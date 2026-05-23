from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session, joinedload

from .. import models, schemas
from ..database import get_db
from ..services.embedding import build_embedding_text, embed_one
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/objets", tags=["objets"])


def _apply_embedding(obj: models.Objet) -> None:
    """(Re)generate the semantic vector from name + sous-cat + notes + category."""
    text = build_embedding_text(
        nom=obj.nom,
        description=" ".join(p for p in [obj.sous_categorie, obj.notes] if p) or None,
        categorie=obj.categorie,
    )
    vector = embed_one(text)
    if vector is not None:
        obj.nom_embedding = vector


def _upsert_vin(db: Session, obj: models.Objet, vin_data: schemas.VinBase) -> None:
    if obj.vin is None:
        db.add(models.Vin(objet_id=obj.id, **vin_data.model_dump()))
    else:
        for key, value in vin_data.model_dump().items():
            setattr(obj.vin, key, value)


def _load(db: Session, obj_id: int) -> models.Objet | None:
    return (
        db.query(models.Objet)
        .options(
            joinedload(models.Objet.emplacement),
            joinedload(models.Objet.vin),
        )
        .filter(models.Objet.id == obj_id)
        .first()
    )


@router.get("", response_model=list[schemas.ObjetOut])
def list_objets(
    zone_id: int | None = None,
    emplacement_id: int | None = None,
    categorie: str | None = None,
    limit: int = 200,
    db: Session = Depends(get_db),
):
    query = db.query(models.Objet).options(
        joinedload(models.Objet.emplacement), joinedload(models.Objet.vin)
    )
    if emplacement_id is not None:
        query = query.filter(models.Objet.emplacement_id == emplacement_id)
    if zone_id is not None:
        query = query.join(models.Emplacement).filter(
            models.Emplacement.zone_id == zone_id
        )
    if categorie:
        query = query.filter(models.Objet.categorie == categorie)
    return query.order_by(models.Objet.date_modification.desc()).limit(limit).all()


@router.get("/recent", response_model=list[schemas.ObjetOut])
def recent_objets(limit: int = 5, db: Session = Depends(get_db)):
    return (
        db.query(models.Objet)
        .options(joinedload(models.Objet.emplacement), joinedload(models.Objet.vin))
        .order_by(models.Objet.date_ajout.desc())
        .limit(limit)
        .all()
    )


@router.get("/{obj_id}", response_model=schemas.ObjetOut)
def get_objet(obj_id: int, db: Session = Depends(get_db)):
    obj = _load(db, obj_id)
    if not obj:
        raise HTTPException(404, "Objet introuvable")
    return obj


@router.post("", response_model=schemas.ObjetOut, status_code=201)
def create_objet(payload: schemas.ObjetCreate, db: Session = Depends(get_db)):
    if not db.get(models.Emplacement, payload.emplacement_id):
        raise HTTPException(404, "Emplacement introuvable")
    data = payload.model_dump(exclude={"vin"})
    obj = models.Objet(**data)
    _apply_embedding(obj)
    db.add(obj)
    db.flush()
    if payload.vin is not None:
        _upsert_vin(db, obj, payload.vin)
    db.commit()
    obj = _load(db, obj.id)
    broadcast_sync("objet", "created", obj.id)
    return obj


@router.put("/{obj_id}", response_model=schemas.ObjetOut)
def update_objet(obj_id: int, payload: schemas.ObjetUpdate, db: Session = Depends(get_db)):
    obj = _load(db, obj_id)
    if not obj:
        raise HTTPException(404, "Objet introuvable")
    fields = payload.model_dump(exclude={"vin"}, exclude_unset=True)
    for key, value in fields.items():
        setattr(obj, key, value)
    # Regenerate embedding if any text field that feeds it changed.
    if {"nom", "sous_categorie", "notes", "categorie"} & set(fields.keys()):
        _apply_embedding(obj)
    if payload.vin is not None:
        _upsert_vin(db, obj, payload.vin)
    db.commit()
    obj = _load(db, obj_id)
    broadcast_sync("objet", "updated", obj.id)
    return obj


@router.delete("/{obj_id}", status_code=204)
def delete_objet(obj_id: int, db: Session = Depends(get_db)):
    obj = db.get(models.Objet, obj_id)
    if not obj:
        raise HTTPException(404, "Objet introuvable")
    db.delete(obj)
    db.commit()
    broadcast_sync("objet", "deleted", obj_id)
