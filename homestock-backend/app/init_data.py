"""Default seed data: 21 zones and the predefined category list."""
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


def seed_zones(db: Session) -> None:
    """Insert the 21 default zones only if no zone exists yet."""
    existing = db.query(models.Zone).count()
    if existing:
        return
    for order, (nom, icone, couleur) in enumerate(DEFAULT_ZONES):
        db.add(models.Zone(nom=nom, icone=icone, couleur=couleur, ordre=order))
    db.commit()
