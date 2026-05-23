"""SQLAlchemy ORM models for HomeStock, including the pgvector embedding column."""
from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import EMBEDDING_DIM, Base


class Zone(Base):
    __tablename__ = "zones"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    nom: Mapped[str] = mapped_column(String(120), nullable=False)
    icone: Mapped[str] = mapped_column(String(64), default="home")
    couleur: Mapped[str] = mapped_column(String(16), default="#4A90D9")
    actif: Mapped[bool] = mapped_column(Boolean, default=True)
    ordre: Mapped[int] = mapped_column(Integer, default=0)

    emplacements: Mapped[list["Emplacement"]] = relationship(
        back_populates="zone", cascade="all, delete-orphan"
    )


class Emplacement(Base):
    __tablename__ = "emplacements"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    zone_id: Mapped[int] = mapped_column(
        ForeignKey("zones.id", ondelete="CASCADE"), nullable=False, index=True
    )
    nom_emplacement: Mapped[str] = mapped_column(String(255), nullable=False)
    photo_url: Mapped[str | None] = mapped_column(String(512), nullable=True)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)

    zone: Mapped["Zone"] = relationship(back_populates="emplacements")
    objets: Mapped[list["Objet"]] = relationship(
        back_populates="emplacement", cascade="all, delete-orphan"
    )


class Objet(Base):
    __tablename__ = "objets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    nom: Mapped[str] = mapped_column(String(255), nullable=False)
    nom_embedding: Mapped[list[float] | None] = mapped_column(
        Vector(EMBEDDING_DIM), nullable=True
    )
    emplacement_id: Mapped[int] = mapped_column(
        ForeignKey("emplacements.id", ondelete="CASCADE"), nullable=False, index=True
    )
    categorie: Mapped[str] = mapped_column(String(120), default="Autre")
    sous_categorie: Mapped[str | None] = mapped_column(String(255), nullable=True)
    quantite: Mapped[int | None] = mapped_column(Integer, nullable=True)
    unite: Mapped[str | None] = mapped_column(String(64), nullable=True)
    etat: Mapped[str | None] = mapped_column(String(64), nullable=True)
    date_expiration: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    photo_url: Mapped[str | None] = mapped_column(String(512), nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    ajoute_par: Mapped[str | None] = mapped_column(String(120), nullable=True)
    date_ajout: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    date_modification: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    emplacement: Mapped["Emplacement"] = relationship(back_populates="objets")
    vin: Mapped["Vin | None"] = relationship(
        back_populates="objet", cascade="all, delete-orphan", uselist=False
    )


class Vin(Base):
    __tablename__ = "vins"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    objet_id: Mapped[int] = mapped_column(
        ForeignKey("objets.id", ondelete="CASCADE"), nullable=False, unique=True
    )
    appellation: Mapped[str | None] = mapped_column(String(255), nullable=True)
    domaine: Mapped[str | None] = mapped_column(String(255), nullable=True)
    millesime: Mapped[int | None] = mapped_column(Integer, nullable=True)
    type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    nombre_bouteilles: Mapped[int | None] = mapped_column(Integer, nullable=True)
    emplacement_rangee: Mapped[str | None] = mapped_column(String(255), nullable=True)
    notes_degustation: Mapped[str | None] = mapped_column(Text, nullable=True)
    prix_achat: Mapped[float | None] = mapped_column(Float, nullable=True)
    a_boire_partir: Mapped[int | None] = mapped_column(Integer, nullable=True)

    objet: Mapped["Objet"] = relationship(back_populates="vin")
