"""Photo upload and serving with cache headers."""
import os
import uuid

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import FileResponse

from ..database import PHOTOS_DIR

router = APIRouter(prefix="/photos", tags=["photos"])

ALLOWED = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


@router.post("")
async def upload_photo(file: UploadFile = File(...)):
    ext = ALLOWED.get(file.content_type or "")
    if ext is None:
        raise HTTPException(400, "Format non supporté (jpeg/png/webp)")
    os.makedirs(PHOTOS_DIR, exist_ok=True)
    name = f"{uuid.uuid4().hex}{ext}"
    path = os.path.join(PHOTOS_DIR, name)
    content = await file.read()
    with open(path, "wb") as fh:
        fh.write(content)
    return {"photo_url": f"/photos/{name}"}


@router.get("/{name}")
def get_photo(name: str):
    # Prevent path traversal.
    safe = os.path.basename(name)
    path = os.path.join(PHOTOS_DIR, safe)
    if not os.path.isfile(path):
        raise HTTPException(404, "Photo introuvable")
    return FileResponse(
        path,
        headers={"Cache-Control": "public, max-age=31536000, immutable"},
    )
