# Publishing a new Android APK

The HomeStock Android app polls `GET /app/version` at startup and offers
the user an in-app update when its own `versionCode` is lower than the one
declared here. Drop two files in this directory to publish a new version:

1. **`homestock-latest.apk`** — the signed APK to install. It MUST be
   signed with the same keystore as the version already on the devices,
   otherwise Android refuses to install it (this is the main security
   barrier — do not bypass).
2. **`version.json`** — release metadata:

   ```json
   {
     "version_code": 2,
     "version_name": "1.0.1",
     "notes": "Adds in-app update + safe zone deletion"
   }
   ```

   `sha256` and `size_bytes` are recomputed on the fly by the server if
   they are missing, so you can leave them out and they will be filled in.

## Workflow

```bash
# On your build machine
cd homestock-android
./gradlew :app:assembleRelease   # or :app:assembleDebug while iterating

# Copy the freshly built APK + a version.json onto the NAS, e.g.:
scp app/build/outputs/apk/release/app-release.apk \
    user@nas:/path/to/homestock-backend/apk/homestock-latest.apk
ssh user@nas "cat > /path/to/homestock-backend/apk/version.json" <<'JSON'
{
  "version_code": 2,
  "version_name": "1.0.1",
  "notes": "What changed in this build"
}
JSON
```

The container picks up the new files immediately (this directory is a
bind mount, no restart needed).

## Security notes

* APKs are **never** committed to git (see `.gitignore`).
* Always bump `versionCode` (an integer) — the client compares integers.
* `versionName` is for humans; the version_code drives the update logic.
* The Android client also verifies the SHA-256 of the downloaded file
  against the one returned by `/app/version` before launching the
  installer, so a corrupted download or a swap mid-transfer is caught.
* The Android-level signature check is what stops an attacker dropping a
  malicious APK in this folder — keep your release keystore safe.
