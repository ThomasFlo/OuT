from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..init_data import DEFAULT_CATEGORIES
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/zones", tags=["zones"])


def _with_count(db: Session, zone: models.Zone) -> schemas.ZoneOut:
    nb_objets = (
        db.query(func.count(models.Objet.id))
        .join(models.Emplacement, models.Objet.emplacement_id == models.Emplacement.id)
        .filter(models.Emplacement.zone_id == zone.id)
        .scalar()
    )
    nb_emplacements = (
        db.query(func.count(models.Emplacement.id))
        .filter(models.Emplacement.zone_id == zone.id)
        .scalar()
    )
    out = schemas.ZoneOut.model_validate(zone)
    out.nb_objets = nb_objets or 0
    out.nb_emplacements = nb_emplacements or 0
    return out


@router.get("", response_model=list[schemas.ZoneOut])
def list_zones(db: Session = Depends(get_db)):
    zones = db.query(models.Zone).order_by(models.Zone.ordre, models.Zone.id).all()
    return [_with_count(db, z) for z in zones]


@router.post("", response_model=schemas.ZoneOut, status_code=201)
def create_zone(payload: schemas.ZoneCreate, db: Session = Depends(get_db)):
    zone = models.Zone(**payload.model_dump())
    db.add(zone)
    db.commit()
    db.refresh(zone)
    broadcast_sync("zone", "created", zone.id)
    return _with_count(db, zone)


@router.put("/{zone_id}", response_model=schemas.ZoneOut)
def update_zone(zone_id: int, payload: schemas.ZoneUpdate, db: Session = Depends(get_db)):
    zone = db.get(models.Zone, zone_id)
    if not zone:
        raise HTTPException(404, "Zone introuvable")
    for key, value in payload.model_dump(exclude_unset=True).items():
        setattr(zone, key, value)
    db.commit()
    db.refresh(zone)
    broadcast_sync("zone", "updated", zone.id)
    return _with_count(db, zone)


@router.delete("/{zone_id}", status_code=204)
def delete_zone(zone_id: int, db: Session = Depends(get_db)):
    """Delete a zone only if it is empty.

    To remove a non-empty zone, the client must first migrate its emplacements
    to another zone via POST /zones/{zone_id}/migrate.
    """
    zone = db.get(models.Zone, zone_id)
    if not zone:
        raise HTTPException(404, "Zone introuvable")
    nb_emplacements = (
        db.query(func.count(models.Emplacement.id))
        .filter(models.Emplacement.zone_id == zone_id)
        .scalar()
    ) or 0
    if nb_emplacements > 0:
        raise HTTPException(
            409,
            f"Zone non vide ({nb_emplacements} emplacement(s)). "
            "Migrez son contenu vers une autre zone avant de la supprimer.",
        )
    db.delete(zone)
    db.commit()
    broadcast_sync("zone", "deleted", zone_id)


@router.post("/{zone_id}/migrate", status_code=204)
def migrate_zone(
    zone_id: int,
    target_id: int,
    delete_source: bool = False,
    db: Session = Depends(get_db),
):
    """Reassign every emplacement of ``zone_id`` to ``target_id``.

    Optionally deletes the source zone in the same transaction once empty.
    The two zones must exist and be distinct.
    """
    if zone_id == target_id:
        raise HTTPException(400, "La zone source et la zone cible doivent différer")
    source = db.get(models.Zone, zone_id)
    if not source:
        raise HTTPException(404, "Zone source introuvable")
    target = db.get(models.Zone, target_id)
    if not target:
        raise HTTPException(404, "Zone cible introuvable")

    moved = (
        db.query(models.Emplacement)
        .filter(models.Emplacement.zone_id == zone_id)
        .update({models.Emplacement.zone_id: target_id}, synchronize_session=False)
    )
    if delete_source:
        db.delete(source)
    db.commit()

    # Tell every connected client to refresh: emplacements + both zones moved,
    # plus the source zone if it was deleted.
    broadcast_sync("zone", "updated", target_id)
    if delete_source:
        broadcast_sync("zone", "deleted", zone_id)
    else:
        broadcast_sync("zone", "updated", zone_id)
    if moved:
        broadcast_sync("emplacement", "updated", target_id)


@router.get("/categories", response_model=list[str], tags=["categories"])
def list_categories(db: Session = Depends(get_db)):
    """Legacy endpoint: the flat list of category names.

    Kept for backward compatibility (older app builds call this). New clients
    use the richer /categories router. Falls back to the static defaults if the
    table has not been seeded yet.
    """
    rows = (
        db.query(models.Category.nom)
        .order_by(models.Category.ordre, models.Category.id)
        .all()
    )
    return [r[0] for r in rows] if rows else DEFAULT_CATEGORIES
