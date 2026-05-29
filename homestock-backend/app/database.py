"""Database engine, session factory and shared settings."""
import os

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql+psycopg2://homestock:homestock@localhost:5432/homestock",
)
EMBEDDING_SERVICE_URL = os.environ.get(
    "EMBEDDING_SERVICE_URL", "http://localhost:9000"
)
PHOTOS_DIR = os.environ.get("PHOTOS_DIR", "./photos")
EMBEDDING_DIM = int(os.environ.get("EMBEDDING_DIM", "384"))
# Minimum cosine similarity for a semantic match. Tuned empirically for the
# multilingual MiniLM model (normalized embeddings); override via env if noisy.
SEMANTIC_THRESHOLD = float(os.environ.get("SEMANTIC_THRESHOLD", "0.4"))

engine = create_engine(DATABASE_URL, pool_pre_ping=True, future=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
