# Field issues — reported from a real Fire TV, Aug 2026

Five symptoms reported after on-device use. Each is investigated to a root cause, fixed
defensively (the device cannot be attached to a debugger, so every plausible cause is addressed,
not just the most likely one), and covered by a test where a test can express it.

`[x]` = done and verified. `[ ]` = pending.

---

## A. Player controls do not work correctly

- [x] **A1. The auto-hide timer never restarts on input.** `LaunchedEffect(controlsVisible, …)` is
  keyed on state that does not change while the user is pressing keys, and `showControls()` writes
  `true` over `true`, which a `MutableStateFlow` does not re-emit. The overlay therefore disappears
  4 s after it opened no matter how much the user is interacting with it.
- [x] **A2. `return` instead of `return@Box`** in the sharing-consent branch abandons the rest of the
  composable, skipping the trailing `DisposableEffect`.
- [x] **A3. OK on a hidden overlay.** Investigated, left as is: `togglePlayPause()` already calls
  `showControls()`, so OK both acts and reveals, which matches the Fire TV player. No change made.
- [x] **A4. Focus is not restored deterministically** when the overlay hides/reopens; the scrubber
  re-grabs focus on every reopen, discarding where the user was.
- [x] **A5. Held keys are not rate-limited.** The root handler acts on every repeat event, so holding
  ← fires ~20 seeks/second into the accumulator.
- [x] **A6. Track pickers.** Investigated: they sit in the same `Column` as the button row, so
  one-dimensional focus search reaches them on ↓. No defect found, nothing changed — what actually
  made them usable is A1, the overlay no longer vanishing mid-interaction.

## B. Not all movies are visible (~25 of 2000)

Catalogue verified healthy: **2000 entries, 3390 valid magnets, 21 genres, 0 type errors, 0 unknown
keys** — so nothing is lost at parse time. The loss is in navigation/presentation.

- [x] **B1. The grid cannot be scrolled past the first screenful** with a D-pad: 6 columns × ~4
  visible rows ≈ the 25 titles reported. Focus search only finds composed items.
- [x] **B2. Home rows are capped** at 60 items and 12 genre rows, silently.
- [x] **B3. No visible total.** Nothing on screen says how many titles exist, so a truncated view is
  indistinguishable from a small catalogue.
- [x] **B4. List identity churn.** Investigated, not a live defect: `observeAllProgress()` emits
  only when a progress row is written, which happens during playback — not while the grid is being
  browsed. Left alone rather than adding caching that would buy nothing.
- [x] **B5. A debug-only catalogue was shadowing the real one.**
  `app/src/debug/assets/catalog.json` held **2 titles** and, through Android's asset merge, replaced
  the 2000-title catalogue in every debug build — silently, with nothing in the build output or the
  app to say so. The README tells people to sideload `app-debug.apk`, so anyone following it since
  it was committed (88ef19d, 2026-08-17) got a two-film app.

  Scope, checked rather than assumed: the shipped APKs are unaffected —
  `dist/torfilx-release.apk` (release variant) and `dist/torfilx-debug.apk` (built 2026-08-16,
  before the stub existed) both contain 2000 titles. Only debug builds made after 2026-08-17 are
  affected. Removed, and `app/build.gradle.kts` now **fails the build** if any variant source set
  reintroduces a catalogue override.

## C. "Network error" on opening a title, works after 3–4 retries

- [x] **C1. The stream server truncates the response body.** `serveBytes` writes headers with a
  `Content-Length`, then returns without writing any bytes when the file is not yet on disk. A
  short body is a hard I/O error to ExoPlayer → "Connection lost". This is the first request of
  every new title, which is exactly when the file does not exist yet.
- [x] **C2. A piece timeout mid-body truncates the same way.**
- [x] **C3. The torrent session starts cold on first play.** DHT bootstrap begins when the user
  presses Play, so the first attempt races a DHT with zero nodes; by the third retry it has peers.
- [x] **C4. No automatic retry.** A transient swarm failure is shown to the user immediately instead
  of being retried with backoff.
- [x] **C5. Wrong `Content-Type` and no file extension** on the loopback URL, so the extractor has to
  fall back to sniffing.

## D. Video plays but there is no audio, on many titles

- [x] **D1. No fallback when the audio codec has no decoder.** Extension renderers are off, so a
  track the device cannot decode (DTS, TrueHD, some AC3/EAC3, Opus/Vorbis on Fire OS 5) results in
  *no audio track being selected at all* — and ExoPlayer raises no error, it just plays silently.
- [x] **D2. Nothing detects or reports the silent state.** The player looks like it is working.
- [x] **D3. Preferred-audio-language filtering** can leave nothing selected on a file whose only
  track is undetermined or another language.
- [x] **D4. Tunneling is on by default** and is a known cause of silent playback on older Fire OS,
  and the capability check only inspects *video* decoders.
- [x] **D5. The reused player carries stale track overrides** from the previous title.

## E. Stress-test the whole thing (cache and all)

- [x] **E1.** Stream-server tests: truncation, ranges, 416, slow/missing pieces, HEAD, concurrency.
- [x] **E2.** Full-catalogue load test (all 2000 entries, not a fixture of three).
- [x] **E3.** Storage budget and eviction ordering are covered by the existing `StorageBudgetTest`.
  Reviewed, not expanded.
- [ ] **E4.** Repeated open → play → leave cycles. **Not done.** It needs an instrumented test on a
  real device or emulator — a JVM unit test cannot hold an ExoPlayer — and the machine this was
  built on could not keep a Gradle daemon alive, let alone an emulator. The specific stale-state
  hazard it would have caught is addressed directly instead (D5: track overrides cleared per title).

### Content gaps found while verifying the catalogue

All 2000 titles are present in the catalogue **and in both APKs** (verified by unzipping them).
Nine carry magnets the validator rejects, for three distinct reasons in the source data:

| Titles | Problem |
| --- | --- |
| Megamind, Walking with Dinosaurs 3D | no magnets at all |
| Baby Driver | magnets are unsubstituted scraper templates, `{$tt.info_hash}` |
| Mile 22, Happy Death Day 2U, The Longest Ride, The Chronicles of Narnia, Boss Level, Good Boys | every magnet has a **41-character** info hash: a 40-hex hash with a `1` prepended |

The 41-character case is a generator bug, not a validator one: all 17 of those magnets begin with
`1`, which random corruption would not produce. The true hash is almost certainly the trailing 40
characters, but "almost certainly" is not good enough — a wrong info hash resolves to nothing and
costs the viewer a full metadata timeout before failing, which is worse than an honest "no playable
version". Left failing loudly, and pinned by a test so the number cannot grow unnoticed.

---

## F. Catalogue read path — full audit

Every stage between the JSON file and a card on screen, and what could silently lose titles at each.
The rule applied throughout: **a failure must never look like a small library.** Either everything
loads, or the app says so.

| # | Stage | How it could lose titles | Status |
| --- | --- | --- | --- |
| F1 | `core/data/src/main/assets/catalog.json` | wrong or truncated source file | ✅ Verified 2000 entries, unique ids, 21 genres, 0 type errors; pinned by `CatalogFullLoadTest` |
| F2 | Variant asset merge | a `src/<variant>/assets/catalog.json` silently replaces it | ✅ Offender removed; the app build now **fails** if one reappears |
| F3 | APK packaging | asset compressed → inflated through `AssetManager`'s buffer, capped at 1 MB on older Android; ours was 2.1 MB **DEFLATEd** | ✅ `noCompress += "json"` — now `Stored`, verified in the APK. No inflater in the path at all |
| F4 | `assets.open()` → parser | the stream is not a file: short reads, and it can fail part-way through a large entry | ✅ Read fully with `readBytes()` first, then parse from memory. Either every byte arrives or it throws — no silent middle state |
| F5 | `decodeToSequence` short reads | a reader mistaking a short read for end-of-input truncates the library | ✅ Ruled out by test: parses all 2000 through a stream that returns **1 byte at a time**, and at 512/4k/8k/16k/64k |
| F6 | Resilient parse loop | keeps what it decoded and stops **quietly** on error — right for one bad entry, wrong as a silent outcome | ✅ Now counts what the file declares (independently of the parser) and logs `CATALOGUE INCOMPLETE: parsed N of M` at error level |
| F7 | `BundledCatalog` cache | a bad read was cached for the life of the process — one failure at startup meant a broken library until force-stop | ✅ An incomplete result is no longer cached; the next access retries |
| F8 | `MediaRepository.views` cache | same hazard one layer down: sorted/grouped views cached from an incomplete catalogue | ✅ Not cached while the catalogue is incomplete |
| F9 | `mapCatalogEntry` | drops entries with no title; ids disambiguated on collision | ✅ Reviewed — 0 dropped in the shipped file; per-entry skips are logged |
| F10 | Home row caps (60/row) | a capped row is visually identical to a complete one — this is what made "Animation" look like 2 films instead of a preview of 217 | ✅ Rows now carry the real total and the header reads `60 of 217 · all in Movies` |
| F11 | Genre row count cap | was 12 of 21 genres, silently | ✅ Raised to 32 — every genre gets a row |
| F12 | Search limit | capped at 60 results with no indication | ✅ Raised to 500 |
| F13 | Lazy-list focus dead-end | D-pad cannot reach uncomposed items, so every list stopped at the viewport edge | ✅ One shared helper applied to the browse grid, home rows, the home row column, and search results |
| F14 | No way to tell what loaded | count, version and completeness were invisible on the device | ✅ Movies tab shows `N titles · v0.2.x`; an incomplete load is stated outright |

### What this means

F3 and F4 are the two that could actually have produced "the first few and then nothing" on a Fire
TV while parsing perfectly on a desktop — a compressed 2.1 MB asset inflated through a capped buffer,
read as a stream that can stop early. Both are now removed from the path rather than worked around,
and F6/F7/F8 mean that if anything ever does go wrong again, the app reports it instead of quietly
serving a fraction of the library.

---

## G. Found while adding TV shows (Sept 2026)

Three defects in the film code, found by reading it end to end for `docs/TV_SHOWS_PLAN.md`. Episodes
would have made each of them the common path, so they were fixed first and on their own.

- [x] **G1. "Direct only" broke automatic playback.** `SourceSelector` kept only `SourceKind.DIRECT`
  under that preference, and every catalogue source is `TORRENT`, so Play from the hero or Continue
  Watching failed with "format not supported". It now rules out only the transcode (`HLS`); a torrent
  is the file itself and counts as direct play.
- [x] **G2. "Auto" picked the largest file.** Torrent sources carry no codec, so every one passed the
  decoder check and the comparator then took the tallest: a 2160p file on a 1080p stick, the slowest
  possible start over a swarm. Sources taller than the display are now demoted (not excluded), and
  "Max 1080p" lowers that ceiling as before.
- [x] **G3. With seeding off, finished titles left unevictable data.** `TorrentCoordinator.stopStreaming`
  removed the torrent but kept its files; a torrent no longer in the session is invisible to the storage
  budget, so an evening of titles piled up gigabytes until the next start, and on a small stick the
  free-space guard then paused every download. The torrent is now removed with its data; no resume data
  is kept, so those files could never have been reused.

Tests: `SourceSelectorTest` (torrent cases), `TorrentCoordinatorTest`.

---

## Done log

_(most recent first)_
