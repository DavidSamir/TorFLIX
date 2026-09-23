# Catalogue Publisher CI/CD Script

This folder contains a simple CI/CD script for publishing catalogue releases to the peer-to-peer DHT network.

## What it does

1. Reads a `catalog.json` file from this directory
2. Builds a signed catalogue release using the publisher seed file
3. Seeds it and publishes a pointer to the DHT
4. Keeps seeding for a configurable duration (default 24 hours)

There is no central server — the catalogue travels over BitTorrent through the DHT, exactly like the titles it references.

## Quick start

### 1. Create your publisher seed (one time only)

If you don't have a seed file yet, generate one:

```bash
cd ../..  # project root
./gradlew :tools:catalog-publisher:run --args="keygen --out ~/my-publisher.seed"
```

Keep this file safe — it signs every release you publish. Back it up.

### 2. Prepare your catalogue

Place your `catalog.json` in this directory, next to the script. The format is the same as `core/data/src/main/assets/catalog.json`.

Each entry must have:
- `id` — unique permanent identifier
- `title` — display name
- `year` — release year
- `image_url`, `backdrop_url` — poster/backdrop URLs
- `magnets` — array with `quality` and `magnet` fields

For shows, use `"type": "show"` with seasons/episodes instead of magnets. See the main README for the full format.

### 3. Publish

**On Linux/macOS:**

```bash
./publish-catalog.sh ~/my-publisher.seed
```

**On Windows:**

```powershell
.\publish-catalog.ps1 -SeedFile C:\my-publisher.seed
```

The script will:
1. Build the release (generates manifest, signature, torrent)
2. Publish it to the DHT
3. Keep seeding for 24 hours (default)

### Custom options

```bash
# Publish for 48 hours
./publish-catalog.sh ~/my-publisher.seed --hours 48

# Use a specific catalogue file
./publish-catalog.sh ~/my-publisher.seed --catalog ./other-catalog.json

# Set version number (default increments automatically)
./publish-catalog.sh ~/my-publisher.seed --version 3

# Require a minimum app version
./publish-catalog.sh ~/my-publisher.seed --min-version-code 15

# Add custom tracker (repeatable)
./publish-catalog.sh ~/my-publisher.seed --tracker "udp://tracker.example.com:1337"
```

PowerShell equivalents:

```powershell
.\publish-catalog.ps1 -SeedFile ~/my-publisher.seed -Hours 48
.\publish-catalog.ps1 -SeedFile ~/my-publisher.seed -Catalog ./other-catalog.json
.\publish-catalog.ps1 -SeedFile ~/my-publisher.seed -Version 3
.\publish-catalog.ps1 -SeedFile ~/my-publisher.seed -MinVersionCode 15
.\publish-catalog.ps1 -SeedFile ~/my-publisher.seed -Tracker @("udp://tracker.example.com:1337")
```

## How it works

The script is a thin wrapper around the `tools:catalog-publisher` gradle task, which:

1. **Builds** — signs the catalogue, creates a manifest, builds a torrent
2. **Publishes** — seeds the torrent, puts a DHT pointer that apps can find

Apps with sharing enabled will:
1. Query the DHT using your publisher key (baked into the APK)
2. Download the torrent using the info hash from the pointer
3. Verify every byte against the signature
4. Swap in the new catalogue while running
5. Help seed it to other devices

See [`docs/CATALOGUE_P2P.md`](../../docs/CATALOGUE_P2P.md) for the full technical details.

## In CI/CD pipelines

For GitHub Actions, Gitlab CI, or similar:

```yaml
# Example: GitHub Actions
- name: Publish catalogue
  run: |
    chmod +x scripts/publish-catalog/publish-catalog.sh
    scripts/publish-catalog/publish-catalog.sh ~/publisher.seed --version ${{ github.run_number }}
  env:
    SEED_FILE: ~/publisher.seed
  # Store the seed file as a repository secret and write it first:
  # echo "${{ secrets.PUBLISHER_SEED }}" > ~/publisher.seed
```

Or with PowerShell (Windows runners):

```yaml
- name: Publish catalogue (Windows)
  run: |
    $seed = "C:\temp\publisher.seed"
    "..." | Out-File $seed
    .\scripts\publish-catalog\publish-catalog.ps1 -SeedFile $seed -Version ${{ github.run_number }}
```

## Troubleshooting

**"catalogue not found"**
- Make sure `catalog.json` is in this directory, or pass `--catalog path/to/file`

**"seed file not found"**
- Generate it with `keygen` first (see Quick start)
- Pass the full path if it's not in your home directory

**"does not verify"**
- The catalogue was built with a different seed file
- Make sure you're using the same seed that signed the release

**"Gradle not found"**
- Make sure you're running from the project root, or adjust paths in the script
- Run from `scripts/publish-catalog/` and the script adjusts for you

**Network issues**
- The script will keep trying to reach the DHT for the specified duration
- It uses hole punching to reach peers behind routers
- Private networks: use `--private` flag (requires manual DHT bootstrapping)

## Example workflow

```bash
# 1. Generate seed (first time)
./gradlew :tools:catalog-publisher:run --args="keygen --out ~/torfilx.seed"

# 2. Create your catalogue file
cat > scripts/publish-catalog/catalog.json << 'EOF'
[
  {
    "id": "my-film-2025",
    "title": "My Film",
    "year": "2025",
    "image_url": "https://example.com/poster.jpg",
    "genres": ["Drama"],
    "runtimeMinutes": 120,
    "magnets": [
      {
        "quality": "720p",
        "magnet": "magnet:?xt=urn:btih:..."
      }
    ]
  }
]
EOF

# 3. Publish
scripts/publish-catalog/publish-catalog.sh ~/torfilx.seed --hours 48
```

## Requirements

- JDK 17 or later (Android Studio's bundled JBR works)
- Gradle (uses ./gradlew from project root)
- The project's `tools:catalog-publisher` module built

## See also

- [`docs/CATALOGUE_P2P.md`](../../docs/CATALOGUE_P2P.md) — catalogue format, trust rules, publishing protocol
- [`tools/catalog-publisher`](../../tools/catalog-publisher/) — the underlying tool
- [`catalogs/`](../../catalogs/) — example catalogue files in the repository
