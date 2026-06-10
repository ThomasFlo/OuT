"""HomeStock FastAPI application: REST API, WebSocket sync, startup seeding."""
import logging

from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text
from sqlalchemy.orm import Session

from . import models, schemas
from .database import Base, engine, get_db
from .init_data import seed_categories, seed_zones
from .routers import (
    app_update,
    categories,
    locations,
    objects,
    photos,
    search,
    wine,
    zones,
)
from .services.websocket import manager, set_event_loop

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("homestock")

app = FastAPI(title="HomeStock API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(zones.router)
app.include_router(categories.router)
app.include_router(locations.router)
app.include_router(objects.router)
app.include_router(search.router)
app.include_router(wine.router)
app.include_router(photos.router)
app.include_router(app_update.router)


@app.on_event("startup")
async def on_startup() -> None:
    import asyncio

    set_event_loop(asyncio.get_running_loop())
    # Ensure pgvector extension exists, then create tables and indexes.
    with engine.begin() as conn:
        conn.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
        conn.execute(text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
    Base.metadata.create_all(bind=engine)
    with engine.begin() as conn:
        # Idempotent migrations for the wine enrichment columns added after
        # the initial schema. Postgres' "ADD COLUMN IF NOT EXISTS" makes
        # re-running on a fresh table a no-op.
        for ddl in (
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS enrichment_summary TEXT",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS apogee_year_min INTEGER",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS apogee_year_max INTEGER",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS keeping_year_max INTEGER",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS pairings_ideal TEXT",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS pairings_possible TEXT",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMP",
            "ALTER TABLE vins ADD COLUMN IF NOT EXISTS enrichment_source VARCHAR(64)",
        ):
            conn.execute(text(ddl))
    with engine.begin() as conn:
        # IVFFlat index for fast approximate cosine search.
        conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS objets_embedding_idx "
                "ON objets USING ivfflat (nom_embedding vector_cosine_ops) "
                "WITH (lists = 100)"
            )
        )
        # GIN trigram index to accelerate full-text / fuzzy matching.
        conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS objets_nom_trgm_idx "
                "ON objets USING gin (nom gin_trgm_ops)"
            )
        )
    from .database import SessionLocal

    db = SessionLocal()
    try:
        seed_zones(db)
        seed_categories(db)
    finally:
        db.close()
    # Calibrate the IVFFlat index against current row stats so the first
    # semantic search after a fresh install isn't a sequential scan.
    with engine.begin() as conn:
        conn.execute(text("ANALYZE objets"))

    # Pre-warm the wine-enrichment LLM so the user's first analyse doesn't
    # pay the ~30-60 s cold start to page Llama 3.2 into RAM. Fire and
    # forget — if Ollama isn't reachable we just log and move on; the
    # enrichment endpoint will surface a friendly error on first use.
    asyncio.create_task(_prewarm_ollama())

    log.info("HomeStock API ready.")


async def _prewarm_ollama() -> None:
    """Send a tiny request to Ollama at startup to load the model in RAM.

    Without keep_alive=-1 in the inference call AND a warmup at boot,
    every cold call would re-page ~2 GB of weights from disk, costing
    30-60 s on weak NAS CPUs."""
    import os
    import httpx as _httpx

    base = os.environ.get("OLLAMA_BASE_URL", "http://homestock-ollama:11434")
    model = os.environ.get("OLLAMA_MODEL", "llama3.2:3b")
    if not base:
        return
    payload = {
        "model": model,
        "prompt": "ok",
        "stream": False,
        "keep_alive": -1,
        "options": {"num_predict": 1, "num_ctx": 512},
    }
    try:
        async with _httpx.AsyncClient(timeout=120.0) as client:
            r = await client.post(f"{base}/api/generate", json=payload)
            if r.status_code == 200:
                log.info("Ollama pre-warmed (model %s loaded in RAM)", model)
            else:
                log.warning(
                    "Ollama pre-warm got status %d: %s",
                    r.status_code, r.text[:120],
                )
    except Exception as exc:  # noqa: BLE001
        log.warning("Ollama pre-warm failed: %s", exc)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True:
            # Keep the connection alive; clients may send pings.
            await websocket.receive_text()
    except WebSocketDisconnect:
        await manager.disconnect(websocket)


@app.get("/export")
def export_data(db: Session = Depends(get_db)):
    """Export all data as JSON for backup / migration."""
    zones_list = db.query(models.Zone).all()
    emplacements = db.query(models.Emplacement).all()
    objets = db.query(models.Objet).all()
    vins = db.query(models.Vin).all()

    def zone_dict(z):
        return {
            "id": z.id, "nom": z.nom, "icone": z.icone, "couleur": z.couleur,
            "actif": z.actif, "ordre": z.ordre,
        }

    def emp_dict(e):
        return {
            "id": e.id, "zone_id": e.zone_id, "nom_emplacement": e.nom_emplacement,
            "photo_url": e.photo_url, "description": e.description,
        }

    def obj_dict(o):
        return {
            "id": o.id, "nom": o.nom, "emplacement_id": o.emplacement_id,
            "categorie": o.categorie, "sous_categorie": o.sous_categorie,
            "quantite": o.quantite, "unite": o.unite, "etat": o.etat,
            "date_expiration": o.date_expiration.isoformat() if o.date_expiration else None,
            "photo_url": o.photo_url, "notes": o.notes, "ajoute_par": o.ajoute_par,
        }

    def vin_dict(v):
        return {
            "objet_id": v.objet_id, "appellation": v.appellation, "domaine": v.domaine,
            "millesime": v.millesime, "type": v.type,
            "nombre_bouteilles": v.nombre_bouteilles,
            "emplacement_rangee": v.emplacement_rangee,
            "notes_degustation": v.notes_degustation, "prix_achat": v.prix_achat,
            "a_boire_partir": v.a_boire_partir,
        }

    return {
        "zones": [zone_dict(z) for z in zones_list],
        "emplacements": [emp_dict(e) for e in emplacements],
        "objets": [obj_dict(o) for o in objets],
        "vins": [vin_dict(v) for v in vins],
    }


@app.post("/import")
def import_data(
    payload: dict,
    replace: bool = False,
    db: Session = Depends(get_db),
):
    """Import a previously exported JSON dump.

    By default the import is rejected if the database already contains data,
    to avoid creating silent duplicates on repeated imports. Pass
    ``?replace=true`` to wipe zones / emplacements / objets / vins first.
    """
    from .routers.objects import _apply_embedding

    if replace:
        # FK cascades take care of emplacements / objets / vins.
        db.query(models.Vin).delete()
        db.query(models.Objet).delete()
        db.query(models.Emplacement).delete()
        db.query(models.Zone).delete()
        db.flush()
    else:
        existing = (
            db.query(models.Zone).count()
            + db.query(models.Objet).count()
        )
        if existing:
            raise HTTPException(
                409,
                "La base contient déjà des données. "
                "Utilisez ?replace=true pour écraser.",
            )

    id_map_zone: dict[int, int] = {}
    id_map_emp: dict[int, int] = {}

    for z in payload.get("zones", []):
        zone = models.Zone(
            nom=z["nom"], icone=z.get("icone", "home"),
            couleur=z.get("couleur", "#4A90D9"),
            actif=z.get("actif", True), ordre=z.get("ordre", 0),
        )
        db.add(zone)
        db.flush()
        id_map_zone[z["id"]] = zone.id

    for e in payload.get("emplacements", []):
        emp = models.Emplacement(
            zone_id=id_map_zone.get(e["zone_id"], e["zone_id"]),
            nom_emplacement=e["nom_emplacement"],
            photo_url=e.get("photo_url"), description=e.get("description"),
        )
        db.add(emp)
        db.flush()
        id_map_emp[e["id"]] = emp.id

    vins_by_objet = {v["objet_id"]: v for v in payload.get("vins", [])}
    for o in payload.get("objets", []):
        obj = models.Objet(
            nom=o["nom"],
            emplacement_id=id_map_emp.get(o["emplacement_id"], o["emplacement_id"]),
            categorie=o.get("categorie", "Autre"),
            sous_categorie=o.get("sous_categorie"), quantite=o.get("quantite"),
            unite=o.get("unite"), etat=o.get("etat"), photo_url=o.get("photo_url"),
            notes=o.get("notes"), ajoute_par=o.get("ajoute_par"),
        )
        _apply_embedding(obj)
        db.add(obj)
        db.flush()
        if o["id"] in vins_by_objet:
            v = vins_by_objet[o["id"]]
            db.add(models.Vin(
                objet_id=obj.id, appellation=v.get("appellation"),
                domaine=v.get("domaine"), millesime=v.get("millesime"),
                type=v.get("type"), nombre_bouteilles=v.get("nombre_bouteilles"),
                emplacement_rangee=v.get("emplacement_rangee"),
                notes_degustation=v.get("notes_degustation"),
                prix_achat=v.get("prix_achat"), a_boire_partir=v.get("a_boire_partir"),
            ))
    db.commit()
    return {"status": "ok"}
