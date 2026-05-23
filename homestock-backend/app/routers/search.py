"""Hybrid search: pgvector cosine similarity fused with French full-text search.

Results from both rankers are merged with Reciprocal Rank Fusion (RRF), which
is robust to the different score scales of the two methods.
"""
from fastapi import APIRouter, Depends
from sqlalchemy import func, text
from sqlalchemy.orm import Session, joinedload

from .. import models, schemas
from ..database import get_db
from ..services.embedding import embed_one

router = APIRouter(prefix="/search", tags=["search"])

RRF_K = 60  # standard RRF damping constant


def _semantic_ranking(db: Session, query: str, limit: int, threshold: float):
    """Return [(objet_id, cosine_similarity)] above threshold, best first."""
    vector = embed_one(query)
    if vector is None:
        return []
    # pgvector cosine distance operator is <=> ; similarity = 1 - distance.
    distance = models.Objet.nom_embedding.cosine_distance(vector)
    rows = (
        db.query(models.Objet.id, distance.label("dist"))
        .filter(models.Objet.nom_embedding.isnot(None))
        .order_by(distance)
        .limit(limit * 3)
        .all()
    )
    out = []
    for obj_id, dist in rows:
        similarity = 1.0 - float(dist)
        if similarity >= threshold:
            out.append((obj_id, similarity))
    return out


_FTS_DOC = (
    "to_tsvector('french', "
    "coalesce(nom,'') || ' ' || coalesce(sous_categorie,'') || ' ' || "
    "coalesce(notes,'') || ' ' || coalesce(categorie,''))"
)


def _fulltext_ranking(db: Session, query: str, limit: int):
    """French ts_vector ranking over nom + sous_categorie + notes + categorie."""
    sql = text(
        f"SELECT id, ts_rank({_FTS_DOC}, plainto_tsquery('french', :q)) AS rank "
        f"FROM objets WHERE {_FTS_DOC} @@ plainto_tsquery('french', :q) "
        "ORDER BY rank DESC LIMIT :lim"
    )
    rows = db.execute(sql, {"q": query, "lim": limit * 3}).all()
    return [(row[0], float(row[1])) for row in rows]


def _reciprocal_rank_fusion(*rankings: list[tuple[int, float]]):
    """Combine multiple ranked lists into one fused score per id."""
    fused: dict[int, float] = {}
    for ranking in rankings:
        for rank_index, (obj_id, _score) in enumerate(ranking):
            fused[obj_id] = fused.get(obj_id, 0.0) + 1.0 / (RRF_K + rank_index + 1)
    return sorted(fused.items(), key=lambda kv: kv[1], reverse=True)


@router.post("", response_model=list[schemas.ObjetSearchResult])
def search(req: schemas.SearchRequest, db: Session = Depends(get_db)):
    query = req.query.strip()
    if not query:
        return []

    semantic = _semantic_ranking(db, query, req.limit, req.threshold)
    fulltext = _fulltext_ranking(db, query, req.limit)
    fused = _reciprocal_rank_fusion(semantic, fulltext)[: req.limit]

    if not fused:
        return []

    ids = [obj_id for obj_id, _ in fused]
    score_by_id = dict(fused)
    objets = (
        db.query(models.Objet)
        .options(joinedload(models.Objet.emplacement), joinedload(models.Objet.vin))
        .filter(models.Objet.id.in_(ids))
        .all()
    )
    by_id = {o.id: o for o in objets}

    results: list[schemas.ObjetSearchResult] = []
    for obj_id, score in fused:
        obj = by_id.get(obj_id)
        if not obj:
            continue
        result = schemas.ObjetSearchResult.model_validate(obj)
        result.score = round(score, 4)
        if obj.emplacement and obj.emplacement.zone_id:
            zone = db.get(models.Zone, obj.emplacement.zone_id)
            result.zone_nom = zone.nom if zone else None
        results.append(result)
    return results
