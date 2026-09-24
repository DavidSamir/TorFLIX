# Catalogue publishing

Merge any number of catalogue files into one catalogue, check it the way a television will, sign it,
and publish it over the peer-to-peer DHT. There is no server: the catalogue travels over BitTorrent,
like the titles it lists.

```
add/*.json  +  remove/*.json
        │  merge    one catalogue: the newest copy of each title, minus what remove/ lists
        ▼
output/catalog.json
        │  build    gzip, manifest, signature, torrent; verified with the app's key and build
        ▼
.release/torfilx-catalogue-N/
        │  publish  seed it and keep its pointer in the DHT
        ▼
televisions download one signed catalog.json
```

The app is not involved in any of this: it still receives one catalogue file.

## Quick start

Put catalogue files in `add/` (and titles to drop in `remove/`), then just run the script:

```powershell
.\publish-catalog.ps1
```

```bash
./publish-catalog.sh
```

It settles everything by itself:

| | How |
| --- | --- |
| Java | `JAVA_HOME`, else Android Studio's bundled JBR, else `java` on the PATH |
| Seed | `-SeedFile`/`--seed`, else `TORFILX_PUBLISHER_SEED`, else the seed in `~/.torfilx/` that the app trusts (`catalogue-publisher.seed` first) |
| Release number | One higher than every number used: `.release/`, `dist/catalogue/` and `.release/versions-used.txt` |
| Oldest app build | The last release's, and at least 17 when there are shows |
| Earlier publishers | Stopped once the new release is built and verified, since only one release may be published at a time. `seed-titles` is never touched |

To check everything without publishing, and without the seed: `-DryRun` / `--dry-run`.

Publishing runs for 24 hours (`-Hours`/`--hours` to change). The DHT forgets the pointer about
2 hours after it stops; run the script again and, if nothing changed, the same release is published
again. Running it while an earlier run is still publishing is fine: the new run takes over.

## The folders

| Folder | What it holds |
| --- | --- |
| `add/` | Catalogue files: `.json`, `.json.gz`, `.jsonl` or `.ndjson`, any number, sub-folders too |
| `remove/` | Titles or episodes to take out |
| `published/` | One folder per release pushed (see below) |
| `output/` | Written by every run: `catalog.json`, `merge-report.txt`, `merge-summary.properties` |
| `.release/` | Every release built, `torfilx-catalogue-N/` and its `.torrent`, and `versions-used.txt`. Never edited |

`add/`, `remove/`, `published/` and `output/` are not committed (this repository is public).

### published/: every release pushed

Each push files its release in `published/release-N (date time)/`:

```
published/
  release-4 (2026-09-24 10-15)/
    catalog.json        what televisions receive
    merge-report.txt    everything the merge found and did, file by file
    release/            the signed release and its torrent
    published.txt       when it was pushed (and each time it was pushed again), titles, key
```

`add/` and `remove/` stay the only input folders; `published/` is never read.

### versions-used.txt

A television ignores a release that is not newer than the one it has, so a release number is never
used twice. Every number built is recorded in `.release/versions-used.txt`, which outlives the
release folders; add a line (`5  2026-09-24  why`) for any number used elsewhere, such as by CI.

### add/: one file per title

```
add/
  movies/
    2019/
      Avengers - Endgame (2019).json
  tv shows/
    Breaking Bad (2008).json
```

Each file is a list holding one title, the catalogue format. The name is only for people: a title is
known by the `id` inside, so renaming or moving a file changes nothing. In names, `:` is written ` -`
and the other characters Windows forbids are left out.

- **Add** a title: add a file for it. A new file is the newest, so the title leads the catalogue.
- **Change** a title: edit its file. Saving makes it the newest, so it moves to the front too (the
  app's Recently added sort goes by year first, so it only moves among titles of its year).
- **Take out** a title: list it in `remove/`. Deleting its file takes it out too, but only with a
  warning that it "vanished": the run stops only when more than 5% of titles vanish at once, so one
  file deleted by accident drops that title from the next release. Read the warnings before
  publishing.

The files' modification times were set so the split kept release 4's order exactly. Copying `add/`
with something that resets those times changes the order, never the content; with `-Order name` the
order is then shows first, then films by year, newest year first.

### add/: newest copy wins

Every file is read, newest first, and the first copy of each title found is used. A title is the
same title in every file when it has the same `id`, or, without an id, the same kind (film or show),
title and year; `{"title": "The Kid", "year": "1921"}` and `{"id": "catalog-the-kid-1921", ...}` are
one title. Older copies are passed over, and the report says which copy won each time.

**Which file is newest.** By default, its modification time. After a git checkout, an unzip or a
copy, those times say nothing, and the run warns when every file has almost the same time. Name files
so they sort by age (`2026-09-24-new-films.json`) and use `--order name` (`-Order name`): the name
that sorts last is the newest, with numbers compared by value, so `batch-10` comes after `batch-9`.

**Order in the app.** Titles are listed newest first, by where each first appeared: brand-new
titles lead, and re-adding a title in a newer file to change its poster does not move it.

**Shows.** The newest copy of a show is used whole. If an older copy has episodes the newest one
lacks, the run warns and names them. `--merge-episodes` (`-MergeEpisodes`) instead gives the show
every episode any copy has, the newest copy of each.

**Keeping ids.** An id is where viewers' progress and My List entries are kept. To rename a title
or correct its year, keep its `"id"` in the new copy. If a title of the last release would get a new
id, the run says so and gives the id to put back.

### remove/: what to take out

A remove file is a list. Each item is one of:

```json
[
  "catalog-inception-2010",
  "show-the-beverly-hillbillies-1962-s01e03",
  { "title": "Nosferatu", "year": "1922" },
  { "title": "Metropolis" },
  { "id": "catalog-the-kid-1921", "title": "The Kid", "year": "1921" }
]
```

- An id removes that title, or that one episode.
- A title, with or without a year and a `type`, must name exactly one title. If it names two, the
  run stops and lists them.
- A title copied whole from a catalogue file works too: its `"id"` is used, and its title and year
  must agree with the title that has that id, or the run stops. A copied show is removed whole.
- Anything in `remove/` wins over `add/`.

Removing is where a mistake does most harm, so a remove file that cannot be read, or an item that is
unclear, stops the run. An item that matches nothing is a warning, with the nearest names.

## What is repaired, skipped and refused

Files are read however they were saved: UTF-8 with or without a byte order mark, UTF-16 (Windows
PowerShell's default), gzip, JSON with comments or trailing commas, JSON Lines, a single title
instead of a list, or `{"movies": [...], "shows": [...]}`. Text that is not valid in its encoding is
refused rather than read as garbled titles.

| Kind | Examples | What happens |
| --- | --- | --- |
| Repaired, with a warning | `"year": 1962`, `"runtimeMinutes": "120"`, `"imageUrl"`, an id with spaces around it, `&amp;` in a magnet, an episode's `"title"` for `"name"`, `"type": "Show"` | Read as meant |
| Left out, with a warning | malformed or repeated magnets, a season or episode without a number, a repeated episode, a date that is not `1962-09-26`, an image that is not `http(s)://`, fields the format does not have | The rest of the title is used |
| Refused, with a warning | no title, an unknown `type`, a film with seasons, a show with no usable season, an id that cannot be derived | An older copy is used, if there is one |
| File skipped, with a warning | a file in `add/` that is not JSON, empty, too large or not valid text | The other files are used |
| Run stops | any problem in `remove/`, an unclear remove item, an empty result, a catalogue over the size limits, too many titles of the last release vanishing, a release number that is not newer | Nothing is written, built or published |

`--strict` (`-Strict`) turns every warning into a stop. Everything is in `output/merge-report.txt`,
file by file.

## Safety checks before anything is published

1. The seed signs with a key the app trusts (`productionCataloguePublisherKeys` in
   `core/data/build.gradle.kts`), or the run stops before merging.
2. The merged catalogue passes the publisher's own rules and its own build step, byte for byte.
3. **Vanished titles.** A title of the last release that is in neither folder has vanished; each one
   is listed, and if more than 5% vanish the run stops (`--allow-vanished`, `--max-vanished-percent`).
4. **Release number.** One higher than every number used (`.release/`, `dist/catalogue/`,
   `versions-used.txt`). A television ignores any release not newer than the one it has, so a lower
   `--version` is refused. If nothing changed since the latest release, it is published again
   instead of a new one.
5. **Oldest app build.** Carried over from the last release, and at least 17 when there are shows
   (builds before 0.2.7 would show them as films that cannot play). A value above the app's current
   `versionCode` is refused, since no television could install it.
6. The release is built in a staging folder, verified with the app's key and current build, checked
   to hold exactly the merged catalogue, and only then moved into `.release/`.

## Options

| bash | PowerShell | |
| --- | --- | --- |
| `<seed>` or `--seed <file>` | `-SeedFile <file>` | The publisher seed, when it is not in `~/.torfilx/` |
| `--dry-run` | `-DryRun` | Merge and check only |
| `--no-publish` | `-NoPublish` | Build and verify, do not publish |
| `--version <n>` | `-Version <n>` | Release number |
| `--min-version-code <n>` | `-MinVersionCode <n>` | Oldest app build that may install it |
| `--hours <h>` | `-Hours <h>` | How long to publish (default 24) |
| `--listen <interfaces>` | `-Listen <interfaces>` | libtorrent listen interfaces, e.g. `0.0.0.0:6881` |
| `--order mtime\|name` | `-Order mtime\|name` | How the newest file is decided |
| `--merge-episodes` | `-MergeEpisodes` | Give a show every episode any copy has |
| `--strict` | `-Strict` | Any warning stops the run |
| `--allow-vanished` | `-AllowVanished` | Let titles of the last release disappear |
| `--max-vanished-percent <p>` | `-MaxVanishedPercent <p>` | How many may vanish before stopping (default 5) |
| `--strip-trackers` | `-StripTrackers` | Two trackers per magnet, when near the size limit |
| `--tracker <url>` | `-Tracker <url>,...` | Extra trackers for the release torrent |
| `--add <dir>`, `--remove <dir>` | `-Add <dir>`, `-Remove <dir>` | Other folders; they must exist |
| `--keys <hex,...>` | `-Keys <hex,...>` | Keys the release must verify with, for a test build |

`JAVA_OPTS` is passed to Java, for example `JAVA_OPTS=-Xmx4g` for folders of millions of files.
In a test, 100,000 small files (20,000 distinct titles) merged in about 25 seconds using under
150 MB of memory.

The merge is also a command of the publisher, for use on its own:

```bash
./gradlew :tools:catalog-publisher:run --args="help"
```

## Publishing from CI

A CI checkout gives every file the same modification time and has no earlier releases, so pass
`--order name` and a `--version` higher than the last release published. The files for `add/` and
`remove/` must be provided by the job, since they are not in the repository. See
[`.github-actions-example.yml`](.github-actions-example.yml).

## Troubleshooting

- **"the seed signs as ..., which the app does not trust"**: the wrong seed, or an app built with a
  different key. Every television would refuse the release.
- **"no publisher seed found"**: put the seed at `~/.torfilx/catalogue-publisher.seed`, set
  `TORFILX_PUBLISHER_SEED`, or pass it.
- **"no earlier release was found"**: the first release from this machine. Pass `--version`, one
  higher than the last release you published (or record it in `.release/versions-used.txt`).
- **"would disappear without being in the remove folder"**: a file is missing from `add/`, or the
  titles really are meant to go: list them in `remove/`.
- **"another run is merging or building"**: wait for it. If none is running, delete `.publish.lock/`.
- **The DHT pointer expires** about 2 hours after publishing stops. Run the script again with the
  same folders: nothing changed, so the same release is published again.

See [`docs/CATALOGUE_P2P.md`](../../docs/CATALOGUE_P2P.md) for the release format and trust rules.
