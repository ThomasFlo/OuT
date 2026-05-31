"""Default seed data: 21 zones and the predefined category list."""
from sqlalchemy import func
from sqlalchemy.orm import Session

from . import models

DEFAULT_ZONES = [
    # Intérieur — Maison (15)
    ("Entrée", "door-front", "#4A90D9"),
    ("Couloir", "horizontal-rule", "#4A90D9"),
    ("Salon", "weekend", "#4A90D9"),
    ("Salle à manger", "dining", "#4A90D9"),
    ("Cuisine", "kitchen", "#4A90D9"),
    ("Cellier / Arrière-cuisine", "shelves", "#4A90D9"),
    ("Chambre principale", "bed", "#4A90D9"),
    ("Chambre 2", "bed", "#4A90D9"),
    ("Chambre 3", "bed", "#4A90D9"),
    ("Salle de bain principale", "bathtub", "#4A90D9"),
    ("Salle de bain 2 / WC", "wc", "#4A90D9"),
    ("Bureau", "desk", "#4A90D9"),
    ("Grenier", "roofing", "#4A90D9"),
    ("Cave", "cellar", "#4A90D9"),
    ("Buanderie", "local-laundry-service", "#4A90D9"),
    # Extérieur (6)
    ("Abri 1", "warehouse", "#00897B"),
    ("Abri 2", "warehouse", "#00897B"),
    ("Abri 3", "warehouse", "#00897B"),
    ("Abri 4", "warehouse", "#00897B"),
    ("Terrasse", "deck", "#00897B"),
    ("Coin barbecue", "outdoor-grill", "#00897B"),
]

DEFAULT_CATEGORIES = [
    "Outillage",
    "Électricité & plomberie",
    "Jardinage",
    "Sport & loisirs",
    "Vêtements & chaussures",
    "Ski & montagne",
    "Équipement de camping",
    "Décoration & saisonnier",
    "Alimentation & épicerie",
    "Boissons & cave à vins",
    "Produits ménagers",
    "Pharmacie & santé",
    "Papiers & documents",
    "Électronique & câbles",
    "Jouets & jeux",
    "Livres & médias",
    "Bricolage & visserie",
    "Autre",
]


# The wine category is special-cased by the apps (dedicated cellar screen),
# so it must keep this exact label — it is seeded as protected.
WINE_CATEGORY = "Boissons & cave à vins"


def seed_zones(db: Session) -> None:
    """Insert the 21 default zones only if no zone exists yet."""
    existing = db.query(models.Zone).count()
    if existing:
        return
    for order, (nom, icone, couleur) in enumerate(DEFAULT_ZONES):
        db.add(models.Zone(nom=nom, icone=icone, couleur=couleur, ordre=order))
    db.commit()


def seed_categories(db: Session) -> None:
    """Ensure the categories table is populated.

    Seeds the defaults when empty, then reconciles with any category label
    already used by existing objects (so pre-existing or imported data with
    custom categories shows up as a manageable category).
    """
    existing_names = {c.nom for c in db.query(models.Category.nom).all()}
    if not existing_names:
        for order, nom in enumerate(DEFAULT_CATEGORIES):
            db.add(models.Category(nom=nom, ordre=order, protegee=(nom == WINE_CATEGORY)))
            existing_names.add(nom)

    used = {row[0] for row in db.query(models.Objet.categorie).distinct().all() if row[0]}
    next_order = (db.query(func.max(models.Category.ordre)).scalar() or 0) + 1
    for nom in sorted(used - existing_names):
        db.add(models.Category(nom=nom, ordre=next_order, protegee=(nom == WINE_CATEGORY)))
        next_order += 1
    db.commit()
