from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/emplacements", tags=["emplacements"])


def _with_count(db: Session, emp: models.Emplacement) -> schemas.EmplacementOut:
    nb_objets = (
        db.query(func.count(models.Objet.id))
        .filter(models.Objet.emplacement_id == emp.id)
        .scalar()
    ) or 0
    out = schemas.EmplacementOut.model_validate(emp)
    out.nb_objets = nb_objets
    return out


@router.get("", response_model=list[schemas.EmplacementOut])
def list_emplacements(zone_id: int | None = None, db: Session = Depends(get_db)):
    query = db.query(models.Emplacement)
    if zone_id is not None:
        query = query.filter(models.Emplacement.zone_id == zone_id)
    emps = query.order_by(models.Emplacement.nom_emplacement).all()
    return [_with_count(db, e) for e in emps]


@router.post("", response_model=schemas.EmplacementOut, status_code=201)
def create_emplacement(payload: schemas.EmplacementCreate, db: Session = Depends(get_db)):
    if not db.get(models.Zone, payload.zone_id):
        raise HTTPException(404, "Zone introuvable")
    emp = models.Emplacement(**payload.model_dump())
    db.add(emp)
    db.commit()
    db.refresh(emp)
    broadcast_sync("emplacement", "created", emp.id)
    return _with_count(db, emp)


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
    return _with_count(db, emp)


@router.delete("/{emp_id}", status_code=204)
def delete_emplacement(emp_id: int, db: Session = Depends(get_db)):
    """Delete an emplacement only if it contains no object.

    To remove a non-empty emplacement, the client must first migrate its
    contents via POST /emplacements/{emp_id}/migrate.
    """
    emp = db.get(models.Emplacement, emp_id)
    if not emp:
        raise HTTPException(404, "Emplacement introuvable")
    nb_objets = (
        db.query(func.count(models.Objet.id))
        .filter(models.Objet.emplacement_id == emp_id)
        .scalar()
    ) or 0
    if nb_objets > 0:
        raise HTTPException(
            409,
            f"Emplacement non vide ({nb_objets} objet(s)). "
            "Migrez son contenu vers un autre emplacement avant de le supprimer.",
        )
    db.delete(emp)
    db.commit()
    broadcast_sync("emplacement", "deleted", emp_id)


@router.post("/{emp_id}/migrate", status_code=204)
def migrate_emplacement(
    emp_id: int,
    target_id: int,
    delete_source: bool = False,
    db: Session = Depends(get_db),
):
    """Reassign every objet of ``emp_id`` to ``target_id``.

    Optionally deletes the source emplacement in the same transaction once
    empty. Both emplacements must exist and be distinct.
    """
    if emp_id == target_id:
        raise HTTPException(400, "L'emplacement source et la cible doivent différer")
    source = db.get(models.Emplacement, emp_id)
    if not source:
        raise HTTPException(404, "Emplacement source introuvable")
    target = db.get(models.Emplacement, target_id)
    if not target:
        raise HTTPException(404, "Emplacement cible introuvable")

    (
        db.query(models.Objet)
        .filter(models.Objet.emplacement_id == emp_id)
        .update({models.Objet.emplacement_id: target_id}, synchronize_session=False)
    )
    if delete_source:
        db.delete(source)
    db.commit()

    broadcast_sync("emplacement", "updated", target_id)
    if delete_source:
        broadcast_sync("emplacement", "deleted", emp_id)
    else:
        broadcast_sync("emplacement", "updated", emp_id)
