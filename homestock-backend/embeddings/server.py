"""Standalone embedding microservice.

Wraps a multilingual sentence-transformers model and exposes a tiny HTTP API.
Runs fully offline once the model has been baked into the image.
"""
import os
from typing import List

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

MODEL_NAME = os.environ.get(
    "MODEL_NAME",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)

app = FastAPI(title="HomeStock Embeddings")
_model: SentenceTransformer | None = None


def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer(MODEL_NAME)
    return _model


class EmbedRequest(BaseModel):
    texts: List[str]


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    dim: int


@app.on_event("startup")
def _warmup() -> None:
    # Load the model eagerly so the first real request is fast.
    get_model()


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest) -> EmbedResponse:
    model = get_model()
    vectors = model.encode(
        req.texts,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    return EmbedResponse(
        embeddings=[v.tolist() for v in vectors],
        dim=int(vectors.shape[1]) if len(vectors) else model.get_sentence_embedding_dimension(),
    )
