"""Category CRUD.

Categories used to be a static list. They are now a real table so the apps
can add / rename / delete them. Objects still store their category as a plain
string (``objets.categorie``); renaming or deleting therefore performs a bulk
string update on the matching objects, mirroring the migrate-on-delete pattern
used for zones and emplacements.

The wine category is flagged ``protegee`` because both apps special-case its
exact label for the dedicated cellar screen; it cannot be renamed or deleted.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.websocket import broadcast_sync

router = APIRouter(prefix="/categories", tags=["categories"])


def _nb_objets(db: Session, nom: str) -> int:
    return (
        db.query(func.count(models.Objet.id))
        .filter(models.Objet.categorie == nom)
        .scalar()
    ) or 0


def _with_count(db: Session, cat: models.Category) -> schemas.CategoryOut:
    out = schemas.CategoryOut.model_validate(cat)
    out.nb_objets = _nb_objets(db, cat.nom)
    return out


@router.get("", response_model=list[schemas.CategoryOut])
def list_categories(db: Session = Depends(get_db)):
    cats = (
        db.query(models.Category)
        .order_by(models.Category.ordre, models.Category.id)
        .all()
    )
    return [_with_count(db, c) for c in cats]


@router.post("", response_model=schemas.CategoryOut, status_code=201)
def create_category(payload: schemas.CategoryCreate, db: Session = Depends(get_db)):
    nom = payload.nom.strip()
    if not nom:
        raise HTTPException(400, "Le nom ne peut pas être vide")
    if db.query(models.Category).filter(models.Category.nom == nom).first():
        raise HTTPException(409, "Cette catégorie existe déjà")
    max_ordre = db.query(func.max(models.Category.ordre)).scalar() or 0
    cat = models.Category(nom=nom, ordre=max_ordre + 1)
    db.add(cat)
    db.commit()
    db.refresh(cat)
    broadcast_sync("category", "created", cat.id)
    return _with_count(db, cat)


@router.put("/{cat_id}", response_model=schemas.CategoryOut)
def update_category(
    cat_id: int, payload: schemas.CategoryUpdate, db: Session = Depends(get_db)
):
    cat = db.get(models.Category, cat_id)
    if not cat:
        raise HTTPException(404, "Catégorie introuvable")
    if cat.protegee:
        raise HTTPException(403, "Cette catégorie système ne peut pas être renommée")
    new_nom = payload.nom.strip()
    if not new_nom:
        raise HTTPException(400, "Le nom ne peut pas être vide")
    clash = (
        db.query(models.Category)
        .filter(models.Category.nom == new_nom, models.Category.id != cat_id)
        .first()
    )
    if clash:
        raise HTTPException(409, "Une autre catégorie porte déjà ce nom")

    old_nom = cat.nom
    cat.nom = new_nom
    # Keep every object in sync with the rename (categorie is a plain string).
    if old_nom != new_nom:
        db.query(models.Objet).filter(models.Objet.categorie == old_nom).update(
            {models.Objet.categorie: new_nom}, synchronize_session=False
        )
    db.commit()
    db.refresh(cat)
    broadcast_sync("category", "updated", cat.id)
    return _with_count(db, cat)


@router.delete("/{cat_id}", status_code=204)
def delete_category(cat_id: int, db: Session = Depends(get_db)):
    """Delete a category only if no object uses it.

    To remove a category that still has objects, migrate them first via
    POST /categories/{cat_id}/migrate.
    """
    cat = db.get(models.Category, cat_id)
    if not cat:
        raise HTTPException(404, "Catégorie introuvable")
    if cat.protegee:
        raise HTTPException(403, "Cette catégorie système ne peut pas être supprimée")
    nb = _nb_objets(db, cat.nom)
    if nb > 0:
        raise HTTPException(
            409,
            f"Catégorie non vide ({nb} objet(s)). "
            "Réaffectez ces objets à une autre catégorie avant de la supprimer.",
        )
    db.delete(cat)
    db.commit()
    broadcast_sync("category", "deleted", cat_id)


@router.post("/{cat_id}/migrate", status_code=204)
def migrate_category(
    cat_id: int,
    target_id: int,
    delete_source: bool = False,
    db: Session = Depends(get_db),
):
    """Reassign every object of ``cat_id`` to ``target_id``'s category.

    Optionally deletes the source category once empty. Both must exist and
    differ; the source must not be protected when ``delete_source`` is set.
    """
    if cat_id == target_id:
        raise HTTPException(400, "La catégorie source et la cible doivent différer")
    source = db.get(models.Category, cat_id)
    if not source:
        raise HTTPException(404, "Catégorie source introuvable")
    target = db.get(models.Category, target_id)
    if not target:
        raise HTTPException(404, "Catégorie cible introuvable")
    if delete_source and source.protegee:
        raise HTTPException(403, "Cette catégorie système ne peut pas être supprimée")

    db.query(models.Objet).filter(models.Objet.categorie == source.nom).update(
        {models.Objet.categorie: target.nom}, synchronize_session=False
    )
    if delete_source:
        db.delete(source)
    db.commit()

    broadcast_sync("category", "updated", target_id)
    if delete_source:
        broadcast_sync("category", "deleted", cat_id)
    else:
        broadcast_sync("category", "updated", cat_id)


@router.post("/reorder", status_code=204)
def reorder_categories(payload: list[int], db: Session = Depends(get_db)):
    """Apply a new order to existing categories.

    Body is the list of category IDs in their desired order. Mirrors
    POST /zones/reorder — see that endpoint for the contract.
    """
    cats_by_id = {c.id: c for c in db.query(models.Category).all()}
    for position, cat_id in enumerate(payload):
        cat = cats_by_id.get(cat_id)
        if cat is not None:
            cat.ordre = position
    db.commit()
    broadcast_sync("category", "updated", 0)
