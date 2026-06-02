"""Cave à vins endpoints: listing, stats, enrichment and 'open a bottle'."""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, or_
from sqlalchemy.orm import Session, joinedload

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync
from ..services.wine_enrichment import (
    EnrichmentDisabled,
    EnrichmentError,
    enrich_wine,
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
def enrich_vin(objet_id: int, db: Session = Depends(get_db)):
    """Call Claude to fill in the LLM-derived fields on this wine.

    Idempotent: running it twice simply refreshes the enrichment.
    Returns 503 when ANTHROPIC_API_KEY isn't set so the client can show a
    friendly message instead of a generic error.
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
        result = enrich_wine(
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


@router.get("/priority", response_model=list[schemas.WinePriorityItem])
def wines_priority(db: Session = Depends(get_db)):
    """Wines we should drink soon, ranked by urgency.

    A wine is flagged when it has at least one bottle left AND either:
      * we're past its keeping_year_max (drink-by passed, top priority);
      * we're past its apogee_year_max (out of the peak window);
      * keeping_year_max is within ~2 years of today (window closing).

    Wines without enrichment are intentionally NOT returned — we don't
    fabricate urgency. The client surfaces a hint about enriching them.
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
            )
        )
        .all()
    )

    items: list[schemas.WinePriorityItem] = []
    for obj in candidates:
        vin = obj.vin
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

    # Most urgent first: past_limit > past_peak > near_limit, then by
    # keeping_year_max ascending (the sooner it dies, the higher).
    rank = {"past_limit": 0, "past_peak": 1, "near_limit": 2}
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
