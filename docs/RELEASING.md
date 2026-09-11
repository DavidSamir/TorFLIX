# Releasing TORFILX

The app is distributed as a sideloaded APK attached to a GitHub Release. There is no app store.

## Why the signing key matters

Android identifies an app by its **signing certificate**. To upgrade an installed app in place, the
new APK must be signed with the **same** key as the one already on the device. If the key changes,
the device refuses the update with *"App not installed"* and the user must uninstall first — which
**erases their watch progress and My List**.

Therefore every release must be signed with one persistent keystore that never changes.

- **Local builds** fall back to the debug key (fine for your own sideloading).
- **Tagged CI releases** (`v*`) must use the release keystore. CI **fails the release** if the
  keystore secrets are absent (see `.github/workflows/release.yml`), so a non-upgradeable APK can
  never be published by accident.

## One-time keystore setup

### 1. Create the keystore (do this once, keep it forever, back it up)

```bash
keytool -genkeypair -v \
  -keystore torfilx-release.jks \
  -alias torfilx \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<STORE_PASSWORD>' -keypass '<KEY_PASSWORD>' \
  -dname "CN=TORFILX, O=TORFILX, C=US"
```

> Keep `torfilx-release.jks` and both passwords somewhere safe and backed up. **If you lose this
> keystore, you can never ship an in-place update again** — every existing install would have to be
> uninstalled and reinstalled. Do **not** commit the keystore to the repository.

### 2. Add the four GitHub repository secrets

Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Value |
| --- | --- |
| `TORFILX_KEYSTORE_BASE64` | `base64 -w0 torfilx-release.jks` (the whole file, base64-encoded) |
| `TORFILX_KEYSTORE_PASSWORD` | the store password from step 1 |
| `TORFILX_KEY_ALIAS` | `torfilx` |
| `TORFILX_KEY_PASSWORD` | the key password from step 1 |

To produce the base64 value:

```bash
base64 -w0 torfilx-release.jks    # Linux
base64 -i torfilx-release.jks      # macOS
```

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push:
   ```bash
   git tag v0.1.9
   git push origin v0.1.9
   ```
3. CI builds, verifies the keystore is present, signs, and attaches `torfilx-release.apk` to the
   GitHub Release. The stable download URL never changes:
   `https://github.com/<owner>/<repo>/releases/latest/download/torfilx-release.apk`
4. The CI log prints the signing certificate fingerprint ("Record the signing certificate" step).
   It must be identical across releases — if it ever changes, in-place upgrades will break.

## Catalogue releases are separate from APK releases

The film catalogue is published over the peer network, signed with the catalogue publisher key, and
reaches installed apps without a new APK. The full runbook is in `docs/CATALOGUE_P2P.md`.

Two things tie the two kinds of release together:

- **Refresh the bundled catalogue when cutting an APK.** Build the latest catalogue release with
  `--asset-dir core/data/src/main/assets`, so a new install starts from it. `BundledCatalogueReleaseTest`
  fails if `catalog.json` and `catalog-manifest.json` do not match.
- **The publisher public key lives in the APK** (`productionCataloguePublisherKeys` in
  `core/data/build.gradle.kts`). Changing it is a key rotation; follow the rotation steps in
  `docs/CATALOGUE_P2P.md`, or installed apps stop accepting catalogue updates.

The publisher seed is a secret in the same class as the release keystore: never commit it, keep it
backed up, and never add it to CI.

## Local release build (debug-signed, for your own testing)

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

This uses the debug key, so it will **not** upgrade over a CI-signed install (different key) — you
must uninstall first. That is expected; only CI-signed releases are upgrade-compatible with each
other.
