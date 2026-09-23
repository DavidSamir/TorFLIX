#!/usr/bin/env bash
# Publish a catalogue release through the peer-to-peer DHT network.
#
# This script:
#   1. Reads a catalog.json file from the same directory
#   2. Builds a signed release using the catalog-publisher tool
#   3. Seeds and publishes it to the DHT
#
# Usage: ./publish-catalog.sh <path-to-seed-file> [--hours <h>] [--version <n>] [--min-version-code <code>]
#
# Example:
#   ./publish-catalog.sh ~/my-publisher.seed
#
# The catalog.json must be in the same directory as this script.
# You can also specify a custom path:
#   ./publish-catalog.sh ~/my-publisher.seed --catalog ./path/to/custom-catalog.json

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CATALOG_FILE="${SCRIPT_DIR}/catalog.json"
RELEASE_DIR="${SCRIPT_DIR}/.release"
VERSION=${VERSION:-1}
HOURS=${HOURS:-24}
MIN_VERSION_CODE=${MIN_VERSION_CODE:-}
TRACKER_ARGS=()

# Parse arguments
if [ $# -lt 1 ]; then
  echo "error: --seed file is required"
  echo "Usage: $(basename "$0") <path-to-seed-file> [--catalog <file>] [--version <n>] [--hours <h>] [--min-version-code <code>]"
  exit 2
fi

SEED_FILE="$1"
shift

while [ $# -gt 0 ]; do
  case "$1" in
    --catalog)
      CATALOG_FILE="$2"
      shift 2
      ;;
    --version)
      VERSION="$2"
      shift 2
      ;;
    --hours)
      HOURS="$2"
      shift 2
      ;;
    --min-version-code)
      MIN_VERSION_CODE="$2"
      shift 2
      ;;
    --tracker)
      TRACKER_ARGS+=("--tracker" "$2")
      shift 2
      ;;
    *)
      echo "error: unknown option $1"
      exit 2
      ;;
  esac
done

# Verify inputs
if [ ! -f "$CATALOG_FILE" ]; then
  echo "error: catalog not found at $CATALOG_FILE"
  exit 1
fi

if [ ! -f "$SEED_FILE" ]; then
  echo "error: seed file not found at $SEED_FILE"
  exit 1
fi

# Clean and prepare release directory
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

echo "Building catalogue release..."
echo "  catalog:  $CATALOG_FILE"
echo "  version:  $VERSION"
echo "  seed:     $SEED_FILE"

# Build the release
BUILD_ARGS=(
  "build"
  "--catalog" "$CATALOG_FILE"
  "--version" "$VERSION"
  "--seed" "$SEED_FILE"
  "--out" "$RELEASE_DIR"
)

if [ -n "$MIN_VERSION_CODE" ]; then
  BUILD_ARGS+=("--min-version-code" "$MIN_VERSION_CODE")
fi

if [ ${#TRACKER_ARGS[@]} -gt 0 ]; then
  BUILD_ARGS+=("${TRACKER_ARGS[@]}")
fi

cd "$PROJECT_ROOT"
./gradlew :tools:catalog-publisher:run --args="${BUILD_ARGS[*]}"

echo ""
echo "Publishing to DHT..."
echo "  release: $RELEASE_DIR"
echo "  hours:   $HOURS"

# Publish to DHT (will seed for the specified hours)
PUBLISH_ARGS=(
  "publish"
  "--release" "$RELEASE_DIR"
  "--seed" "$SEED_FILE"
  "--hours" "$HOURS"
)

./gradlew :tools:catalog-publisher:run --args="${PUBLISH_ARGS[*]}"

echo ""
echo "✓ Catalogue published successfully"
