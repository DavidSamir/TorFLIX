# TV shows — design and plan

**Status:** built through phase 3 on top of 0.2.6 (`versionCode` 16), not yet released. Every code step
in "Order of work" is done and unit-tested. Still open, none of them code: publishing the first
release with a show (phase 1 step 9), the season-pack and migration checks on a device (phase 2 step
4, phase 3 step 1), and stripping trackers once the catalogue nears the size limit (phase 3 step 6).
Every decision that was open in the first draft is settled in "Decisions" below.

## What this is for

TORFILX is a film catalogue. Every type, screen, rule and test assumes one title is one playable
thing: `MediaItem` is documented as "films only, no series, no seasons, no episodes",
`MediaCard.playableId` is always the item's own id, and the details screen is one column of quality
buttons.

The goal is to add series without changing what makes the app work: one signed catalogue that
travels over the swarm, every title streamed from a magnet, all watch state local, sharing off until
consented, and a floor device of a 2016 Fire TV Stick with a ~128 MB heap.

A show has to behave like a show, not like a folder of films: a show card opens the show; the primary
button is *Resume S2 E3* or *Play S1 E1*, never a quality picker; an episode ending rolls into the
next one; Continue Watching holds one card per show; My List holds the show, not its episodes.

Hard constraints, in priority order:

1. **Nothing installed today breaks.** Existing film entries stay byte-for-byte identical, every
   pinned id is kept, no progress or My List row is orphaned, and phases 1 and 2 need no Room
   migration.
2. **The release trust chain is untouched.** Same files, same signatures, same verifier order,
   same "every declared entry maps to a title" rule.
3. **Cheap on the floor device.** Catalogue memory and per-tick work stay inside the budgets already
   documented in `CatalogRelease` and `MediaRepository`.
4. **Nothing reaches the network before consent**, including for the *next* episode.

The original engineering plan (`plan.md` §6.4, §7.4, §7.5) designed shows for a media server and
the code was then stripped to films (`plan.md` line 425). Its rules for next-up, the season selector,
the countdown card and "still watching" are reused here; the data layer is new because the source
is a signed catalogue and a swarm, not an API.

---

## Decisions

The five questions left open in the first draft, answered, plus three found by reading the code.

| Question | Decision | Why |
| --- | --- | --- |
| Tab order | Home · Movies · **Shows** · My List · Search · Settings | Films are 2 000 titles and shows start at a handful; Movies stays first. Six tabs measure about 780 dp against the 864 dp the bar has at 960 dp. |
| Genre rows on Home | Mixed: films and shows in one row per genre, show cards carry a "SERIES" label; plus one dedicated "TV shows" row | `MIN_GENRE_ROW_SIZE` is 2, so per-kind genre rows would be empty for months. Mixed rows surface shows where the viewer already looks. The label stops a poster being mistaken for a film. |
| Episode quality | Automatic, with a Menu long-press override on the episode row | A button per quality per episode does not fit a list. Films keep their buttons. Automatic selection needs the two selector fixes below to be safe. |
| Strip trackers from magnets | Build `--strip-trackers` into the publisher in phase 1, **do not apply it** to the first show release; apply once `catalog.json` passes 8 MB | Half the catalogue's bytes are dead tracker URLs and the app adds live ones anyway, but changing 2 000 film entries in the same release that introduces shows mixes two risks. The lever exists before it is needed. |
| Specials (season 0) | Listed last, playable by hand, **skipped** by next-up and autoplay | Specials interrupt the story when chained. This is what every mainstream player does. |
| Quality "Direct only" (found) | Treat a torrent source as direct: `DIRECT_ONLY` filters out `HLS`, not everything that is not `DIRECT` | Today `SourceSelector.select` under `DIRECT_ONLY` keeps only `SourceKind.DIRECT`, and every catalogue source is `TORRENT`, so Play from the hero or Continue Watching fails with "format not supported" whenever that preference is set. Episodes make automatic selection the main path, so this ships first. |
| Quality "Auto" picks the largest file (found) | Under `AUTO`, prefer the largest source whose height is at or under the display's height; only go above it when nothing else exists | Torrent sources carry no codec, so `canPlay` trusts them and the comparator then orders by height: a 2160p file wins on a 1080p stick, which is the slowest possible start over a swarm. `CAP_1080P` stays as the explicit cap. |
| Stopped torrents leave data behind (found) | With "Keep seeding after playback" off, `TorrentCoordinator.stopStreaming` removes the torrent **with** its data | Today it calls `remove(deleteData = false)`; the files stay on disk, leave `_torrents`, and `enforceStorageBudget` can no longer evict them because it only considers listed torrents. Ten episodes in one evening on a 5 GB stick is 3 GB of unevictable data, `guardFreeSpace` then pauses everything, and playback stalls. No resume data is kept, so the files were dead weight anyway. |

---

## What exists today, and what is missing

| Needed for shows | Available now? |
| --- | --- |
| A media *type* to branch on | ✗ `MediaItem` has none; every screen renders a film |
| Season / episode types | ✗ nothing |
| Progress per episode | ✓ `PlaybackProgress.itemId` is documented as "a movie or a single episode"; the `progress` table is keyed by any string id |
| A card that plays something other than its item | ✓ `MediaCard.playableId` exists as a separate property, always `item.id` today. Every lazy row already keys on it |
| Autoplay next episode | ✗ `AppSettings.autoplayNextEpisode` and its Settings toggle exist and do nothing |
| A next-episode countdown | ✗ only the 3-hour idle "still watching" in `PlaybackController` |
| Player title + subtitle line | ✓ `PlayerUiState.subtitle` exists and is rendered, never set |
| Streaming one file out of a multi-file torrent | ✗ `LibTorrentEngine.stream` always picks the largest video file; `managedTorrents` and the loopback server are keyed by info hash only |
| A "Shows" library mode | ✗ `LibraryMode` is `MOVIES`, `MY_LIST`; its doc comment already mentions "the Shows tab" |
| Show-aware ids, content rules, pinning | ✗ `CatalogIds`, `CatalogContentRules`, `CatalogPinning` walk a flat list |
| Show entries in the release format | ✗ `CatalogEntryDto` is flat; `countDeclaredTitles` counts `"title"` byte-strings |
| 16:9 card, "episodes" dimension | ✓ `LandscapeCard` and `TorfilxDimens.episodeCardWidth` exist, from the original plan |

---

## Guiding design choices

| Choice | Why |
| --- | --- |
| Shows live in the same `catalog.json` array, as entries with `"type": "show"` | One file, one manifest, one signature, one DHT pointer. A second file touches `RELEASE_FILES`, the verifier, the torrent builder and the pointer for nothing. |
| `type` defaults to `movie` when absent | Existing entries do not change by a byte until a show is added, so the bundled manifest's SHA-256 and `BundledCatalogueReleaseTest` stay valid. |
| Episode names use the key `"name"`, never `"title"` | `countDeclaredTitles` counts `"title"` byte-strings and the trust chain requires that number to equal the top-level entry count (rule 6). One `"title"` per top-level entry keeps the rule, the verifier and the count check exactly as they are. |
| `schemaVersion` stays `1`; the first release carrying a show sets `minVersionCode` | The container is unchanged. `minVersionCode` is the mechanism that already exists for "needs a newer app", and `CatalogUpdater` already records the rejection per app build and retries after an update. Without it an old build decodes a show as a film with no magnets and shows an unplayable card. |
| Progress is keyed by the episode id | No new table, no migration. Continue Watching, backup/restore and mark-watched all flow through the existing `itemId` string. |
| Show ids `show-<slug>-<year>`, episode ids `<showId>-s01e03`, both pinned by the publisher | Same contract as films: deterministic from content, so the "pinned equals derived" property holds and ids are opaque after pinning. |
| Per-episode torrents first, season packs second | A single-file torrent streams today with no engine change. Packs need file switching inside one handle, the riskiest piece, so it gets its own phase and its own device check. |
| Episode order is `(season, number)`, numerically | Ids carry zero-padded numbers for readability only; nothing sorts by id. |

---

## The catalogue format

### A show entry

```json
{
  "id": "show-the-twilight-zone-1959",
  "type": "show",
  "title": "The Twilight Zone",
  "year": "1959",
  "image_url": "https://…/poster.jpg",
  "backdrop_url": "https://…/backdrop.jpg",
  "overview": "…",
  "genres": ["Sci-Fi", "Drama"],
  "seasons": [
    {
      "number": 1,
      "name": "Season 1",
      "image_url": "https://…/s1.jpg",
      "packs": [
        { "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }
      ],
      "episodes": [
        {
          "id": "show-the-twilight-zone-1959-s01e01",
          "number": 1,
          "name": "Where Is Everybody?",
          "overview": "…",
          "runtimeMinutes": 25,
          "airDate": "1959-10-02",
          "image_url": "https://…/s01e01.jpg",
          "magnets": [
            { "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }
          ]
        }
      ]
    }
  ]
}
```

`backdrop_url` is new and optional for films too; today `image_url` is used for both poster and
backdrop. Not required for shows, but a 2:3 poster stretched to 16:9 behind an episode list looks
worse than it does behind a film's four lines of text.

### Field rules

| Field | Rule |
| --- | --- |
| `type` | `movie` (default) or `show`. Anything else is refused by the publisher; the app skips such an entry with a log line, so a release containing one is refused before installation by trust-chain rule 8. |
| `title` | Exactly one per top-level entry, non-blank. **Never inside a season or episode.** |
| `seasons` | Show only; ≥ 1 season. A film with `seasons` is refused. |
| `seasons[].number` | Integer ≥ 0, unique within the show. `0` is Specials. |
| `seasons[].name`, `image_url` | Optional. Defaults "Season N" and "Specials". |
| `seasons[].episodes` | ≥ 1 episode. |
| `seasons[].packs` | Optional (phase 2). Whole-season torrents; an episode may be played from a pack by file selection. Ignored by phase 1 builds. |
| `episodes[].id` | Pinned by the publisher; valid per `CatalogIds.isValid`; unique across the **whole** catalogue, films included. |
| `episodes[].number` | Integer ≥ 1, unique within the season. |
| `episodes[].name` | Optional; the UI shows "Episode N". |
| `episodes[].runtimeMinutes` | Optional; ≤ 0 is treated as absent. |
| `episodes[].airDate` | Optional ISO date, display only; unparseable is absent. |
| `episodes[].magnets` | Same shape as a film's. Malformed magnets are dropped with a log line as for films. |
| `magnets` on a show entry | Refused. |
| Episodes per show | ≤ `MAX_EPISODES_PER_SHOW` (2 000), a sanity cap. |

A hand-edited file still loads in the app without ids (derived at parse time, like films). Only a
published release must carry them.

### Ids

| Kind | Derived id | Collision rule |
| --- | --- | --- |
| Film | `catalog-<slug>-<year>` (unchanged) | first info hash, then position (unchanged) |
| Show | `show-<slug>-<year>` | first info hash of its first episode, then position |
| Episode | `<showId>-s<SS>e<EE>`, two digits minimum | position within the catalogue walk |

`CatalogIds.pin` walks films, shows, seasons and episodes in file order with one shared `usedIds`
set, so an episode id can never collide with a film id. Pinning is idempotent. The app's parser does
the same walk with the same fallbacks, so a hand-edited file derives the ids the publisher would pin.

After pinning, an id is opaque: renaming a show, correcting its year, renumbering or moving an
episode keeps the id and therefore the progress. The id may then lie about the episode's number;
nothing reads a number out of an id.

### Content rules added to `CatalogContentRules`

- `type` valid; `seasons` only on shows; `magnets` never on shows.
- Every show has ≥ 1 season, every season ≥ 1 episode; season and episode numbers unique.
- Every episode id explicit, valid and unique across the catalogue (release only).
- Episode count per show within the cap.
- When `declaredTitles != entries.size` and any entry has `seasons`, the message appends:
  *"episodes are named with \"name\"; a \"title\" key inside a season or episode breaks the count"*.

The publisher runs these before signing (`CatalogPinning.pin` and `CatalogueReleaseWriter.write`)
and the app runs them again in `CatalogueReleaseVerifier` before installing, unchanged in shape.

### Manifest

`titleCount` keeps meaning **top-level entries**. One optional field is added:

| Field | Meaning |
| --- | --- |
| `episodeCount` | Total episodes. When present the verifier checks it against the decoded content; when absent it is not checked. Old apps ignore unknown manifest fields, so adding it is safe. |

### Size budget

The decompressed catalogue is capped at 12 MB (`MAX_JSON_BYTES`) because it is held as bytes, then
a string, then objects on a small heap. Today's 2 000 films are 2.27 MB, about 1.1 KB per film,
mostly the seven tracker URLs repeated in every magnet.

| Episode shape | Bytes per episode | Episodes that fit beside today's films (~9.7 MB free) |
| --- | --- | --- |
| One magnet, trackers as they come | ~800 | ~12 000 (≈ 120 shows of 100 episodes) |
| One magnet, trackers stripped | ~250 | ~39 000 |
| Two qualities, trackers stripped | ~350 | ~28 000 |

The publisher's `build` prints the JSON size and the percentage of the cap on every run, so the
number is seen before it matters. Raising `MAX_JSON_BYTES` is not on the table without measuring
heap on a Fire OS 5 stick.

---

## Domain model (`core/model`)

```kotlin
enum class MediaKind { MOVIE, SHOW }

data class MediaItem(
    // … existing fields unchanged …
    val kind: MediaKind = MediaKind.MOVIE,
    val seasonCount: Int = 0,     // shows only; specials excluded
    val episodeCount: Int = 0,    // shows only; specials included
)

data class Season(val number: Int, val name: String, val image: String?, val episodes: List<Episode>) {
    val isSpecials: Boolean get() = number == 0
}

data class Episode(
    val id: String,               // the progress key
    val showId: String,
    val season: Int,
    val number: Int,
    val name: String?,            // null → "Episode N"
    val overview: String?,
    val runtimeMs: Long?,
    val airDateMs: Long?,
    val image: String?,
) {
    val code: String get() = "S$season E$number"
    val displayName: String get() = name ?: "Episode $number"
}

data class MediaCard(
    val item: MediaItem,
    val episode: Episode? = null,        // set on Continue Watching / hero cards for shows
    val progress: PlaybackProgress? = null,
    val inMyList: Boolean = false,
    /** Films: progress.watched. Shows: every non-special episode watched. */
    val isWatched: Boolean = progress?.watched == true,
) {
    val playableId: String get() = episode?.id ?: item.id
    val runtimeMs: Long? get() = episode?.runtimeMs ?: item.runtimeMs
}
```

Pure rules, unit-tested without Android:

| Rule | Behaviour |
| --- | --- |
| `ShowPlayRules.nextUp(seasons, progressById)` | The most recent activity on the show decides. 1. If it was leaving an episode part-way (`ResumeRules.isInProgress`), that episode is resumed, a special included. 2. If it was finishing one, the first unwatched **playable** episode after it in `(season, number)` order; failing that, the first unwatched playable one from the start. 3. When every regular episode is watched, the first playable one with `restart = true`. 4. When nothing is watched, the first playable regular episode. 5. A specials-only show starts at its first playable special. Unplayable episodes are never chosen; null only when nothing can play. Progress rows for ids that are not episodes of this show are ignored. |
| `ShowPlayRules.nextAfter(seasons, episodeId)` | The next episode in `(season, number)` order across season boundaries, skipping specials; null at the end. Does **not** skip unplayable episodes: the caller decides what to say. |
| `PlayActionResolver.actionFor(show, seasons, progressById)` | `Resume(episodeId, pos)` / `Play(episodeId, restart)` for the next-up episode; `Unavailable` when no episode has a source. |
| `ShowWatchedRules.isWatched(seasons, progress)` | True when the show has ≥ 1 non-special episode and all of them are watched. |
| `ShowWatchedRules.matches(filter, …)` | `WATCHED` = all watched; `UNWATCHED` = not all watched (a half-watched show is "unwatched", exactly as a half-watched film is today). |
| `EpisodeFileMatcher.select(fileNames, season, number, episodesInSeason)` | Phase 2 (pure function shipped in phase 1 as a hint). See the engine section. |
| `SourceSelector` changes | `DIRECT_ONLY` excludes `HLS` only. `AUTO` prefers height ≤ `capabilities.maxDisplayHeight`, then the highest of those; sources above the display height come last. Ties: own torrent before season pack. |

`LibraryQuery` gains `kind: MediaKind?`. `LibraryMode` gains `SHOWS`. `HomeRowKind` gains `SHOWS`.
`Format.metaLine` prints "5 seasons" for a show in place of the runtime.

---

## Data layer

### DTOs (`core/catalogue/format/CatalogDto.kt`)

`CatalogEntryDto` gains `type: String? = null`, `backdropUrl: String? = null` (`backdrop_url`) and
`seasons: List<CatalogSeasonDto> = emptyList()`. New `CatalogSeasonDto(number, name, imageUrl,
packs, episodes)` and `CatalogEpisodeDto(id, number, name, overview, runtimeMinutes, airDate,
imageUrl, magnets)`. Everything optional or defaulted; `CatalogueJson.content` stays lenient. A
season or episode with a wrong-typed field makes that *entry* fail to decode, exactly as a film with
a wrong-typed field does today: the atomic decode fails, the resilient reader skips the entry, the
catalogue is reported incomplete and is not cached. A release with such an entry never signs.

### Parsing (`core/data/catalog/CatalogParsing.kt`)

`mapCatalogEntry` branches on `type`:

- `movie` → today's path, byte for byte.
- `show` → a `CatalogItem` whose `item.kind == SHOW`, whose `sources` is empty, and whose new
  `seasons: List<CatalogSeason>` holds `CatalogEpisode(episode, sources)` per episode. Episode
  sources are built exactly like a film's (`torrent-<hash>`, quality height from the label). In
  phase 2 each season pack adds one source per episode with a `FileSelection.Episode`.
- unknown → skipped with a log line.

Seasons are sorted by number with specials last; episodes by number. A show whose every episode
lost its magnets to validation is kept as an unplayable show, as an unplayable film is kept today.

### Snapshot and `Catalog`

`CatalogSnapshot` adds two indexes, built once per generation: `episodeById` (episode id →
`Playable.EpisodeOf(show, season, episode, sources)`) and `seasonsByShowId`. `Catalog` gains:

```kotlin
sealed interface Playable {
    data class Film(val item: MediaItem, val sources: List<MediaSource>) : Playable
    data class EpisodeOf(val show: MediaItem, val season: Season, val episode: Episode, val sources: List<MediaSource>) : Playable
}
fun playable(id: String): Playable?          // film ids and episode ids; null for show ids
fun seasons(showId: String): List<Season>
fun sourcesFor(id: String): List<MediaSource> // now resolves episode ids too
```

`mediaItems()` returns films and shows in catalogue order, so every existing row and grid works with
a kind filter and nothing else. `genres` includes show genres. Search stays over titles.

### `MediaRepository`

- `CatalogViews` gains `shows: List<MediaItem>`; the sorted lists include shows.
- `observeLibrary(query)` filters by `query.kind`; the watched filter uses `ShowWatchedRules` for
  shows from the progress map already in the combine. `isWatched` is set on every card.
- `observeHome()` adds the `SHOWS` row ("TV shows", newest first, `MAX_ROW_ITEMS`) after Continue
  Watching and before "Recently added". Genre rows and "Recently added" mix kinds. Preview
  subtitles say "all in Shows" for the shows row and "all in Movies and Shows" for mixed rows.
- The hero receives `HeroItem`s whose `action` is the show's next-up action, and the hero label
  comes from that action ("▶ Resume S2 E3"), not from `card.progress`.

### `ProgressRepository`

- `observeContinueWatching()` resolves each in-progress row through `catalog.playable(id)`. A
  film row becomes the card it is today. An episode row becomes `MediaCard(item = show, episode,
  progress)`. **One card per show**: the newest in-progress episode wins. Rows whose id resolves to
  nothing (an old catalogue, a stray show id) are skipped, as unknown ids are today.
- `markWatched(id, …)`: for an episode id, unchanged. For a show id, expands to every non-special
  episode in one `@Transaction` (`ProgressDao.upsertAll`), so a crash mid-way never leaves half a
  season marked. Mark *unwatched* writes position 0 / watched false per episode, the film rule.
- `markSeasonWatched(showId, season, watched)` for the details screen, same transaction.
- Menu → Remove on an episode card deletes that episode's row only; if the viewer had sampled two
  episodes, the other one then surfaces. That is the film behaviour applied per episode.

### `PlaybackInfoRepository` and the player's item lookup

Both resolve through `catalog.playable(id)`. An episode yields its own sources and runtime; a film is
unchanged. A show id is never a playable: `PlaybackController` maps a show id to its next-up episode
before doing anything else, so a stale or hand-typed route can never open a show as a film.

### Room, backup

**No migration in phases 1 and 2.** Episode ids are strings in the existing `progress` table,
`my_list` holds show ids, `UserDataBackup` exports both untouched. A backup restored into an app
whose catalogue lacks some episode ids stores the rows dormant, as it does for films today. Phase 3's
next-up dismissal is the first schema change.

---

## The torrent engine

### Phase 1: no engine change, one hint

Per-episode torrents stream today. Two small things are wired in phase 1 because they protect
against catalogue mistakes and cost nothing:

- `stream(magnet, hint: FileHint?)`: when the torrent turns out to hold several video files and a
  hint names `S01E03`, the file matching it is chosen; otherwise the largest video file, as today.
  A single-episode entry that points at a pack by mistake then plays the right episode instead of
  the largest one.
- `stream(…, displayName)`: `TorrentStatus.name`, and so the contribution record, says
  "The Twilight Zone · S1 E3" instead of a release file name.

### Phase 2: season packs

One handle serves many files over time.

| Today | Change |
| --- | --- |
| `stream(magnet)` picks the largest video file | `stream(magnet, selection: FileSelection = LargestVideo, displayName)` |
| `StreamedTorrent` has one immutable `fileIndex` | `@Volatile selected: SelectedFile` (index, name, size, offset, path) and `switchTo(index)` under the session mutex |
| `managedTorrents[infoHash]` early-returns the existing stream | Compares the requested selection with `selected` first; a different file gets old file → `IGNORE` (its pieces stay on disk for seeding), new file → `DEFAULT`, `prioritiseFrom(0)` |
| URL `/<infoHash>/<fileName>` | `/<infoHash>/<fileIndex>/<fileName>`; the server keys on the first segment and answers 404 when the second does not match the selected file, so a stale request from the previous episode can never read the wrong bytes |
| `cachedParts` over the one file | Over the selected file; same shape |
| `TorrentStatus.progress` | libtorrent reports progress over *wanted* files, so it reads as the selected file's progress without change |

**`EpisodeFileMatcher`** (pure, `core/model`): among video files (existing extension list, files
named `sample` or under 5 % of the median size excluded) match, in order, `S01E03`, `s1e3`, `1x03`,
`E03` when the pack holds one season, then the N-th video file by name when the count equals the
season's episode count. No match → `TorrentError.NoPlayableFile("no file for S1 E3 in this
torrent")`, permanent and not retried, like today's `NoPlayableFile`. A pack whose contents are
archives has no video file and fails the same way; the source is marked failed and the next one is
tried by the existing retry.

Storage: eviction stays per torrent, so a pack keeps every episode it has downloaded until the pack
is evicted as a whole; `sizeBytes` and the "exceeds budget" warning use the selected file; the
contribution record's `sizeBytes` becomes the largest episode file, so "copies shared" is an
approximation for packs and the page says so.

Device check required before phase 2 ships: libtorrent 1.2.3's behaviour when a partly downloaded
file's priority drops to `IGNORE` while another file in the same torrent goes to `DEFAULT`, on a
Fire OS 5 stick. Verified on a desktop JVM by a test with the fake `StreamSource` first.

---

## The player

### Title line

`openInternal` resolves the playable. For an episode: `title` = show title, `subtitle` =
`"S1 E3 · Where Is Everybody?"`, duration from the episode's runtime (then ExoPlayer's, as today),
`item` = the show.

### Advancing in place

`PlaybackController.advance(nextId)`: save progress, `stopStreaming` the current torrent (today's
`openInternal` overwrites `activeTorrentInfoHash` without stopping the previous one, which would
leave the finished episode marked "streaming" forever and immune to eviction), then `open(next)`
**keeping** `aspectMode`, `playbackSpeed` and `subtitlesEnabled` (today's open resets the whole
state). Same `ExoPlayer` instance, no navigation, no cross-fade. Progress is saved under
`resolved.playableId`, which follows the advance; the route's original id is only used by the first
open. Audio and subtitle overrides on the reused player carry across, which is right within a season
and is covered by the existing silent-audio recovery when it is not.

### What happens when an episode ends

`STATE_ENDED` → `onPlaybackEnded` marks the episode watched (as today), then:

| Situation | The player shows |
| --- | --- |
| A film | Nothing new: today's behaviour |
| Autoplay **off** | End card: "Next: S1 E4 · <name>" with **Play** (focused) and **Back** |
| Autoplay on, next episode playable, screen resumed, fewer than 3 unattended autoplays | Countdown card: "Next episode in 10 s", **Play now** (focused), **Back**; on zero → `advance(next)` |
| Autoplay on, 3 unattended autoplays in a row | The existing "Are you still watching?" overlay instead of a countdown. **Continue** resets the counter and starts the countdown; **Stop** leaves |
| Last episode of the show | End card: "You've reached the end of <Show>" with **Back** (focused) and **Watch again** (S1 E1, `restart`) |
| Next episode exists but has no source | End card: "S1 E5 isn't available" with **Back** |
| The show is no longer in the catalogue (a release swapped it out mid-episode) | End card with **Back** only |
| The app is not in the foreground (`ON_STOP`) | The countdown is cancelled and never starts a download; on return the card is there with **Play now** focused, no timer |
| Playback was seeked to the very end by hand | Same as a natural end: this is standard |

**Back** on any card leaves the player to wherever it was opened from: the show's details, or Home
when the episode was started from Continue Watching. It is not labelled "Back to show" because it
does not always go there.

The countdown lives in `EpisodeAutoplay`, which the controller owns, so no recomposition can restart
it and it is tested with virtual time; the screen only draws the card it is given. Any key press resets the
unattended counter (the viewer is present) but does not stop the countdown; Back leaves the player;
OK on **Play now** advances at once.

While the next swarm resolves the existing buffering overlay explains itself ("Fetching file
details…", "Looking for peers…", attempt 2 of 2), so a slow swarm is never a blank screen. A failure
to start the next episode is the existing error overlay with **Retry** and **Back**.

### What does not change

Frame-rate matching runs once per player screen; torrent sources carry no frame rate, so nothing is
lost on an advance. `PauseWhenBackgrounded`, `KeepScreenOn`, the 3-hour idle prompt, the consent
dialog on `SharingNotEnabled`, the audio-recovery escalation and `retry()` all keep working because
the advance goes through `open`.

---

## Screens

### Navigation

`Routes.SHOWS = "shows"` joins `TOP_LEVEL`; `NAV_ITEMS` gains "Shows" after "Movies";
`LibraryScreen` takes `LibraryMode.SHOWS` (empty copy: "No shows match these filters"; header line
"N shows · vX · catalogue N"). Cards open `details/{showId}`; play goes to `player/{episodeId}`.
Ids stay `[a-z0-9-]`, so the routes are unchanged. `details/{episodeId}` (nothing produces it, but a
saved back stack from a future build might) resolves to the show's details.

### Cards

- Show poster cards: label line `"1959 · 5 seasons"`, a small "SERIES" badge at the top-left, the
  ✓ badge when `isWatched`. Never a progress bar: a show card carries no `progress`.
- Episode cards (Continue Watching, hero): 16:9, the episode still when present, else the show
  backdrop, else the poster; label `"S1 E3 · 12m left"`; the progress bar.
- Accessibility descriptions say "series", the episode code and the remaining time.

### Show details (`feature/details`)

`DetailsScreen` branches on `item.kind`: films keep `FilmDetails` untouched; shows get `ShowDetails`.

```
┌──────────────────────────────────────────────────────────────────┐
│ backdrop                                                         │
│  THE TWILIGHT ZONE                                     SERIES    │
│  1959 · 5 seasons · Sci-Fi, Drama                                │
│  overview (2 lines)                                              │
│  [▶ Resume S2 E3 · 12:40]  [+ My List]  [← Back]                 │
├──────────────────────────────────────────────────────────────────┤
│  Season 1   Season 2   Season 3   Season 4   Season 5   Specials │  chips
│  ┌──────┐ 3  The Lonely                          25m  ▬▬▬▬░░ ✓   │
│  │thumb │    A convict serving his sentence on an asteroid…      │
│  └──────┘                                                        │
│  ┌──────┐ 4  Sixteen-Millimeter Shrine               25m         │
```

- One `LazyColumn`: item 0 the header, item 1 the season chips, then one item per episode. The
  header scrolls away as focus moves down, which is the standard TV pattern and needs no custom
  collapse. `focusRestorer` on the column and on the chip row, `keepNeighbourComposed` on every
  episode row so the D-pad never dead-ends, `initialFocus` on the primary button once content exists
  (the same three helpers Home and the grids use).
- The primary label comes from `PlayActionResolver` for shows: "▶ Resume S2 E3 · 12:40",
  "▶ Play S1 E1", "▶ Play again from S1 E1" when everything is watched; with nothing playable the
  button is not shown and the header says why.
- The selected season defaults to the next-up episode's season. Focus starts on the primary button, or
  on the episode row just played when the viewer comes back from the player. The list is not
  scrolled to next-up on open: that would push the focused button off screen, and it plays next-up.
- A single-season show hides the chip row. Twenty seasons scroll in the `LazyRow`.
- OK on an episode plays it with automatic source selection through the same consent gate as a
  film (`requestTorrentPlayback`); on decline the viewer stays on the screen. Menu on an episode
  opens a small anchored popup: one "Play in 720p / 1080p" entry per source when there is more than
  one, then "Mark watched" or "Mark unwatched". Menu on a season chip: "Mark season watched /
  unwatched". Back closes a popup first, then leaves the screen (the Settings screen's pattern).
- Unplayable episodes are listed greyed with "No source" so numbering stays honest; OK on one does
  nothing but show that text; Menu still offers mark watched.
- A newer catalogue swapped in while the screen is open re-emits the item and the seasons together
  (both derived in the same combine from the catalogue generation). A show that disappears shows
  the existing "not in the catalogue" error. Progress written every 10 s during playback re-emits
  the map; recomputing next-up is a pass over the show's episodes, not the catalogue.

`DetailsViewModel` gains `seasons`, `selectedSeason`, `episodeProgress` (from the
`observeAllProgress` map it already combines), `nextUp`, `popup`. `torrentSources` is no longer read
once at construction; it follows the item.

### Home

`HomeRowKind.SHOWS` renders as a poster row. Continue Watching holds episode cards; OK on one plays
the episode (`playActionFor(card)` uses the card's playable, today it uses `card.item.id`), Menu
removes it. The hero includes show cards from Continue Watching with the episode's action.

### Search

Titles, films and shows alike, then shows found by an episode's name (phase 3): three characters
or more, one result per show, after the title matches, the card naming the matched episode. Every
result opens details.

### Settings

"Autoplay next episode" stays. Phase 3 adds one line under it: *"Also starts finding peers for the
next episode while this one ends."* That is a privacy statement (the next swarm sees the viewer's
address before they chose it) and it belongs next to the switch.

---

## Publisher tool (`tools/catalog-publisher`)

- `pin`, `build`, `verify` walk shows through the new `CatalogIds.pin` and content rules.
- `build` prints "films N · shows N · episodes N", the JSON size as a percentage of the cap, and
  writes `episodeCount` into the manifest.
- `build --strip-trackers` keeps at most two `tr=` per magnet, preferring those in the app's
  `FALLBACK_TRACKERS` list. Built in phase 1, applied later (see Decisions).
- The first release carrying a show is built with `--min-version-code <first show-capable build>`;
  the runbook in `docs/CATALOGUE_P2P.md` gains that step.
- Optional, not in the critical path: `add-show --title … --year … --episodes list.json` appends a
  well-formed show entry with derived ids, so nobody hand-writes nested JSON.

Content sourcing is out of scope and is the real gate on shipping a show catalogue: the policy in
the README is public-domain works only, an episode list with a magnet per episode has to be
curated, and seeding is redistribution. The format supports per-episode torrents and season packs
so the choice of source cannot force a schema change later.

---

## Edge cases and scenarios

Every row names where the behaviour is enforced and, in brackets, where it is tested.

### Catalogue and release

| Scenario | Behaviour |
| --- | --- |
| Film entries in a catalogue that also has shows | Unchanged bytes, unchanged ids, unchanged parse path (`BundledCatalogueReleaseTest`, `CatalogParseTest`) |
| `"title"` written inside an episode or season | Count mismatch; publisher refuses with the hint; a hand-edited bundled file logs "CATALOGUE INCOMPLETE", is not cached, and `BundledCatalogueReleaseTest` fails the build (`CatalogContentRules`, `parseCatalogBytes`) [rules test, parse test] |
| Unknown `type` | Publisher refuses. App skips the entry, so a release with one fails rule 8 before install (`mapCatalogEntry`, `LayeredCatalog.prepare`) [parse test, verifier test] |
| Show with top-level `magnets`, film with `seasons`, empty seasons, empty episodes | Publisher refuses. App: the show is kept as unplayable so `titleCount` still matches; a film's `seasons` are ignored [rules test] |
| Duplicate season or episode numbers | Publisher refuses. App keeps both with distinct derived ids [rules test, ids test] |
| Episode id equal to a film id, or to another episode's | Publisher refuses. App falls back to the derived id for the second occurrence (`CatalogIds.assign`) [ids test] |
| Episode with no magnet and no pack | Allowed, counted as unplayable in the build summary, greyed in the UI, skipped by nothing (next-up stops on it and the end card says so) [rules test, `nextAfter` test] |
| Show renamed, year corrected, episode renumbered or moved | Pinned ids unchanged; progress and My List intact [ids test: "a renamed show keeps its pinned id"] |
| Episode removed from the catalogue | Its progress row goes dormant; Continue Watching skips it; watched status uses the remaining episodes (`observeContinueWatching`) [CW test] |
| Episode added to a season later | Next-up picks it up by `(season, number)`; a "watched" show becomes "unwatched" again, which is the correct answer [`nextUp` test] |
| Specials only | Listed, playable; next-up returns the first special because nothing else exists [`nextUp` test] |
| Episode number ≥ 100 | Id grows to three digits; ordering is numeric everywhere [ids test] |
| Unparseable `airDate`, `runtimeMinutes ≤ 0`, blank season name | Absent, absent, default name [parse test] |
| Non-Latin show title | Slug keeps letters and digits from any script, as for films [ids test] |
| Catalogue passes the size cap | `CatalogueReleaseWriter.write` refuses; `build` printed the percentage on every earlier run [writer test] |
| `episodeCount` present and wrong | Verifier rejects `TITLE_COUNT_MISMATCH` with a message naming episodes [verifier test] |
| `episodeCount` absent | Not checked [verifier test] |
| First show release reaches an old build | `NEEDS_NEWER_APP`, Settings says "needs a newer version of the app", the rejection is remembered per app build and retried after an update (`CatalogUpdater.refuse`, `check`) [updater test exists for `NEEDS_NEWER_APP`] |
| New build, old fetched release still installed | Loads as today; no shows until the next release [layered catalog tests] |
| Release swapped in while a show's details are open | Item and seasons re-emit together; vanished show → error state [repository generation test] |
| Release swapped in during an episode | Playback continues on the resolved sources; the end-of-episode step reads the new catalogue and shows "Back" if the show is gone [controller test] |

### Progress, Continue Watching, My List, backup

| Scenario | Behaviour |
| --- | --- |
| Two episodes of one show in progress | One card, the newest; Remove on it deletes that row and the other surfaces [CW test] |
| An episode finished | The show's card becomes the next episode "up next": no bar, placed in the row by when the episode was finished; crosses into the next season [CW test, `continueWatching` rules test] |
| The last episode finished, or a special | No card: nothing follows it [CW test] |
| Remove on an "up next" card | Progress is kept; the dismissal is stored in `show_state` against the finished episode and hides the card until the viewer finishes another episode, finishes the same one again, or starts one [CW test] |
| Remove on a part-watched episode's card, with an earlier episode finished | The row is deleted and the up-next card it would reveal is dismissed too, so Remove never swaps one card for another [CW test] |
| Progress row under a show id (should never exist) | Ignored by Continue Watching and next-up; `markWatched(showId)` expands rather than writing one [CW test, `nextUp` test] |
| Mark a show watched | All non-special episodes in one transaction; specials untouched [repository test] |
| Mark a show unwatched | Position 0 / watched false per episode (the film rule); the show leaves the WATCHED filter [repository test] |
| Mark a season watched / unwatched | Same, scoped to the season [repository test] |
| Library watched filter | WATCHED = every non-special episode watched; UNWATCHED = anything else, mirroring films [rules test] |
| A 5-minute short | 90 % is 4:30; `MIN_RESUME_POSITION_MS` and `MIN_REMAINING_MS` apply unchanged [existing `ResumeRulesTest`] |
| A 22-minute episode with 45 s of credits | Watched at 19:48 by the threshold and again at `STATE_ENDED`; both paths write the same row [controller test] |
| Backup taken on a build with shows, restored on the same build | Rows and show state round-trip; the file is format 2 and says so [backup test] |
| Format 1 backup (written before shows) restored | Imports as before, with no show state [backup test] |
| Backup restored into a catalogue missing some episodes | Dormant rows, as for films [backup test] |
| My List | Holds show ids; episodes are never offered [details screen] |
| Search history | Unchanged |

### Show details and browsing

| Scenario | Behaviour |
| --- | --- |
| Show with one season | No chip row |
| Show with 20 seasons, 500 episodes | Chips in a `LazyRow`, episodes in a `LazyColumn`; `keepNeighbourComposed` on every row [on device] |
| Episode without a name, without a still, without a runtime | "Episode N", the show backdrop, no runtime shown |
| Every episode unplayable | Primary button "Unavailable"; rows greyed; Menu still marks watched |
| Nothing playable on this device (`torrentAvailable == false`) | The film message, unchanged |
| First play ever (no consent) | Consent dialog from the episode row; accept plays that episode; decline stays [details VM test] |
| Back with a popup open | Closes the popup; second Back leaves |
| Focus after returning from the player | Restored to the episode that was played; the primary label already reflects the new progress [on device] |
| Long episode name | One line, ellipsised; overview two lines |
| Home genre row containing a show | Opens details; Menu toggles My List for the show [home VM test] |
| Continue Watching card for an episode | OK plays the episode; Menu removes; label "S1 E3 · 12m left" [home VM test] |
| Hero built from an episode card | Show art and title, label from the next-up action [home VM test] |
| Shows tab with no shows in the catalogue | Empty state "No shows match these filters"; the tab still exists |
| Library grid keys | `item.id`; grids never hold episode cards, so keys stay unique [existing behaviour] |

### Player and autoplay

| Scenario | Behaviour |
| --- | --- |
| Autoplay off | End card with Play / Back |
| Autoplay on, next episode playable | 10 s countdown, then `advance` |
| Any key during the countdown | Resets the unattended counter; countdown continues; OK on Play now advances at once |
| Back during the countdown | Leaves the player to the show's details |
| Three unattended autoplays | "Are you still watching?" instead of the fourth countdown; Continue resets and counts down; Stop leaves [controller test] |
| Last episode | End card: Back / Watch again |
| Next episode has no source | End card: "isn't available" / Back |
| Home pressed during the countdown | `ON_STOP` cancels the timer; nothing downloads; on return the card waits with Play now focused [screen test / on device] |
| Process killed during the countdown | Nothing started; Continue Watching has the finished episode marked watched; the next visit to the show offers the next episode as next-up |
| Consent withdrawn between episodes | `stream()` throws `NotConsented`; the existing consent dialog appears on the player; decline leaves |
| Free space below the reserve | `NoSpace` → "Not enough space" overlay with Retry / Back, as for films |
| Next swarm times out | Two attempts with "attempt 2 of 2" shown, then "Connection lost" with Retry / Back, as for films |
| Aspect, speed, subtitles on/off chosen on E1 | Kept for E2 (`advance` preserves them) [controller test] |
| Audio/subtitle track override from E1 | Carried on the reused player; if E2's track ids differ the silent-audio recovery re-selects, as it does across films today |
| Seeking to the end by hand | Same as a natural end |
| `STATE_ENDED` on a film | Today's behaviour, no card |
| Alexa / remote transport keys | Unchanged, except next: mid-episode it pauses and offers the next episode on a card whose "Keep watching" (or Back) returns to the episode; on a card it plays the next episode; nothing for films, the last episode or specials. Carried by the activity in front and by the media session for "Alexa, next" [controller test] |
| Display mode | Not re-matched on advance; torrents carry no frame rate |
| Screen-on | The existing rule (`isPlaying || isBuffering || isLoading`) keeps the screen on through buffering of the next episode; the 10 s countdown is far below any TV's sleep timeout |

### Engine, storage, network

| Scenario | Behaviour |
| --- | --- |
| Seeding after playback **off**, ten episodes in one evening | Each finished torrent is removed *with* its data (fix); the budget sees only live torrents and stays honest [coordinator test] |
| Seeding after playback **on**, ten episodes | Eight seeding handles at most; disk bounded by `enforceStorageBudget` (oldest-touched first, never the playing one); upload capped at 2 MB/s globally; at most eight torrents stay in the session, the oldest-touched non-streaming ones removed with their data [`HandleCapTest`] |
| Single-episode entry that actually points at a pack | Phase 1 hint picks the `S01E03` file; otherwise the largest, as today [matcher test] |
| Pack file names without an episode code (phase 2) | Ordinal fallback only when the file count equals the season's episode count; otherwise `NoPlayableFile` and the next source is tried [matcher test] |
| Pack contains `sample.mkv` and extras | Excluded by name and by size [matcher test] |
| Two episodes from one pack back to back (phase 2) | File switch under the mutex; the old file's pieces stay for seeding; the URL changes; a stale request gets 404 [fake-source server test, on device] |
| Pack eviction | Whole torrent, oldest-touched; documented |
| DHT blocked, extra trackers off, magnets stripped (future) | Discovery is weaker; that is why stripping waits and keeps two trackers |
| Offline | Home banner as today; playing an episode fails with the network error as a film does |
| Engine unavailable (no native library) | Details shows the existing message; nothing else changes |

### Lifecycle and updates

| Scenario | Behaviour |
| --- | --- |
| Crash guard restart mid-episode | Home; the episode is in Continue Watching from the last 10 s save |
| Task removed | `PlaybackService.onTaskRemoved` stops playback and the session; no countdown survives |
| App finishing | `MainActivity.onStop` stops the session; unchanged |
| Catalogue check runs while an episode plays | Unchanged; a swap re-emits screens; the player keeps its resolved sources |
| Old and new builds side by side (debug and release ids) | Independent databases and catalogues, as today |

---

## Performance budget

| Concern | Decision |
| --- | --- |
| Catalogue bytes | Stays under the cap; the build prints the percentage; tracker stripping is ready before it is needed |
| Parse / index | `episodeById` and `seasonsByShowId` are two maps built per generation beside `byId`; 40 000 small objects is inside what the film index already costs on the floor device |
| Home / Library flows | No new combine inputs. The kind filter and `ShowWatchedRules` are a pass over data already in hand; `isWatched` for a show is a lookup per episode, done once per emission |
| Continue Watching | One `playable` lookup per progress row and a group-by show; capped at 20 |
| Details | One `LazyColumn`; episode stills through `Artwork` with size hints; a season switch swaps a list reference |
| Player | Same `ExoPlayer` instance across an advance (releasing it churns the Fire TV's single decoder, see `leavePlayer`); the countdown is one effect |
| Engine | Phase 2 adds no polling; a file switch is a priority call on an existing handle |
| When closed | Zero. Nothing new runs outside a screen or a playback |

---

## Tests

| Where | What is proved |
| --- | --- |
| `core/model` | `ShowPlayRules.nextUp` in every state of the matrix above (fresh, mid-episode, after a finished episode, gap of an unplayable episode, everything watched, specials only, stray show-id row); `nextAfter` across a season boundary and at the end; `ShowWatchedRules`; `PlayActionResolver` for shows; `EpisodeFileMatcher` against real-world pack listings including samples and archives; `SourceSelector`: `DIRECT_ONLY` keeps torrents, `AUTO` prefers ≤ display height, own torrent beats pack on a tie |
| `core/catalogue` | Show and episode ids derive and pin deterministically and idempotently; a renamed show keeps its ids; every new content rule rejects with its message, including the `"title"`-in-episode hint; `episodeCount` verified when present and ignored when absent; a release fixture with one film and one show round-trips through writer → verifier. `TestCatalogues.show(seasons, episodesPerSeason)` joins the fixtures |
| `core/data` | A show parses to a `CatalogItem` with seasons and episode sources; an unknown `type` is skipped; a film's `seasons` are ignored; `playable()` resolves film and episode ids and not show ids; `sourcesFor(episodeId)`; Continue Watching yields one card per show, the newest, skips unknown and show ids; show-level and season-level mark watched are one transaction; the library kind and watched filters; `stopStreaming` with seeding off deletes data; `BundledCatalogueReleaseTest` extended so "pinned equals derived" also nulls episode ids before re-deriving |
| `core/player` | `onPlaybackEnded` for each row of the autoplay table; the unattended counter; `advance` stops the previous torrent and preserves aspect/speed/subtitles; progress saved under the advanced id; a show id given to `open` resolves to next-up |
| `core/torrent` (phase 2) | Against the fake `StreamSource`: a file switch re-keys the URL, a stale second segment gets 404, a read after a switch never crosses into the old file's pieces |
| `feature/*` | Home and details view models with the fakes already used by `MediaRepositoryGenerationTest`: hero labels from actions, Continue Watching plays the episode, details primary action, popup and consent flows |
| On device (`TESTING_PLAN.md`, new section 10) | see below |

### On-device checklist (section 10 of `TESTING_PLAN.md`)

1. Shows tab: grid scrolls to the last show with the D-pad; the header line counts shows.
2. Show details: header → chips → list → back up, never dead-ending; Back leaves; a 20-season
   fixture scrolls its chips.
3. First episode with sharing off: consent dialog, accept plays, decline stays.
4. Play E1, leave at 5 minutes: Home shows the episode card with "S1 E1 · Xm left"; hero says
   "Resume S1 E1"; details primary says the same; the list is focused on E1.
5. Finish E1 with autoplay on: countdown, E2 starts, E1 shows ✓ in the list afterwards.
6. Finish three episodes without touching the remote: "Are you still watching?".
7. Press Home during the countdown, return after a minute: the card waits, nothing downloaded
   (Settings sharing stats unchanged).
8. Last episode: end card; Watch again starts S1 E1.
9. Autoplay off: end card with Play / Back.
10. Seeding off: after three episodes, `Torrent` log shows each removed with its files and Settings'
    disk usage falls back.
11. Quality preference "Direct only" and "Auto": Play from the hero works; Auto never picks 2160p on
    a 1080p device.
12. Publish a show release with `--min-version-code` above the installed build: Settings reports
    "needs a newer version of the app"; install the new build: the release installs on the next check.
13. Swap a catalogue release while on show details and while an episode plays.
14. Phase 2: two consecutive episodes from one pack; seek within each; the contribution page names
    the pack.

---

## Docs to update

- `README.md`: the catalogue section (a show entry beside the film entry), the tab list, the legal
  section ("films" → "films and episodes", policy unchanged).
- `docs/CATALOGUE_P2P.md`: catalogue entries, the `"name"` rule, `episodeCount`,
  `--min-version-code` and `--strip-trackers` in the runbook.
- `core/model/MediaItem.kt`: the "films only" doc comment.
- `TESTING_PLAN.md`: section 10 above.
- `FIELD_ISSUES.md` or `PROD_READINESS.md`: the three fixes in Decisions, logged like the others.

---

## Order of work

### Phase 0 — fixes the reading found (ship on their own, before any show code)

1. [x] `SourceSelector`: `DIRECT_ONLY` excludes `HLS` only; `AUTO` prefers height ≤ display height.
   Tests. This changes film playback from the hero for the better and is independent of shows.
2. [x] `TorrentCoordinator.stopStreaming`: seeding off → `remove(infoHash, deleteData = true)`. Test.
3. [x] `HomeViewModel.playActionFor(card)` and both `markWatched(card)` call sites read the card's
   playable, not `card.item.id`. No behaviour change for films; a prerequisite for episode cards.

### Phase 1 — shows with per-episode torrents

No engine change beyond the file hint and display name; no Room migration. Ships a complete
feature for shows whose episodes each have their own torrent.

1. [x] `core/model`: `MediaKind`, `Season`, `Episode`, `MediaCard.episode/isWatched`, `ShowPlayRules`,
   `ShowWatchedRules`, `PlayActionResolver` for shows, `EpisodeFileMatcher`, `LibraryQuery.kind`,
   `Format.metaLine`. Tests.
2. [x] `core/catalogue`: DTOs, `CatalogIds` for shows and episodes, content rules, `episodeCount`,
   `TestCatalogues.show`. Tests including the release fixture.
3. [x] `core/data`: parsing, the two indexes, `Catalog.playable/seasons`, `MediaRepository` (kind
   filter, Shows row, hero actions), `ProgressRepository` (one card per show, transactional mark
   watched, season mark watched), `PlaybackInfoRepository` through `playable`. Tests.
4. [x] `core/torrent`: `stream(magnet, hint, displayName)`; largest-file fallback unchanged. Test.
5. [x] `core/ui`: SERIES badge, show subtitle, `EpisodeRow`, `SeasonChips`, `EndCard`/`CountdownCard`,
   accessibility strings.
6. [x] `feature/details`: `ShowDetails`, the view-model additions, the popup, the consent path. This
   is where the on-device focus time goes; the app's field history says focus is where TV bugs live.
7. [x] `app` + `feature/library` + `feature/home`: Shows tab and route, `LibraryMode.SHOWS`, the Shows
   row, hero labels, Continue Watching episode cards.
8. [x] `core/player` + `feature/player`: title/subtitle, `advance`, the end-of-episode table, the
   countdown with lifecycle gating, the unattended counter.
9. [x] `tools/catalog-publisher`: pinning and rules for shows, the size print-out, `--strip-trackers`
   (built, not applied). Build the first show release with `--min-version-code`.
   - [ ] **Operator task, not code:** build and publish the first release that contains a show, with
     `build --min-version-code <versionCode of the first app build with shows>`. It needs the private
     publisher seed and curated public-domain episodes, neither of which lives in this repository.
10. [x] Docs and the on-device checklist.

**Done when:** every row of the edge-case tables that is not marked phase 2 or 3 has a test or a
checklist item and passes; a show in the bundled catalogue browses, resumes, autoplays and marks
watched on a Fire OS 5 stick and on a Fire OS 7 or 8 device; an old build refuses the show release
with the documented message and the new build installs it.

### Phase 2 — season packs

1. [x] `FileSelection` in `core/model`; the matcher already exists.
2. [x] Engine: selectable file inside `StreamedTorrent`, URL with file index, 404 on mismatch,
   `cachedParts` over the selected file. Fake-source tests.
3. [x] Parsing: `packs` → one source per episode per pack; the selector tie-break.
4. [ ] Device verification of libtorrent 1.2.3 priority switching on Fire OS 5.
   **Device task, not code:** needs a Fire OS 5 stick and a seeded season pack; checklist row 15 in
   `TESTING_PLAN.md` section 10. The switching logic itself is covered on the JVM by
   `TorrentStreamServerTest` (a response keeps its own file across a switch; a stale file is refused),
   `FileChoiceTest` and `EpisodeFileMatcherTest`.

**Done when:** two consecutive episodes play from one pack on a stick, seeking within each works,
a stale URL is refused, and the contribution page names the pack sensibly.

### Phase 3 — polish that needs state or engine warm-up

1. [x] **Next-up in Continue Watching.** After a finished episode the next one appears as a card with
   no bar. Dismissal needs state: Room 2 → 3 with `show_state(showId PK, dismissedAfterEpisodeId,
   updatedAtMs)`, a migration, the `runMigrationsAndValidate` line in
   `TorfilxDatabaseMigrationTest`, and backup format 2 (format 1 still imports).
   **Not yet run on a device:** the instrumented `TorfilxDatabaseMigrationTest` (`:core:data:connectedDebugAndroidTest`). This machine could not hold an emulator and Gradle in memory at once. The migration SQL was checked column for column against the exported `3.json`.
2. [x] **Warm the next episode** when the countdown starts: `torrentCoordinator.stream(nextMagnet)`
   in the background (inside `MAX_ACTIVE_DOWNLOADS = 2`), `stopStreaming` if the viewer backs out;
   the Settings explanation line ships with this.
3. [x] A cap on managed torrents (oldest-touched non-streaming removed beyond eight), so a long binge
   with seeding on does not accumulate handles.
4. [x] `MEDIA_NEXT` on the remote opens the next-episode card.
5. [x] Episode-name search, if wanted. Decided yes, in its cheapest form: a query of three or more characters that is in an episode's name finds the show, listed after the title matches, its card naming the episode. No new result type; one lower-cased key per named episode.
6. [ ] Apply `--strip-trackers` once the catalogue passes 8 MB.
   **Not triggered yet, operator task:** the bundled `catalog.json` is 2.27 MB. `build` prints a warning naming `--strip-trackers` from 67 % of the 12 MB limit (about 8 MB); apply it to the next build after that warning first appears.

---

## Risks

| Risk | Mitigation |
| --- | --- |
| **Content.** A public-domain episode list with a working magnet per episode is curation work no code removes | Out of scope here; the format supports both source shapes so the choice can be made later |
| **The size cap.** Episodes are many; 12 MB is fixed by the floor device's heap | The size table; the build prints the percentage; stripping is built before it is needed |
| **Autoplay latency.** A new swarm takes 10–60 s to resolve, and a countdown that ends in a spinner feels broken | The buffering overlay already explains each stage; warm-up in phase 3 |
| **The `"name"` convention.** A hand-editor who writes `"title"` in an episode gets a count mismatch | The publisher's message says exactly this; the app never sees the release |
| **Old builds** stop receiving film additions once a show ships | `minVersionCode` is a one-time cost; sideloaders update; the alternative (shows decoding as unplayable films) is worse |
| **Pack eviction is per torrent** | Documented; the budget holds because `directorySize` counts every file |
| **libtorrent 1.2.3 file priorities** on a partly downloaded pack are unverified on Fire OS 5 | Phase 2 has its own device step and can slip without blocking phase 1 |
| **Focus** across header, chips, list and popups on the details screen | One `LazyColumn` and the existing helpers; budget on-device time; `FilmDetails` untouched so films cannot regress |
| **Continue Watching keys** | One card per show, keyed by `playableId`; tested |
| **Phase 0 changes film behaviour** (`AUTO` no longer picks 2160p on a 1080p device) | It is the intended behaviour and `CAP_1080P` already existed for it; called out in the release notes |
