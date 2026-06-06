"""Cave à vins endpoints: listing, stats, enrichment and 'open a bottle'."""
import json
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy import func, or_
from sqlalchemy.orm import Session, joinedload

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync
from ..services.wine_enrichment import (
    EnrichmentDisabled,
    EnrichmentError,
    enrich_wine,
    enrich_wine_stream,
    MODEL as ENRICHMENT_MODEL,
)

router = APIRouter(prefix="/vins", tags=["vins"])


@router.get("", response_model=list[schemas.ObjetOut])
def list_vins(
    type: str | None = None,
    millesime: int | None = None,
    domaine: str | None = None,
    db: Session = Depends(get_db),
):
    query = (
        db.query(models.Objet)
        .join(models.Vin, models.Vin.objet_id == models.Objet.id)
        .options(joinedload(models.Objet.emplacement), joinedload(models.Objet.vin))
    )
    if type:
        query = query.filter(models.Vin.type == type)
    if millesime is not None:
        query = query.filter(models.Vin.millesime == millesime)
    if domaine:
        query = query.filter(models.Vin.domaine.ilike(f"%{domaine}%"))
    return query.all()


@router.get("/stats")
def wine_stats(db: Session = Depends(get_db)):
    total = db.query(func.coalesce(func.sum(models.Vin.nombre_bouteilles), 0)).scalar()
    by_type = (
        db.query(models.Vin.type, func.coalesce(func.sum(models.Vin.nombre_bouteilles), 0))
        .group_by(models.Vin.type)
        .all()
    )
    return {
        "total_bouteilles": int(total or 0),
        "par_type": {(t or "Autre"): int(n) for t, n in by_type},
    }


@router.post("/{objet_id}/enrich", response_model=schemas.ObjetOut)
async def enrich_vin(objet_id: int, db: Session = Depends(get_db)):
    """Call the local LLM to fill in the sommelier-derived fields on this wine.

    Idempotent: running it twice simply refreshes the enrichment.
    Returns 503 when no LLM is configured so the client can show a
    friendly message instead of a generic error.

    Async on purpose — the Ollama call can take 60-180 s on a CPU NAS
    during cold start; running sync would tie up a FastAPI worker thread
    for the whole duration and cause the API's TCP accept queue to
    overflow under burst clicks (silent SYN drops → Android ConnectException).
    """
    obj = (
        db.query(models.Objet)
        .options(joinedload(models.Objet.vin), joinedload(models.Objet.emplacement))
        .filter(models.Objet.id == objet_id)
        .first()
    )
    if not obj or not obj.vin:
        raise HTTPException(404, "Vin introuvable")

    try:
        result = await enrich_wine(
            appellation=obj.vin.appellation,
            domaine=obj.vin.domaine,
            millesime=obj.vin.millesime,
            type_=obj.vin.type,
        )
    except EnrichmentDisabled as exc:
        raise HTTPException(503, str(exc))
    except EnrichmentError as exc:
        raise HTTPException(502, str(exc))

    obj.vin.enrichment_summary = result.summary or None
    obj.vin.apogee_year_min = result.apogee_year_min
    obj.vin.apogee_year_max = result.apogee_year_max
    obj.vin.keeping_year_max = result.keeping_year_max
    # Persist the lists as comma-joined text to keep the schema small;
    # the Android client splits on ", ".
    obj.vin.pairings_ideal = ", ".join(result.pairings_ideal) or None
    obj.vin.pairings_possible = ", ".join(result.pairings_possible) or None
    obj.vin.enriched_at = datetime.utcnow()
    obj.vin.enrichment_source = ENRICHMENT_MODEL
    db.commit()
    db.refresh(obj)
    broadcast_sync("objet", "updated", obj.id)
    return obj


@router.post("/{objet_id}/enrich/stream")
async def enrich_vin_stream(objet_id: int, db: Session = Depends(get_db)):
    """Streaming variant of /enrich.

    Returns a newline-delimited JSON stream (application/x-ndjson) with
    progressive events while Llama generates:

      {"type":"summary","text":"Le Moulin-à-Vent..."}   ← partial summary,
                                                          updated every time
                                                          the model emits a
                                                          new chunk
      {"type":"done","objet":{...full ObjetOut DTO...}} ← terminal event
                                                          after sanity checks
                                                          and DB persist
      {"type":"error","message":"..."}                   ← on failure

    The client reads line by line and updates the wine fiche live, so the
    user sees the sommelier sentence type out instead of staring at a
    frozen "Analyse…" for 60-90 s.
    """
    obj = (
        db.query(models.Objet)
        .options(joinedload(models.Objet.vin), joinedload(models.Objet.emplacement))
        .filter(models.Objet.id == objet_id)
        .first()
    )
    if not obj or not obj.vin:
        raise HTTPException(404, "Vin introuvable")

    vin = obj.vin  # bound names for the closure

    async def emit():
        try:
            final = None
            async for event in enrich_wine_stream(
                appellation=vin.appellation,
                domaine=vin.domaine,
                millesime=vin.millesime,
                type_=vin.type,
            ):
                t = event.get("type")
                if t == "summary":
                    yield json.dumps({
                        "type": "summary", "text": event["text"],
                    }, ensure_ascii=False) + "\n"
                elif t == "done":
                    final = event["enrichment"]
            if final is None:
                yield json.dumps({
                    "type": "error",
                    "message": "Stream terminé sans résultat.",
                }) + "\n"
                return

            vin.enrichment_summary = final.summary or None
            vin.apogee_year_min = final.apogee_year_min
            vin.apogee_year_max = final.apogee_year_max
            vin.keeping_year_max = final.keeping_year_max
            vin.pairings_ideal = ", ".join(final.pairings_ideal) or None
            vin.pairings_possible = ", ".join(final.pairings_possible) or None
            vin.enriched_at = datetime.utcnow()
            vin.enrichment_source = ENRICHMENT_MODEL
            db.commit()
            db.refresh(obj)
            broadcast_sync("objet", "updated", obj.id)

            # Send the persisted ObjetOut as the terminal payload so the
            # client can drop the streaming dialog state and render the
            # final fiche.
            payload = schemas.ObjetOut.model_validate(obj).model_dump(
                mode="json"
            )
            yield json.dumps({"type": "done", "objet": payload},
                             ensure_ascii=False) + "\n"
        except EnrichmentDisabled as exc:
            yield json.dumps({
                "type": "error", "message": str(exc),
                "code": "disabled",
            }) + "\n"
        except EnrichmentError as exc:
            yield json.dumps({
                "type": "error", "message": str(exc),
            }) + "\n"

    return StreamingResponse(emit(), media_type="application/x-ndjson")


@router.get("/priority", response_model=list[schemas.WinePriorityItem])
def wines_priority(db: Session = Depends(get_db)):
    """Wines we should drink soon, ranked by urgency.

    A wine is flagged when it has at least one bottle left AND either:
      * we're past its keeping_year_max (drink-by passed, top priority);
      * we're past its apogee_year_max (out of the peak window);
      * keeping_year_max is within ~2 years of today (window closing).

    Returns wines flagged as ranked by urgency:
      * past_limit   — drink-by date passed, top priority (red)
      * past_peak    — out of the peak window (orange)
      * near_limit   — keeping_year_max within ~2 years
      * at_peak      — currently inside the optimal window (drink now)

    Wines whose peak is still years away are intentionally NOT returned —
    they are not actionable yet and would clutter the list.

    Wines without enrichment are also not returned — we don't fabricate
    urgency. The client surfaces a hint about enriching them.
    """
    current_year = datetime.utcnow().year
    near_threshold = current_year + 2

    candidates = (
        db.query(models.Objet)
        .join(models.Vin, models.Vin.objet_id == models.Objet.id)
        .options(joinedload(models.Objet.vin), joinedload(models.Objet.emplacement))
        .filter(
            (models.Vin.nombre_bouteilles.is_(None))
            | (models.Vin.nombre_bouteilles > 0)
        )
        .filter(
            or_(
                models.Vin.keeping_year_max.isnot(None),
                models.Vin.apogee_year_max.isnot(None),
                models.Vin.apogee_year_min.isnot(None),
            )
        )
        .all()
    )

    items: list[schemas.WinePriorityItem] = []
    for obj in candidates:
        vin = obj.vin
        apogee_min = vin.apogee_year_min
        apogee_max = vin.apogee_year_max
        keep_max = vin.keeping_year_max

        urgency: str | None = None
        reason: str | None = None
        if keep_max is not None and current_year > keep_max:
            urgency = "past_limit"
            reason = (
                f"À ne plus boire : date limite passée ({keep_max})."
            )
        elif apogee_max is not None and current_year > apogee_max:
            urgency = "past_peak"
            reason = (
                f"Hors fenêtre optimale depuis {apogee_max}, "
                f"à boire avant {keep_max}." if keep_max else
                f"Hors fenêtre optimale depuis {apogee_max}."
            )
        elif keep_max is not None and keep_max <= near_threshold:
            urgency = "near_limit"
            reason = f"Fenêtre se ferme bientôt (avant {keep_max})."
        elif (
            apogee_min is not None
            and apogee_min <= current_year
            and (apogee_max is None or current_year <= apogee_max)
        ):
            urgency = "at_peak"
            reason = (
                f"Dans sa fenêtre d'apogée (jusqu'à {apogee_max})."
                if apogee_max else "Dans sa fenêtre d'apogée."
            )

        if urgency is None:
            continue

        items.append(
            schemas.WinePriorityItem(
                objet_id=obj.id,
                nom=obj.nom,
                appellation=vin.appellation,
                domaine=vin.domaine,
                millesime=vin.millesime,
                type=vin.type,
                nombre_bouteilles=vin.nombre_bouteilles,
                apogee_year_max=apogee_max,
                keeping_year_max=keep_max,
                photo_url=obj.photo_url,
                urgency=urgency,
                reason=reason,
            )
        )

    # Most urgent first: past_limit > past_peak > near_limit > at_peak.
    # Within a tier, prefer the one whose window dies sooner — that's the
    # bottle to open next.
    rank = {"past_limit": 0, "past_peak": 1, "near_limit": 2, "at_peak": 3}
    items.sort(key=lambda x: (rank[x.urgency], x.keeping_year_max or 9999))
    return items


@router.post("/{objet_id}/deboucher", response_model=schemas.ObjetOut)
def open_bottle(objet_id: int, db: Session = Depends(get_db)):
    obj = (
        db.query(models.Objet)
        .options(joinedload(models.Objet.vin), joinedload(models.Objet.emplacement))
        .filter(models.Objet.id == objet_id)
        .first()
    )
    if not obj or not obj.vin:
        raise HTTPException(404, "Vin introuvable")
    current = obj.vin.nombre_bouteilles or 0
    if current <= 0:
        raise HTTPException(400, "Plus aucune bouteille")
    # The wine bottle count is the single source of truth; the generic
    # objet.quantite is intentionally left untouched to avoid double-counting.
    obj.vin.nombre_bouteilles = current - 1
    db.commit()
    db.refresh(obj)
    broadcast_sync("objet", "updated", obj.id)
    return obj
