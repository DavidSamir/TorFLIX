#!/usr/bin/env bash
# Merge the catalogue folders into one catalogue and publish it, signed, over the peer-to-peer DHT.
#
# Just run it:   ./publish-catalog.sh
#
# Folders next to this script:
#   add/        Every catalogue file to publish: .json, .json.gz, .jsonl or .ndjson, any number of
#               them, in sub-folders too. When a title is in several files, the newest file wins.
#   remove/     What to take out: ids ("catalog-inception-2010", or an episode's id), or objects with
#               a "title" (and a "year"), such as a title copied from a catalogue file.
#   published/  One folder per release pushed, "release-4 (2026-09-24 10-15)": the catalog.json the
#               televisions receive, the merge report, the signed release, and published.txt saying
#               when it was pushed.
#   output/     Written by every run: catalog.json, merge-report.txt, merge-summary.properties.
#   .release/   Every release built, torfilx-catalogue-<n>/ and its .torrent, and versions-used.txt,
#               the record of every release number used. Never edited.
#
# Each step stops everything when it fails; nothing is published unless every check passed:
#   1. Find Java 17 or later: JAVA_HOME, else Android Studio's bundled JBR, else java on the PATH.
#   2. Install the catalogue publisher (Gradle installDist; quick when nothing changed).
#   3. Find the publisher seed and check it signs with a key the app trusts.
#   4. Merge add/ and remove/ into output/catalog.json, checking every title.
#   5. Build and sign the release in a staging folder, verify it with the app's key and version,
#      check it holds exactly the merged catalogue, then move it into .release/.
#   6. Stop any earlier publisher of this catalogue, file the release in published/, and publish it.
#
# Everything is settled by itself:
#   seed        --seed, else $TORFILX_PUBLISHER_SEED, else the seed in ~/.torfilx/ the app trusts
#               (catalogue-publisher.seed first)
#   version     one higher than every release number used (.release/, dist/catalogue/ and
#               .release/versions-used.txt). When nothing changed since the latest release, it is
#               published again as it is.
#   app build   the last release's minimum, and at least 17 when there are shows
#
# Options, all optional:
#   --seed <file>               the publisher seed (or give it as the first argument)
#   --version <n>               release number
#   --min-version-code <n>      oldest app build that may install it
#   --hours <h>                 how long to publish and seed (default 24)
#   --listen <interfaces>       libtorrent listen interfaces for publishing, e.g. 0.0.0.0:6881
#   --tracker <url>             an extra tracker for the release torrent (repeatable)
#   --strip-trackers            keep two trackers per magnet (when the catalogue is near its size limit)
#   --order mtime|name          how the newest file is decided (default mtime: modification time).
#                               After a git checkout or a copy, times say nothing: name files so they
#                               sort by age (2026-09-24-films.json) and use --order name.
#   --merge-episodes            a show in several files gets every episode any of them has, the newest
#                               copy of each (default: the newest copy of the show is used whole)
#   --strict                    any warning fails the run
#   --allow-vanished            allow titles of the last release to disappear without being in remove/
#   --max-vanished-percent <p>  how many may disappear before the run stops (default 5)
#   --add <folder>              use another add folder (it must exist)
#   --remove <folder>           use another remove folder (it must exist)
#   --keys <hex,...>            public keys the release must verify with (default: the app's)
#   --dry-run                   merge and check only; no seed needed, nothing built or published
#   --no-publish                build and verify, but do not publish
#   -h, --help                  show this help
#
# Environment: TORFILX_PUBLISHER_SEED, and VERSION, HOURS and MIN_VERSION_CODE for the options of the
# same name; JAVA_OPTS is passed to Java (for example JAVA_OPTS=-Xmx4g for very large folders).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT_NAME="$(basename "$0")"

ADD_DIR="$SCRIPT_DIR/add"
REMOVE_DIR="$SCRIPT_DIR/remove"
OUTPUT_DIR="$SCRIPT_DIR/output"
PUBLISHED_DIR="$SCRIPT_DIR/published"
RELEASE_DIR="$SCRIPT_DIR/.release"
USED_VERSIONS="$RELEASE_DIR/versions-used.txt"
DIST_RELEASE_DIR="$PROJECT_ROOT/dist/catalogue"
LOCK_DIR="$SCRIPT_DIR/.publish.lock"
SEED_HOME="$HOME/.torfilx"
TOOL_LIB="$PROJECT_ROOT/tools/catalog-publisher/build/install/catalog-publisher/lib"
MAIN_CLASS="com.torfilx.tools.catalog.CliKt"

SEED_FILE=""
VERSION="${VERSION:-}"
MIN_VERSION_CODE="${MIN_VERSION_CODE:-}"
HOURS="${HOURS:-24}"
LISTEN=""
KEYS=""
ORDER=""
MAX_VANISHED=""
DRY_RUN=0
NO_PUBLISH=0
ADD_GIVEN=0
REMOVE_GIVEN=0
TRACKER_ARGS=()
MERGE_FLAGS=()

say() { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die() {
  printf '\nerror: %s\n' "$1" >&2
  exit "${2:-1}"
}
usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

# Stops when an option that takes a value has none.
need_value() {
  if [ -z "${2:-}" ] || [ "${2#--}" != "$2" ]; then
    die "$1 needs a value (see --help)" 2
  fi
}

# --- arguments -------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --seed|--version|--min-version-code|--hours|--listen|--tracker|--order|--max-vanished-percent|--add|--remove|--keys)
      need_value "$1" "${2:-}"
      case "$1" in
        --seed) SEED_FILE="$2" ;;
        --version) VERSION="$2" ;;
        --min-version-code) MIN_VERSION_CODE="$2" ;;
        --hours) HOURS="$2" ;;
        --listen) LISTEN="$2" ;;
        --tracker) TRACKER_ARGS+=("--tracker" "$2") ;;
        --order) ORDER="$2" ;;
        --max-vanished-percent) MAX_VANISHED="$2" ;;
        --add) ADD_DIR="$2"; ADD_GIVEN=1 ;;
        --remove) REMOVE_DIR="$2"; REMOVE_GIVEN=1 ;;
        --keys) KEYS="$2" ;;
      esac
      shift 2
      ;;
    --strip-trackers|--merge-episodes|--strict|--allow-vanished) MERGE_FLAGS+=("$1"); shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-publish) NO_PUBLISH=1; shift ;;
    --catalog) die "--catalog is gone: put catalogue files in $ADD_DIR (any number of them); the newest copy of each title wins" 2 ;;
    --*) die "unknown option $1 (see --help)" 2 ;;
    *)
      [ -z "$SEED_FILE" ] || die "unexpected argument \"$1\": the seed file is already $SEED_FILE" 2
      SEED_FILE="$1"
      shift
      ;;
  esac
done

is_whole() { case "$1" in ''|*[!0-9]*) return 1 ;; *) [ "$1" -gt 0 ] 2>/dev/null ;; esac; }
[ -z "$VERSION" ] || is_whole "$VERSION" || die "--version must be a whole number of 1 or more, got \"$VERSION\"" 2
[ -z "$MIN_VERSION_CODE" ] || is_whole "$MIN_VERSION_CODE" || die "--min-version-code must be a whole number of 1 or more, got \"$MIN_VERSION_CODE\"" 2
printf '%s' "$HOURS" | grep -Eq '^[0-9]+([.][0-9]+)?$' && awk "BEGIN { exit !($HOURS > 0) }" ||
  die "--hours must be a positive number, got \"$HOURS\"" 2
case "$ORDER" in ''|mtime|name) ;; *) die "--order must be mtime or name, got \"$ORDER\"" 2 ;; esac
[ -z "$MAX_VANISHED" ] || printf '%s' "$MAX_VANISHED" | grep -Eq '^[0-9]+([.][0-9]+)?$' || die "--max-vanished-percent must be a number from 0 to 100" 2

# A seed named on the command line or in TORFILX_PUBLISHER_SEED must be there; otherwise it is found
# in ~/.torfilx/ once the publisher can read seeds (step 3).
[ -n "$SEED_FILE" ] || SEED_FILE="${TORFILX_PUBLISHER_SEED:-}"
if [ "$DRY_RUN" -eq 0 ] && [ -n "$SEED_FILE" ]; then
  [ -f "$SEED_FILE" ] || die "no seed file at $SEED_FILE"
  [ -r "$SEED_FILE" ] || die "the seed file $SEED_FILE cannot be read"
  [ -s "$SEED_FILE" ] || die "the seed file $SEED_FILE is empty"
  SEED_FILE="$(cd "$(dirname "$SEED_FILE")" && pwd)/$(basename "$SEED_FILE")"
fi

# Paths as the Java tool needs them: Windows form under Git Bash or Cygwin, unchanged elsewhere.
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# --- one run at a time -----------------------------------------------------------------------

# Two runs at once would overwrite each other's output and staging folder. The lock is held until
# publishing starts; a later run then replaces the publisher itself (step 6). A lock left by a run
# that died is taken over.
take_lock() {
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf 'script=sh\npid=%s\nstarted=%s\n' "$$" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$LOCK_DIR/owner"
    return
  fi
  local owner_script owner_pid
  owner_script="$(sed -n 's/^script=//p' "$LOCK_DIR/owner" 2>/dev/null | tr -d '\r')"
  owner_pid="$(sed -n 's/^pid=//p' "$LOCK_DIR/owner" 2>/dev/null | tr -d '\r')"
  if [ "$owner_script" = "sh" ] && [ -n "$owner_pid" ] && ! kill -0 "$owner_pid" 2>/dev/null; then
    say "Taking over the lock of an earlier run (process $owner_pid) that is no longer running."
    rm -rf "$LOCK_DIR"
    take_lock
    return
  fi
  die "another run is merging or building (see $LOCK_DIR/owner). Wait for it; if none is running, delete $LOCK_DIR"
}
release_lock() { rm -rf "$LOCK_DIR"; }

take_lock
trap release_lock EXIT
trap 'exit 130' INT TERM

# --- 1. Java ---------------------------------------------------------------------------------

step "1/6 Java"
java_in() { [ -n "$1" ] && { [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ]; }; }
if ! java_in "${JAVA_HOME:-}"; then
  [ -z "${JAVA_HOME:-}" ] || say "JAVA_HOME ($JAVA_HOME) holds no Java; looking elsewhere."
  unset JAVA_HOME
  for candidate in \
    "${PROGRAMFILES:+$(native "$PROGRAMFILES")/Android/Android Studio/jbr}" \
    "${LOCALAPPDATA:+$(native "$LOCALAPPDATA")/Programs/Android Studio/jbr}" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/android-studio/jbr" \
    "/opt/android-studio/jbr" \
    "/usr/local/android-studio/jbr" \
    "/snap/android-studio/current/jbr"; do
    if java_in "$candidate"; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  die "no Java found. Install JDK 17 or later, or set JAVA_HOME (Android Studio's bundled JBR works)"
fi
JAVA_VERSION_LINE="$("$JAVA" -version 2>&1 | head -n 1 | tr -d '\r')"
JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION_LINE" | sed -n 's/.*version "\([0-9][0-9]*\)\.\{0,1\}\([0-9]*\).*/\1 \2/p')"
case "$JAVA_MAJOR" in
  "1 "*) JAVA_MAJOR="${JAVA_MAJOR#1 }" ;;
  *) JAVA_MAJOR="${JAVA_MAJOR%% *}" ;;
esac
is_whole "$JAVA_MAJOR" || die "cannot tell the version of $JAVA (it said: $JAVA_VERSION_LINE)"
[ "$JAVA_MAJOR" -ge 17 ] || die "$JAVA is Java $JAVA_MAJOR; the publisher needs 17 or later (Android Studio's bundled JBR works)"
say "Java $JAVA_MAJOR: $JAVA"

# --- 2. the publisher ------------------------------------------------------------------------

step "2/6 Installing the catalogue publisher"
[ -f "$PROJECT_ROOT/gradlew" ] || die "no gradlew in $PROJECT_ROOT; run this script from its place in the repository"
(cd "$PROJECT_ROOT" && sh ./gradlew --quiet --console=plain :tools:catalog-publisher:installDist) ||
  die "Gradle could not build the publisher (see above). On a machine short of memory, try GRADLE_OPTS=-Xmx1536m"
[ -f "$TOOL_LIB/catalog-publisher.jar" ] || die "the publisher was not installed at $TOOL_LIB"
say "Installed: $TOOL_LIB"

# Runs the publisher. Paths are already in native form, so Git Bash must not convert any argument.
tool() {
  # shellcheck disable=SC2086 # JAVA_OPTS is a list of options.
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$JAVA" ${JAVA_OPTS:-} -cp "$(native "$TOOL_LIB")/*" "$MAIN_CLASS" "$@"
}

# --- 3. the key ------------------------------------------------------------------------------

APP_GRADLE="$PROJECT_ROOT/app/build.gradle.kts"
DATA_GRADLE="$PROJECT_ROOT/core/data/build.gradle.kts"
APP_VERSION_CODE=""
[ -f "$APP_GRADLE" ] && APP_VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$APP_GRADLE" | head -n 1)"
if [ -z "$KEYS" ] && [ -f "$DATA_GRADLE" ]; then
  KEYS="$(sed -n 's/^[[:space:]]*val productionCataloguePublisherKeys[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$DATA_GRADLE" | head -n 1)"
fi
KEYS="$(printf '%s' "$KEYS" | tr -d ' \r' | tr 'A-F' 'a-f')"

# The public key a seed file signs with, or nothing when it is not a seed.
public_key_of() { tool pubkey --seed "$(native "$1")" 2>/dev/null | tr -d '\r' | tail -n 1 | tr 'A-F' 'a-f'; }
is_trusted() { case ",$KEYS," in *",$1,"*) return 0 ;; esac; return 1; }

step "3/6 Publisher key"
if [ "$DRY_RUN" -eq 1 ]; then
  say "Dry run: the seed is not needed and not checked."
else
  [ -n "$KEYS" ] || die "no trusted key found in $DATA_GRADLE (productionCataloguePublisherKeys); pass --keys <hex>"
  if [ -n "$SEED_FILE" ]; then
    PUBLIC_KEY="$(public_key_of "$SEED_FILE")" || PUBLIC_KEY=""
    [ -n "$PUBLIC_KEY" ] || die "$SEED_FILE is not a publisher seed (64 hexadecimal characters)"
    is_trusted "$PUBLIC_KEY" ||
      die "the seed $SEED_FILE signs as $PUBLIC_KEY, which the app does not trust ($KEYS). Every television would refuse the release. Use the right seed, or --keys for a test build"
  else
    UNTRUSTED=""
    for candidate in "$SEED_HOME/catalogue-publisher.seed" "$SEED_HOME"/*.seed; do
      [ -f "$candidate" ] && [ -s "$candidate" ] || continue
      key="$(public_key_of "$candidate")" || key=""
      if [ -n "$key" ] && is_trusted "$key"; then
        SEED_FILE="$candidate"
        PUBLIC_KEY="$key"
        break
      fi
      UNTRUSTED="$UNTRUSTED $candidate"
    done
    if [ -z "$SEED_FILE" ] && [ -n "$UNTRUSTED" ]; then
      die "no seed in $SEED_HOME signs with a key the app trusts ($KEYS); found:$UNTRUSTED"
    fi
    [ -n "$SEED_FILE" ] ||
      die "no publisher seed found. Put it at $SEED_HOME/catalogue-publisher.seed, set TORFILX_PUBLISHER_SEED, or pass --seed <file>"
  fi
  say "Seed: $SEED_FILE"
  say "It signs as $PUBLIC_KEY, a key the app trusts."
  case "$SEED_FILE" in
    "$PROJECT_ROOT"/*) say "warning: the seed is inside the repository. It signs every release: keep it somewhere else." ;;
  esac
fi
[ -n "$APP_VERSION_CODE" ] && say "The app is at build $APP_VERSION_CODE." || say "warning: no versionCode found in $APP_GRADLE; the release is not checked against the app's build."

# --- 4. merge --------------------------------------------------------------------------------

step "4/6 Merging add/ and remove/"
# The default folders are made on first use. A folder named on the command line must exist: a typo in
# --remove must not become an empty folder that silently removes nothing.
[ "$ADD_GIVEN" -eq 1 ] || mkdir -p "$ADD_DIR"
[ "$REMOVE_GIVEN" -eq 1 ] || mkdir -p "$REMOVE_DIR"
mkdir -p "$OUTPUT_DIR" "$RELEASE_DIR"
CATALOG="$OUTPUT_DIR/catalog.json"
REPORT="$OUTPUT_DIR/merge-report.txt"
SUMMARY="$OUTPUT_DIR/merge-summary.properties"
MERGE_ARGS=(
  merge
  --add "$(native "$ADD_DIR")"
  --remove "$(native "$REMOVE_DIR")"
  --out "$(native "$CATALOG")"
  --report "$(native "$REPORT")"
  --summary "$(native "$SUMMARY")"
  --releases "$(native "$RELEASE_DIR")"
  --releases "$(native "$DIST_RELEASE_DIR")"
)
[ -z "$VERSION" ] || MERGE_ARGS+=(--version "$VERSION")
[ -z "$MIN_VERSION_CODE" ] || MERGE_ARGS+=(--min-version-code "$MIN_VERSION_CODE")
[ -z "$APP_VERSION_CODE" ] || MERGE_ARGS+=(--app-version-code "$APP_VERSION_CODE")
[ -z "$ORDER" ] || MERGE_ARGS+=(--order "$ORDER")
[ -z "$MAX_VANISHED" ] || MERGE_ARGS+=(--max-vanished-percent "$MAX_VANISHED")
[ ${#MERGE_FLAGS[@]} -eq 0 ] || MERGE_ARGS+=("${MERGE_FLAGS[@]}")

MERGE_STATUS=0
tool "${MERGE_ARGS[@]}" || MERGE_STATUS=$?
[ "$MERGE_STATUS" -eq 0 ] || die "the merge failed; nothing was built or published. Everything it found is in $REPORT" "$MERGE_STATUS"

summary() { sed -n "s/^$1=//p" "$SUMMARY" | tr -d '\r' | head -n 1; }
[ "$(summary status)" = "ok" ] || die "the merge summary ($SUMMARY) does not say ok; nothing was built or published"
[ -f "$CATALOG" ] || die "the merge reported success but wrote no $CATALOG"
NEW_VERSION="$(summary version)"
NEW_MIN_CODE="$(summary min_version_code)"
REPUBLISH="$(summary republish)"
CATALOG_SHA="$(summary sha256)"
is_whole "$NEW_VERSION" || die "the merge settled no release number; see $REPORT"

if [ "$DRY_RUN" -eq 1 ]; then
  step "Dry run finished"
  say "The merged catalogue is $CATALOG (release $NEW_VERSION${NEW_MIN_CODE:+, for app build $NEW_MIN_CODE or later}); nothing was built or published."
  exit 0
fi

# --- 5. build and verify ---------------------------------------------------------------------

# The sha-256 of a file's unpacked contents, or nothing when no tool for it exists.
unpacked_sha() {
  if command -v sha256sum >/dev/null 2>&1; then
    gzip -dc "$1" | sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    gzip -dc "$1" | shasum -a 256 | cut -d' ' -f1
  fi
}

verify_release() {
  local root="$1"
  local args=(verify --dir "$(native "$root")" --keys "$KEYS")
  [ -z "$APP_VERSION_CODE" ] || args+=(--app-version-code "$APP_VERSION_CODE")
  tool "${args[@]}" || die "release $root does not verify with the app's key${APP_VERSION_CODE:+ and build $APP_VERSION_CODE}; it was not published"
}

# Adds a release number to the record of numbers used, which outlives any release folder.
record_version() {
  if [ ! -f "$USED_VERSIONS" ]; then
    {
      say "# Release numbers already used. A television ignores a release that is not newer than the one it"
      say "# has, so the next release is always higher than every number here, even when a folder is deleted."
    } > "$USED_VERSIONS"
  fi
  printf '%s  %s  %s\n' "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$2" >> "$USED_VERSIONS"
}

if [ -n "$REPUBLISH" ]; then
  step "5/6 Nothing changed since release $NEW_VERSION"
  RELEASE_ROOT="$REPUBLISH"
  [ -d "$RELEASE_ROOT" ] || die "release $NEW_VERSION is not at $RELEASE_ROOT"
  [ -f "$RELEASE_ROOT.torrent" ] || die "release $NEW_VERSION has no torrent at $RELEASE_ROOT.torrent; rebuild it with --version $((NEW_VERSION + 1))"
  verify_release "$RELEASE_ROOT"
  say "Publishing release $NEW_VERSION again, as it is."
else
  step "5/6 Building release $NEW_VERSION"
  STAGING="$RELEASE_DIR/.staging"
  RELEASE_NAME="torfilx-catalogue-$NEW_VERSION"
  RELEASE_ROOT="$RELEASE_DIR/$RELEASE_NAME"
  [ ! -e "$RELEASE_ROOT" ] && [ ! -e "$RELEASE_ROOT.torrent" ] ||
    die "$RELEASE_ROOT already exists; a release never changes. Use a higher --version"
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  BUILD_ARGS=(build --catalog "$(native "$CATALOG")" --version "$NEW_VERSION" --seed "$(native "$SEED_FILE")" --out "$(native "$STAGING")")
  [ -z "$NEW_MIN_CODE" ] || BUILD_ARGS+=(--min-version-code "$NEW_MIN_CODE")
  [ ${#TRACKER_ARGS[@]} -eq 0 ] || BUILD_ARGS+=("${TRACKER_ARGS[@]}")
  tool "${BUILD_ARGS[@]}" || die "building release $NEW_VERSION failed (see above); nothing was published"

  [ -d "$STAGING/$RELEASE_NAME" ] && [ -f "$STAGING/$RELEASE_NAME.torrent" ] || die "the build left no release in $STAGING"
  say ""
  verify_release "$STAGING/$RELEASE_NAME"
  BUILT_SHA="$(unpacked_sha "$STAGING/$RELEASE_NAME/catalog.json.gz")"
  if [ -z "$BUILT_SHA" ]; then
    say "note: no sha256sum or shasum here, so the release's contents were not compared with $CATALOG"
  elif [ "$BUILT_SHA" != "$CATALOG_SHA" ]; then
    die "release $NEW_VERSION does not hold exactly the merged catalogue (sha-256 $BUILT_SHA, merged $CATALOG_SHA); it was not published"
  else
    say "The release holds exactly the merged catalogue (sha-256 $BUILT_SHA)."
  fi

  mv "$STAGING/$RELEASE_NAME" "$RELEASE_ROOT"
  mv "$STAGING/$RELEASE_NAME.torrent" "$RELEASE_ROOT.torrent"
  rm -rf "$STAGING"
  record_version "$NEW_VERSION" "built: $(summary titles) titles, sha-256 $CATALOG_SHA"
  say "Release $NEW_VERSION is in $RELEASE_ROOT"
fi

# --- 6. publish ------------------------------------------------------------------------------

if [ "$NO_PUBLISH" -eq 1 ]; then
  step "Built, not published (--no-publish)"
  say "To publish it, run $SCRIPT_NAME again: nothing changed, so this release is published as it is."
  exit 0
fi

step "6/6 Publishing release $NEW_VERSION for $HOURS hours"

# Only one release may be published at a time: two publishers of the same key take turns overwriting
# the DHT pointer, and televisions may miss the newer release. Rehearsals (--private, --salt) are
# left alone, and so is seed-titles, which serves the titles themselves.
stop_earlier_publishers() {
  if [ "${OS:-}" = "Windows_NT" ] && command -v powershell.exe >/dev/null 2>&1; then
    local script
    script="$(mktemp "${TMPDIR:-/tmp}/stop-publishers-XXXXXX")"
    mv "$script" "$script.ps1"
    script="$script.ps1"
    cat > "$script" <<'PS1'
$pattern = 'com\.torfilx\.tools\.catalog\.CliKt\s+publish\s'
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -match $pattern -and $_.CommandLine -notmatch '\s--(private|salt)(\s|=)' } |
    ForEach-Object {
        $release = if ($_.CommandLine -match '--release[= ]+("[^"]+"|\S+)') { $Matches[1] } else { 'an earlier release' }
        Write-Output "Stopping the earlier publisher of $release (process $($_.ProcessId))"
        Stop-Process -Id $_.ProcessId -Force
    }
PS1
    MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$(native "$script")" | tr -d '\r' ||
      say "warning: could not look for earlier publishers; if one is running, stop it"
    rm -f "$script"
  else
    local pids
    pids="$( (ps -eo pid=,args= 2>/dev/null || true) |
      grep -E 'com\.torfilx\.tools\.catalog\.CliKt[[:space:]]+publish[[:space:]]' |
      grep -vE -- '[[:space:]]--(private|salt)([[:space:]]|=)' | awk '{print $1}' || true)"
    for pid in $pids; do
      say "Stopping the earlier publisher (process $pid)"
      kill "$pid" 2>/dev/null || continue
      for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
    done
  fi
}
stop_earlier_publishers

# Files the release in published/: one folder per release, the first time it is pushed. The lock is
# still held, so output/ is this run's merge.
archive_release() {
  local existing folder local_time
  local_time="$(date '+%Y-%m-%d %H:%M')"
  mkdir -p "$PUBLISHED_DIR"
  existing="$(find "$PUBLISHED_DIR" -mindepth 1 -maxdepth 1 -type d -name "release-$NEW_VERSION (*" 2>/dev/null | head -n 1)"
  if [ -n "$existing" ]; then
    printf 'Published again %s\n' "$local_time" >> "$existing/published.txt"
    say "Filed: $existing (published again)"
    return
  fi
  folder="$PUBLISHED_DIR/release-$NEW_VERSION ($(date '+%Y-%m-%d %H-%M'))"
  mkdir -p "$folder/release"
  cp -p "$CATALOG" "$REPORT" "$folder/"
  cp -Rp "$RELEASE_ROOT" "$folder/release/"
  cp -p "$RELEASE_ROOT.torrent" "$folder/release/"
  {
    say "Release $NEW_VERSION"
    say ""
    say "  published    $local_time (publishing started; it runs for $HOURS hours)"
    say "  titles       $(summary titles): $(summary films) films, $(summary shows) shows with $(summary episodes) episodes"
    say "  app build    ${NEW_MIN_CODE:-any} or later"
    say "  catalog.json sha-256 $CATALOG_SHA"
    say "  signed by    $PUBLIC_KEY"
    say ""
    say "catalog.json      what televisions receive"
    say "merge-report.txt  everything the merge found and did, file by file"
    say "release/          the signed release and its torrent"
    say ""
  } > "$folder/published.txt"
  say "Filed: $folder"
}
archive_release

# From here on a later run may replace this one, so the lock is let go.
release_lock
trap - EXIT

say "Stop with Ctrl+C. The DHT forgets the pointer about 2 hours after publishing stops; run this again"
say "to publish the same release again."
PUBLISH_ARGS=(publish --release "$(native "$RELEASE_ROOT")" --seed "$(native "$SEED_FILE")" --hours "$HOURS")
[ -z "$LISTEN" ] || PUBLISH_ARGS+=(--listen "$LISTEN")
PUBLISH_STATUS=0
tool "${PUBLISH_ARGS[@]}" || PUBLISH_STATUS=$?
if [ "$PUBLISH_STATUS" -ne 0 ]; then
  NEWEST="$(find "$RELEASE_DIR" -mindepth 1 -maxdepth 1 -type d -name 'torfilx-catalogue-*' 2>/dev/null |
    sed -n 's/.*torfilx-catalogue-\([0-9][0-9]*\)$/\1/p' | sort -n | tail -n 1)"
  if [ -n "$NEWEST" ] && [ "$NEWEST" -gt "$NEW_VERSION" ]; then
    say ""
    say "Release $NEW_VERSION is no longer published: release $NEWEST, published by a later run, replaced it."
    exit 0
  fi
  die "publishing release $NEW_VERSION failed (see above). The release is built and verified; run this again to publish it" "$PUBLISH_STATUS"
fi

say ""
say "OK: release $NEW_VERSION was published for $HOURS hours."
