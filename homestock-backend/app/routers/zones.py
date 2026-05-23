from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..init_data import DEFAULT_CATEGORIES
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/zones", tags=["zones"])


def _with_count(db: Session, zone: models.Zone) -> schemas.ZoneOut:
    count = (
        db.query(func.count(models.Objet.id))
        .join(models.Emplacement, models.Objet.emplacement_id == models.Emplacement.id)
        .filter(models.Emplacement.zone_id == zone.id)
        .scalar()
    )
    out = schemas.ZoneOut.model_validate(zone)
    out.nb_objets = count or 0
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
    zone = db.get(models.Zone, zone_id)
    if not zone:
        raise HTTPException(404, "Zone introuvable")
    db.delete(zone)
    db.commit()
    broadcast_sync("zone", "deleted", zone_id)


@router.get("/categories", response_model=list[str], tags=["categories"])
def list_categories():
    return DEFAULT_CATEGORIES
