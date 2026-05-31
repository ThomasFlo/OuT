"""Self-update endpoints: serve the latest signed APK and its metadata.

The administrator drops a fresh release into ``homestock-backend/apk/`` with
two files:

* ``homestock-latest.apk`` — the signed APK to install
* ``version.json`` — metadata consumed by the Android client

Sample ``version.json``::

    {
      "version_code": 2,
      "version_name": "1.0.1",
      "sha256": "deadbeef…",
      "size_bytes": 12345678,
      "notes": "Bug fix: …"
    }

The Android app polls ``GET /app/version`` at start-up; if its own
``BuildConfig.VERSION_CODE`` is lower, it prompts the user to download
``GET /app/download``, verifies the SHA-256, and launches the system
PackageInstaller. Signature checks (same keystore) are enforced by
Android natively, so a tampered file on the NAS cannot replace the app.
"""
import hashlib
import json
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

router = APIRouter(prefix="/app", tags=["app-update"])

# Both files live in homestock-backend/apk/ at the repo root. The Dockerfile
# is expected to mount or copy that directory into /apk inside the container.
APK_DIR = Path(__file__).resolve().parent.parent.parent / "apk"
APK_FILE = APK_DIR / "homestock-latest.apk"
VERSION_FILE = APK_DIR / "version.json"


class AppVersionInfo(BaseModel):
    version_code: int
    version_name: str
    sha256: str | None = None
    size_bytes: int | None = None
    notes: str | None = None
    available: bool = True


def _compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


@router.get("/version", response_model=AppVersionInfo)
def app_version() -> AppVersionInfo:
    """Return metadata about the latest published APK.

    Returns ``available=False`` (with version_code=0) when no APK has been
    published yet, so the client can silently skip the update check.
    """
    if not APK_FILE.exists() or not VERSION_FILE.exists():
        return AppVersionInfo(version_code=0, version_name="0.0", available=False)
    try:
        meta = json.loads(VERSION_FILE.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise HTTPException(500, f"Métadonnées APK illisibles : {exc}")

    # Recompute size and hash on demand if missing, so the administrator does
    # not have to update version.json each time they drop a new binary.
    sha = meta.get("sha256") or _compute_sha256(APK_FILE)
    size = meta.get("size_bytes") or APK_FILE.stat().st_size
    return AppVersionInfo(
        version_code=int(meta["version_code"]),
        version_name=str(meta["version_name"]),
        sha256=sha,
        size_bytes=size,
        notes=meta.get("notes"),
        available=True,
    )


@router.get("/download")
def app_download() -> FileResponse:
    """Stream the latest APK back to the client."""
    if not APK_FILE.exists():
        raise HTTPException(404, "Aucune APK publiée")
    return FileResponse(
        path=APK_FILE,
        media_type="application/vnd.android.package-archive",
        filename="homestock-latest.apk",
    )
