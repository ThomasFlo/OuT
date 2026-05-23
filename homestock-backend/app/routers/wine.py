"""Cave à vins endpoints: listing, stats and 'open a bottle'."""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session, joinedload

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync

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
    obj.vin.nombre_bouteilles = current - 1
    if obj.quantite is not None:
        obj.quantite = max(0, obj.quantite - 1)
    db.commit()
    db.refresh(obj)
    broadcast_sync("objet", "updated", obj.id)
    return obj
