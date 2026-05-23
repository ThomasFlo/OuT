from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/emplacements", tags=["emplacements"])


@router.get("", response_model=list[schemas.EmplacementOut])
def list_emplacements(zone_id: int | None = None, db: Session = Depends(get_db)):
    query = db.query(models.Emplacement)
    if zone_id is not None:
        query = query.filter(models.Emplacement.zone_id == zone_id)
    return query.order_by(models.Emplacement.nom_emplacement).all()


@router.post("", response_model=schemas.EmplacementOut, status_code=201)
def create_emplacement(payload: schemas.EmplacementCreate, db: Session = Depends(get_db)):
    if not db.get(models.Zone, payload.zone_id):
        raise HTTPException(404, "Zone introuvable")
    emp = models.Emplacement(**payload.model_dump())
    db.add(emp)
    db.commit()
    db.refresh(emp)
    broadcast_sync("emplacement", "created", emp.id)
    return emp


@router.put("/{emp_id}", response_model=schemas.EmplacementOut)
def update_emplacement(
    emp_id: int, payload: schemas.EmplacementUpdate, db: Session = Depends(get_db)
):
    emp = db.get(models.Emplacement, emp_id)
    if not emp:
        raise HTTPException(404, "Emplacement introuvable")
    for key, value in payload.model_dump(exclude_unset=True).items():
        setattr(emp, key, value)
    db.commit()
    db.refresh(emp)
    broadcast_sync("emplacement", "updated", emp.id)
    return emp


@router.delete("/{emp_id}", status_code=204)
def delete_emplacement(emp_id: int, db: Session = Depends(get_db)):
    emp = db.get(models.Emplacement, emp_id)
    if not emp:
        raise HTTPException(404, "Emplacement introuvable")
    db.delete(emp)
    db.commit()
    broadcast_sync("emplacement", "deleted", emp_id)
