"""Client for the standalone embedding microservice.

Network failures are swallowed so that the API keeps working (objects are
simply stored without a vector and fall back to full-text search).
"""
import logging
from typing import List

import urllib.error
import urllib.request
import json

from ..database import EMBEDDING_SERVICE_URL

log = logging.getLogger("homestock.embedding")


def _post(path: str, payload: dict, timeout: float = 30.0) -> dict | None:
    url = f"{EMBEDDING_SERVICE_URL}{path}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        log.warning("Embedding service unavailable: %s", exc)
        return None


def embed_texts(texts: List[str]) -> List[List[float]] | None:
    if not texts:
        return []
    result = _post("/embed", {"texts": texts})
    if result is None:
        return None
    return result.get("embeddings")


def embed_one(text: str) -> List[float] | None:
    vectors = embed_texts([text])
    if not vectors:
        return None
    return vectors[0]


def build_embedding_text(nom: str, description: str | None, categorie: str | None) -> str:
    """Concatenate the fields used to build an object's semantic vector."""
    parts = [nom]
    if categorie:
        parts.append(categorie)
    if description:
        parts.append(description)
    return " — ".join(p for p in parts if p)
