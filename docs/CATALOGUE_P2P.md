# The peer-to-peer catalogue

TORFILX used to ship its whole library as `catalog.json` inside the APK, so a new or corrected title
meant a new APK and a sideload on every television. The catalogue now travels the way the films do.
Its maintainer signs a release, publishes a pointer to it in the BitTorrent DHT, and seeds it as a
small torrent. Every television that has sharing on finds the pointer, downloads the release, checks
it, swaps it in while the app runs, and then helps seed it.

There is still no server. The copy inside the APK remains, as the catalogue a new install starts with
and the one the app falls back to whenever a downloaded release cannot be used.

---

## How it works

```
 maintainer's PC (tools/catalog-publisher)                  television (the app)
 ------------------------------------------                 --------------------
 keygen   seed file, kept outside the repo                  sharing on -> torrent session starts
          public key  ->  baked into the APK                     |
                                                           20 s later, then every 6 h
 build    catalog.json.gz + manifest.json + manifest.sig         |
          + release torrent (16 KiB pieces)                DHT get(publisher key, salt)
                                                                 |  -> pointer {cv, ih}
 publish  seed the torrent                                 cv newer than the catalogue in use?
          DHT put(pointer), again every 30 min                   |  yes
                                                           download torrent ih -> verify every byte
                                                                 |  ok
                                                           install atomically, swap in, keep seeding
```

## What makes a release trustworthy

A release is accepted only when every link in this chain holds, checked in this order:

1. **The DHT pointer** was signed by a publisher key this build trusts. libtorrent checks this
   signature itself (BEP 44) before the app ever sees the value. The pointer only says where to look;
   nothing else is taken from it.
2. **`manifest.sig`** is a valid Ed25519 signature, by a trusted key, over the exact bytes of
   `manifest.json`. Nothing else in the release is read until this passes.
3. **The manifest** uses a schema this build understands, does not require a newer app, and stays
   within the size limits.
4. **`catalog.json.gz`** is exactly the size the manifest states and hashes to its SHA-256.
5. **Decompression** is bounded by the signed size and must produce exactly that many bytes.
6. **The catalogue** declares exactly `titleCount` titles, all of them decode, every title is present,
   and every id is explicit, valid and unique — every episode's id included. Every show has
   numbered seasons and episodes, and, when the manifest states `episodeCount`, exactly that many
   episodes.
7. **The pointer and the manifest** name the same catalogue version.
8. **Every entry maps to a title** in the app. A release the app cannot fully use is refused before
   anything is installed.

A downloaded release is verified again in full every time the app starts. That catches files damaged
on disk, and it means a build that stops trusting a key also stops using what that key signed.

Signatures are standard RFC 8032 Ed25519. The app verifies with a pure-Java implementation
(`net.i2p.crypto:eddsa`), so verification never depends on the native library. Tests prove that
libtorrent's native Ed25519, which signs the DHT pointer, agrees with it byte for byte.

---

## The release format

### Files

A release is one directory, which is also the torrent:

```
torfilx-catalogue-<version>/
  catalog.json.gz   the catalogue: the same JSON array the APK ships, gzip-compressed
  manifest.json     what the release claims about itself (signed)
  manifest.sig      Ed25519 signature over manifest.json, as 128 hex characters
```

A torrent holding anything else, or named anything else, is refused before a byte of it is
downloaded.

### `manifest.json`

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Release format; currently `1`. An app refuses a schema it does not know. |
| `catalogVersion` | Monotonic release number. Higher replaces lower; a tie keeps what is installed. |
| `publishedAtMs` | When the release was built, epoch milliseconds. |
| `titleCount` | Entries in the catalogue; must equal the `"title"` keys in the raw bytes. |
| `sha256` | SHA-256 of `catalog.json.gz`, lower-case hex. |
| `gzBytes` | Size of `catalog.json.gz`. |
| `jsonBytes` | Size of the decompressed `catalog.json`. |
| `minVersionCode` | Optional. The oldest app build (versionCode) that may install this release. |
| `episodeCount` | Optional. Episodes across every show; written only when the catalogue has shows, so a films-only release is byte-identical to one from before shows existed. Checked against the content when present. Older apps ignore it. |

### Catalogue entries

Unchanged from before, plus `id`:

```json
{
  "id": "catalog-the-kid-1921",
  "title": "The Kid",
  "year": "1921",
  "image_url": "https://…/poster.jpg",
  "genres": ["Comedy"],
  "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }]
}
```

`id` is the key watch progress and My List are stored under. The publisher pins it the first time a
title is published, using exactly the id the app was already deriving from title and year. After
that it never changes, so renaming a film or correcting its year cannot orphan anyone's progress.
A hand-edited file without ids still loads in the app; only a published release must carry them.

### Shows

A show is an entry with `"type": "show"` and `seasons` in place of `magnets`:

```json
{
  "id": "show-the-twilight-zone-1959",
  "type": "show",
  "title": "The Twilight Zone",
  "year": "1959",
  "image_url": "https://…/poster.jpg",
  "backdrop_url": "https://…/backdrop.jpg",
  "genres": ["Sci-Fi"],
  "seasons": [
    {
      "number": 1,
      "name": "Season 1",
      "episodes": [
        {
          "id": "show-the-twilight-zone-1959-s01e01",
          "number": 1,
          "name": "Where Is Everybody?",
          "overview": "…",
          "runtimeMinutes": 25,
          "airDate": "1959-10-02",
          "image_url": "https://…/s01e01.jpg",
          "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }]
        }
      ]
    }
  ]
}
```

| Rule | Why |
| --- | --- |
| `type` is `movie` (or absent) or `show`; anything else is refused | An app skips a type it does not know, and a release it cannot fully use is refused |
| An episode's name is `"name"`, **never** `"title"` | The release counts `"title"` keys and requires one per top-level entry; a `"title"` in an episode breaks that count, and the publisher says so |
| A show has at least one season, each with at least one episode, and no magnets of its own | Its episodes are what play |
| Season numbers are 0 or more and unique; `0` is Specials | Specials are listed last and never chained into by next-up or autoplay |
| Episode numbers are 1 or more and unique within a season | They order the list and the autoplay |
| Every episode has an id, unique across the whole catalogue, films included | It is the key the viewer's progress on that episode is stored under |
| At most 2 000 episodes per show | A sanity cap, not a design limit |

Ids are derived exactly like a film's, with their own prefixes: a show is `show-<slug>-<year>`, an
episode `<show id>-s<SS>e<EE>`. `build` and `pin` walk films, shows, seasons and episodes in file order
with one set of used ids, exactly as the app's parser does, so the ids pinned are the ids the app was
already deriving. After pinning an id is opaque: renaming a show, correcting its year or renumbering an
episode keeps every id, and so every viewer's progress.

`seasons[].packs` holds whole-season torrents. Each pack becomes one more source for every episode of
its season, and plays exactly that episode's file: matched by `S01E03`, `1x03` or, in a single-season
pack, `E03` in the file names, or by position only when no file carries any episode code and their
number equals the season's episodes. A pack that does not hold the episode plays nothing for it rather
than some other episode. At the same quality an episode's own torrent is preferred to a pack. Builds
before season-pack support ignore `packs`.

### Size

The decompressed catalogue is capped at 12 MB. Episodes add up quickly, and most of a catalogue's
bytes are tracker URLs repeated in every magnet. `build` prints the size against the cap on every run,
warns past two thirds of it, and `build --strip-trackers` keeps two trackers per magnet (preferring the
ones the app adds itself). Stripping never changes an id.

### The DHT pointer

A BEP 44 mutable item under the publisher's public key, with the salt `torfilx-catalog-v1`:

```
d 2:cv i<catalogVersion>e  2:ih 40:<info hash, hex>  1:v i1e e
```

About 65 bytes against the DHT's 1000-byte limit. The release number travels inside the value
because the DHT's own sequence number is chosen by libtorrent, which adds one to the highest it finds.

### Limits

| Limit | Value | Why |
| --- | --- | --- |
| Compressed catalogue | 4 MB | Today's is a few hundred KB |
| Decompressed catalogue | 12 MB | Held as bytes, string and objects on a ~128 MB heap |
| Titles | 50,000 | |
| Manifest | 16 KB | |

Anything over a limit is a clean rejection rather than an out-of-memory crash on a Fire TV Stick.

---

## What the app does

### When it checks

A check needs all of these, and none of them is ever switched on by the updater itself:

| Condition | Why |
| --- | --- |
| The build trusts a publisher key | `productionCataloguePublisherKeys` in `core/data/build.gradle.kts` |
| Sharing consent is on | No torrent session, and no DHT node, runs before the viewer agrees |
| "Find peers with DHT" is on | The pointer lives in the DHT |
| The torrent session is running | The updater follows the session the engine runs; it never starts one on its own schedule |
| "Update the catalogue over the peer network" is on | Default on; off also stops seeding the catalogue |

"Check for a new catalogue" in Settings may start the session, but only with sharing consent.

### Schedule

| After | Next check |
| --- | --- |
| The session comes up | 20 s later, or 1 h after the last successful check if that is later |
| Up to date, or updated | 6 h |
| A release refused | 6 h |
| Nothing published | 1 h |
| A precondition is missing | 1 h |
| A network failure | 15 min, doubling up to 6 h |

A lookup waits up to 45 s, including up to 22 s for DHT nodes. A download may take 3 minutes.

### Installing a release

1. The release downloads straight into `filesDir/catalogue/releases/<version>/`.
2. It is verified there, mapped into titles, and checked complete.
3. `filesDir/catalogue/current.json` is rewritten atomically to name it.
4. The catalogue is swapped in memory; Home, Movies, My List, Search, Details and Continue Watching
   all update without a restart.
5. The previous release stops seeding and its files are deleted.
6. The new release keeps seeding from where it downloaded.

A crash at any point leaves either the old catalogue or the new one in use, never a mixture.

### When it goes back to the bundled catalogue

- The downloaded release fails verification at start-up (damaged, key no longer trusted, needs a
  newer app). The download is deleted.
- An app update ships a bundled catalogue at least as new as the download. The download is deleted.
- The viewer chooses "Use the built-in catalogue". That release is remembered as refused, so it is not
  installed again; a newer release still is.

### Refused releases

A release that fails verification is deleted and remembered, so it is not downloaded again every six
hours. The memory is tied to the app build: an app update tries it again, which matters for releases
that needed a newer app.

### Seeding

While the session runs and updates are on, the installed release is seeded, so other televisions can
get it from this one. It is a few hundred kilobytes, counts against no storage budget, does not appear
in the sharing statistics or the contribution record, and shares the session's upload cap. It is
never removed by "Clear downloaded data", which is about films.

### On disk

```
filesDir/catalogue/current.json                         which release is installed
filesDir/catalogue/releases/<v>/torfilx-catalogue-<v>/  the release files
filesDir/catalogue/releases/<v>/catalogue.torrent       for seeding it again
filesDir/torrent-session/dht.state                      the DHT routing table, saved every 10 min and on stop
```

All of it is outside `filesDir/torrents`, which the engine empties on every start.

### Privacy

Nothing reaches the network before sharing consent; that rule is enforced by the torrent session,
which the updater only follows. A DHT lookup reveals the viewer's IP address to the DHT nodes asked,
exactly as the film lookups that already happen do. Seeding the catalogue reveals it to the peers
fetching the catalogue. Turning off "Update the catalogue over the peer network" stops both.

---

## Publishing a release

Run the tool from the repository root:

```bash
./gradlew :tools:catalog-publisher:run --args="help"
```

or build a standalone copy once and run that:

```bash
./gradlew :tools:catalog-publisher:installDist
tools/catalog-publisher/build/install/catalog-publisher/bin/catalog-publisher help
```

### Once: the publisher key

```bash
./gradlew :tools:catalog-publisher:run --args="keygen --out C:/Users/<you>/.torfilx/catalogue-publisher.seed"
```

The tool refuses to write the seed inside the repository. It prints the public key. Put that key in
`productionCataloguePublisherKeys` in `core/data/build.gradle.kts` and ship an APK with it.

**Back the seed up like the release keystore.** Anyone holding it can publish a catalogue every
TORFILX build trusts. Losing it means no further catalogue updates reach existing installs until an
APK that trusts a new key has been installed.

### Every release

1. Edit `core/data/src/main/assets/catalog.json` (or any copy of it).
2. Build release *N*, one higher than the last:

   ```bash
   ./gradlew :tools:catalog-publisher:run --args="build --catalog core/data/src/main/assets/catalog.json \
     --version N --seed <seed file> --out dist/catalogue --asset-dir core/data/src/main/assets"
   ```

   This pins ids for new titles and episodes, signs the release into
   `dist/catalogue/torfilx-catalogue-N/`, writes its torrent beside it, verifies the result, and with
   `--asset-dir` rewrites the bundled `catalog.json` and `catalog-manifest.json` so the next APK
   carries release *N*.

   **The first release that contains a show** must also pass `--min-version-code <V>`, where `V` is the
   `versionCode` of the first app build that understands shows. Older builds then refuse the release
   cleanly ("A newer catalogue needs a newer version of the app") and keep the catalogue they have,
   instead of showing each show as a film that cannot play. They try it again after an update.

   If `build` warns that the catalogue is near the size limit, add `--strip-trackers`.
3. Commit the updated assets. `BundledCatalogueReleaseTest` fails if the manifest no longer matches
   the catalogue, or if an id has been lost.
4. Publish it:

   ```bash
   ./gradlew :tools:catalog-publisher:run --args="publish --release dist/catalogue/torfilx-catalogue-N \
     --seed <seed file> --hours 48"
   ```

   It seeds the release, puts the pointer in the DHT, checks the DHT returns it, and puts it again
   every 30 minutes for as long as it runs.
5. Check it from anywhere, exactly as a television would:

   ```bash
   ./gradlew :tools:catalog-publisher:run --args="fetch --keys <public key hex> --out build/fetch-check"
   ```

A release is immutable: `build` refuses to write over an existing version.

### Keeping it available

DHT nodes forget an item about two hours after it was last put. While nobody re-puts the pointer,
televisions cannot learn about a newer release; they keep the catalogue they have. Keep `publish`
running for a day or two after each release, and run it again from time to time. Televisions with
sharing on seed the release too, but only the key holder can refresh the pointer.

### Rolling back

Releases only move forward. To undo release *N*, publish release *N+1* containing the older
catalogue.

### Rotating or replacing the key

1. Generate the new key and add it to `productionCataloguePublisherKeys` next to the old one
   (comma-separated). Ship that APK and give it time to spread.
2. Publish under the new key.
3. In a later APK, remove the old key.

If a key leaks, remove it from the next APK at once. Installs that update will refuse, and delete,
anything signed only by it.

---

## Rehearsing a release privately

The same commands run on a closed network: no public routers, trackers, UPnP or local discovery.
Use a salt of your own so nothing collides with the real feed. In three terminals:

```bash
# 1. A DHT node to anchor the network. Note its DHT (UDP) port, D.
catalog-publisher dht-node --private --listen 127.0.0.1:0 --minutes 30

# 2. The publisher. Note its peer (TCP) port, P.
catalog-publisher publish --private --listen 127.0.0.1:0 --dht-node 127.0.0.1:D \
  --release dist/catalogue/torfilx-catalogue-N --seed <seed file> --salt rehearsal --hours 0.25

# 3. A client doing what a television does.
catalog-publisher fetch --private --listen 127.0.0.1:0 --dht-node 127.0.0.1:D --peer 127.0.0.1:P \
  --keys <public key hex> --salt rehearsal --out build/rehearsal
```

The peer and DHT ports are printed separately because they can differ: asked for port 0, libtorrent
1.2 binds TCP and UDP separately. A DHT node must be addressed on its UDP port.

`--private` also lifts libtorrent's per-address DHT rate limit. Every node of a loopback network shares
one address, and the default limit (a handful of packets a second, then five minutes of silence) would
otherwise cut the rehearsal network off after a few lookups. Sessions on the public DHT keep the default.

If a command ends with `warning: libtorrent did not shut down within 15 s; exiting anyway`, that is the
known shutdown hang (see Known limits). The command had already finished its work.

A debug build can trust a test publisher instead of the production key:

```bash
./gradlew :app:assembleDebug -Ptorfilx.cataloguePublisherKeys=<test public key hex>
```

---

## Troubleshooting on a television

Export the log from Settings, or read it live:

```bash
adb logcat -d | grep -E "Torfilx/(Catalog|CatalogUpdate|CatalogStore|CatalogSwarm|Torrent)"
```

| Log line | Meaning |
| --- | --- |
| `Catalog: Using the downloaded catalogue N: … titles, verified in … ms` | A downloaded release is in use |
| `Catalog: Catalogue parsed in full: N titles` | The bundled catalogue is in use |
| `Torrent: DHT state restored from the last session` | The DHT rejoined from saved nodes |
| `CatalogUpdate: Checking for a catalogue newer than N (…, M DHT nodes)` | A check started |
| `CatalogSwarm: DHT lookup for key …: catalogue N at <hash> (seq …, authoritative=true)` | The pointer was found |
| `CatalogSwarm: Catalogue torrent <hash> complete: … bytes` | The release downloaded |
| `Catalog: Catalogue N is now in use: … titles (generation G)` | The release was swapped in |
| `CatalogUpdate: REJECTED catalogue N (REASON): …` | Verification refused it; see the reason |
| `Torrent: libtorrent did not finish shutting down within 20 s; it continues in the background` | The known shutdown hang; the next start waits for it |
| `Torrent: The previous torrent session is still shutting down; not starting a new one yet` | A start refused until the hung shutdown ends; retrying later works |

Settings states the outcome of the last check in words. The reasons behind them:

| Settings says | Cause | What to do |
| --- | --- | --- |
| Turn on sharing to update the catalogue | No consent | Turn on sharing |
| Turn on "Find peers with DHT" | DHT off | Turn it on |
| Could not reach the peer network | No DHT nodes, or the lookup timed out | Network blocks UDP; try another network |
| No catalogue has been published | Lookup completed, nothing found | Publisher not running for over ~2 h; run `publish` |
| Nobody is sharing the new catalogue right now | Pointer found, torrent unavailable | Keep `publish` running |
| A new catalogue failed verification | Wrong key, damaged or malformed release | Rebuild with the right seed; publish a new version |
| A newer catalogue needs a newer version of the app | `minVersionCode` above this build | Update the app |

---

## Code map

| Where | What |
| --- | --- |
| `core/catalogue` | Pure JVM. Release format, id rules, Ed25519 verification, the release verifier and writer, the fetch sequence, and the transport interface. Shared by the app and the tool. |
| `core/catalogue-swarm` | Pure JVM. The libtorrent side: DHT pointer lookup and publish, release torrent download and seeding, torrent building, DHT state persistence. Tested against real libtorrent, one process per session for anything networked. |
| `core/torrent` · `LibTorrentEngine` | Runs the catalogue transport on the app's own session, saves and restores DHT state. |
| `core/data/catalog` | `LayeredCatalog` (which catalogue is in use), `FetchedCatalogStore` (releases on disk), `CatalogUpdater` (when to check and what to do with the result). |
| `core/data/repository/MediaRepository` | Every screen's flows follow the catalogue's generation. |
| `feature/settings` | The Catalogue section. |
| `tools/catalog-publisher` | `keygen`, `pin`, `build`, `verify`, `publish`, `fetch`, `dht-node`. |

## How it is tested

| Where | What the tests prove |
| --- | --- |
| `core/catalogue` | The release format, ids and content rules; Ed25519 against the RFC 8032 vectors; every verifier rejection, from a flipped signature byte to a lying title count; the fetch sequence against a fake transport. |
| `core/data` | The release store, which catalogue is in use and when it falls back, the updater's schedule and every failure it can meet, screens following the catalogue's generation, and that the bundled catalogue matches its manifest with every id pinned. |
| `core/catalogue-swarm` | Against real libtorrent: Ed25519 and bencoding interop, release torrents, DHT state, the bounded session shutdown, and a single session's timeouts and refusals. `process/SwarmNetworkTest` then runs the whole path with an anchor, a publisher and a client, each a process of its own: nothing published, a release found and verified, up to date, an impersonator ignored, a newer release found through the DHT alone, a forged release refused, seeding stopped and resumed, and rapid successive releases. |
| `tools/catalog-publisher` | Argument parsing, key generation, pinning, building and verifying, the launcher's main class, and loading the desktop native library. |

Each libtorrent session in the network tests has a process of its own because several libtorrent 1.2
sessions busy on the DHT inside one process corrupt each other's native memory (see Known limits). The
app and the publisher tool each run a single session, so the tests match how the code really runs.

Beyond the automated tests: the private rehearsal above, and the real-device checklist in section 9 of
`TESTING_PLAN.md`.

## Known limits

- **Pointer availability depends on the publisher.** Only the key holder can refresh the DHT item.
  Televisions seed the release, not the pointer.
- **A stale pointer can briefly win.** If some DHT nodes still hold an old pointer with a higher
  sequence number than a fresh put reached, a lookup may return the older release until the next
  re-put overrides it. The app never installs a release older than the one it has, so the only effect
  is a delay in learning about the newest one.
- **The emulator reaches the DHT on some host networks and not others.** Section 1 of `TESTING_PLAN.md`
  found no UDP through the emulator. On 2026-09-12 the development emulator did reach the public DHT: the
  updater reported 78 nodes and a completed lookup (nothing published under the production key). A full
  release still cannot be rehearsed there without publishing publicly, so the transport is proven on a
  private libtorrent network on a desktop JVM, and the rest on a real Fire TV.
- **Several libtorrent sessions must not share a process.** libtorrent 1.2.3, pinned for Fire OS 5,
  corrupts native memory now and then when several sessions busy on the DHT run inside one process: on
  Windows the process ends with STATUS_HEAP_CORRUPTION or STATUS_BAD_FUNCTION_TABLE, on Linux with a
  SIGSEGV on a libtorrent thread. The app and the publisher tool run one session each, and the network
  tests give every session a process of its own. Do not start a second session inside the app.
- **libtorrent's session shutdown can hang.** Its session destructor now and then never returns. Every
  stop is bounded: the tool gives up after 15 s and exits, and the app's engine gives up after 20 s,
  releases its lock, and declines to start a new session until the old one has really gone, reporting the
  engine as unavailable in the meantime.
- **No background refresh.** Like the films, the catalogue updates only while the app runs with
  sharing on.
