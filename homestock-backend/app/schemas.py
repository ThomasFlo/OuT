"""Pydantic request/response schemas."""
from datetime import datetime

from pydantic import BaseModel, ConfigDict


# ----- Zones -----
class ZoneBase(BaseModel):
    nom: str
    icone: str = "home"
    couleur: str = "#4A90D9"
    actif: bool = True
    ordre: int = 0


class ZoneCreate(ZoneBase):
    pass


class ZoneUpdate(BaseModel):
    nom: str | None = None
    icone: str | None = None
    couleur: str | None = None
    actif: bool | None = None
    ordre: int | None = None


class ZoneOut(ZoneBase):
    model_config = ConfigDict(from_attributes=True)
    id: int
    nb_objets: int = 0


# ----- Emplacements -----
class EmplacementBase(BaseModel):
    zone_id: int
    nom_emplacement: str
    photo_url: str | None = None
    description: str | None = None


class EmplacementCreate(EmplacementBase):
    pass


class EmplacementUpdate(BaseModel):
    zone_id: int | None = None
    nom_emplacement: str | None = None
    photo_url: str | None = None
    description: str | None = None


class EmplacementOut(EmplacementBase):
    model_config = ConfigDict(from_attributes=True)
    id: int


# ----- Vin -----
class VinBase(BaseModel):
    appellation: str | None = None
    domaine: str | None = None
    millesime: int | None = None
    type: str | None = None
    nombre_bouteilles: int | None = None
    emplacement_rangee: str | None = None
    notes_degustation: str | None = None
    prix_achat: float | None = None
    a_boire_partir: int | None = None


class VinOut(VinBase):
    model_config = ConfigDict(from_attributes=True)
    id: int
    objet_id: int


# ----- Objets -----
class ObjetBase(BaseModel):
    nom: str
    emplacement_id: int
    categorie: str = "Autre"
    sous_categorie: str | None = None
    quantite: int | None = None
    unite: str | None = None
    etat: str | None = None
    date_expiration: datetime | None = None
    photo_url: str | None = None
    notes: str | None = None
    ajoute_par: str | None = None


class ObjetCreate(ObjetBase):
    vin: VinBase | None = None


class ObjetUpdate(BaseModel):
    nom: str | None = None
    emplacement_id: int | None = None
    categorie: str | None = None
    sous_categorie: str | None = None
    quantite: int | None = None
    unite: str | None = None
    etat: str | None = None
    date_expiration: datetime | None = None
    photo_url: str | None = None
    notes: str | None = None
    ajoute_par: str | None = None
    vin: VinBase | None = None


class ObjetOut(ObjetBase):
    model_config = ConfigDict(from_attributes=True)
    id: int
    date_ajout: datetime
    date_modification: datetime
    emplacement: EmplacementOut | None = None
    vin: VinOut | None = None


class ObjetSearchResult(ObjetOut):
    score: float = 0.0
    zone_nom: str | None = None


# ----- Search -----
class SearchRequest(BaseModel):
    query: str
    limit: int = 20
    # None -> server falls back to the configured SEMANTIC_THRESHOLD default.
    threshold: float | None = None


# ----- WebSocket sync event -----
class SyncEvent(BaseModel):
    entity: str  # "objet" | "zone" | "emplacement"
    action: str  # "created" | "updated" | "deleted"
    id: int
    data: dict | None = None
