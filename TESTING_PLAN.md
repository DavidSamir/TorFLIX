# Torfilx — Compatibility & Playback Verification Plan

_Last updated: 2026-08-17_

This document captures what we have **proven**, what we have **ruled out**, the full
**Fire TV device matrix** the app must run on, and the **test cases** that remain — so the
work can continue without re-deriving any of it.

---

## 1. The headline finding (read this first)

**The Android emulator cannot test BitTorrent playback, and never could.**

Measured, not assumed:

| Probe (on the API 36 x86_64 emulator) | Result |
| --- | --- |
| `ping 8.8.8.8` | **100% packet loss** |
| DHT nodes after 20s (`session.dhtNodes()`) | **0** |
| HTTPS poster fetch (TCP 443) | **works** |
| Torrent metadata (DHT + UDP trackers) | **times out every time** |

QEMU's user-mode networking forwards **TCP only**. BitTorrent peer discovery is **UDP**
(DHT is UDP; our test magnets carry `udp://` trackers exclusively). So on every emulator:

- Posters load (TCP) → the UI looks healthy.
- No peer is ever found (UDP) → **every** magnet dies with `MetadataTimeout: No peers responded`.

This is a property of the **sandbox**, not the app. It is why three well-seeded magnets
(Big Buck Bunny, Cosmos Laundromat, The Gold Rush) all failed identically regardless of
libtorrent version. **Playback must be verified on real hardware or a network-permissive
device.** More emulator runs cannot answer the playback question.

> The earlier apparent success with libtorrent 2.1 does **not** contradict this: it is not a
> reliable comparison, because 2.1 cannot be used anyway (see §3) and that run was not
> reproduced under the same UDP-blocked conditions.

---

## 2. Fire TV device matrix (what the APK must support)

Sources: Amazon Fire TV device specifications (developer.amazon.com), Aug 2026.

**Every Fire TV device ever shipped is 32-bit userspace (`armeabi-v7a`).** No Fire TV is
arm64. This is the single most important compatibility fact for our native library.

### Streaming sticks / players

| Device | Year | Fire OS | Android / API | SoC | ABI |
| --- | --- | --- | --- | --- | --- |
| Fire TV Stick — 1st Gen | 2014 | 5 | 5.1 / **22** | Broadcom Capri 28155 | armeabi-v7a |
| Fire TV Stick — 2nd Gen | 2016–19 | 5 | 5.1 / **22** | MediaTek 8127D | armeabi-v7a |
| Fire TV Stick — Basic Edition | 2017 | 5 | 5.1 / **22** | MediaTek 8127D | armeabi-v7a |
| Fire TV Stick 4K — 1st Gen | 2018 | 6 | 7.1 / **25** | MTK8695 | armeabi-v7a |
| Fire TV Stick — 3rd Gen | 2020 | 7 | 9 / **28** | MT8695D | armeabi-v7a |
| Fire TV Stick Lite — 1st Gen | 2020 | 7 | 9 / **28** | MT8695D | armeabi-v7a |
| Fire TV Stick 4K Max — 1st Gen | 2021 | 7 | 9 / **28** | MTK8696 | armeabi-v7a |
| Fire TV Stick 4K — 2nd Gen | 2023 | 8 | 11 / **30** | MTK8696D | armeabi-v7a |
| Fire TV Stick 4K Max — 2nd Gen | 2023 | 8 | 11 / **30** | MTK8696T | armeabi-v7a |
| Fire TV Stick HD | 2024 | 7 | 9 / **28** | MT8695D | armeabi-v7a |
| Fire TV Stick 4K Plus | 2025 | 8 | 11 / **30** | MTK8696D | armeabi-v7a |
| Fire TV Stick HD | 2026 | **Vega OS** | N/A (not Android) | MediaTek MT8698D | — |
| Fire TV Stick 4K Select | 2025 | **Vega OS** | N/A (not Android) | MT8698 MCM | — |

### Boxes / Cubes / pendants

| Device | Year | Fire OS | Android / API | ABI |
| --- | --- | --- | --- | --- |
| Fire TV — 1st Gen | 2014 | 5 | 5.1 / **22** | armeabi-v7a |
| Fire TV — 2nd Gen | 2015 | 5 | 5.1 / **22** | armeabi-v7a |
| Fire TV — 3rd Gen (pendant) | 2017 | 6 | 7.1 / **25** | armeabi-v7a |
| Fire TV Cube — 1st Gen | 2018 | 6 | 7.1 / **25** | armeabi-v7a |
| Fire TV Cube — 2nd Gen | 2019 | 7 | 9 / **28** | armeabi-v7a |
| Fire TV Cube — 3rd Gen | 2022 | 7 | 9 / **28** | armeabi-v7a |

### What this means for the build

- **minSdk 22** is correct and necessary — the oldest live sticks are API 22, and the
  user's own device is a Fire TV Stick 2nd Gen (Fire OS 5.2.9.5, API 22).
- **`armeabi-v7a` is the ABI that matters.** `arm64-v8a` is never used by a Fire TV; we ship
  it for forward-safety and emulators only. `x86`/`x86_64` are emulator-only.
- **API levels to cover: 22, 25, 28, 30.** Four distinct Fire OS generations (5, 6, 7, 8).
- **Vega OS devices (2025–26) are out of scope** — they are not Android and cannot run an
  APK at all. Nothing we do reaches them.

---

## 3. Why libtorrent version is locked to 1.2.x (proven)

| libtorrent4j build | Loads on API 22? | Reason |
| --- | --- | --- |
| 2.1.0-27 … -39 | ❌ No | needs `getifaddrs` (API 24), `aligned_alloc`+`getentropy` (API 28) as **strong** symbols |
| 2.0.x | ❌ No | same class of API 24/28 symbols |
| **1.2.x (1.2.3.0)** | ✅ **Yes** | all undefined symbols resolve against API 22 libc; only `getentropy` is referenced, and it is **weak** (linker tolerates null) |

Verified by dumping every undefined symbol in each ABI's `.so` against the NDK's API 22
stubs. **1.2.3.0 is the only viable engine**, and it publishes an `x86` artifact (2.x does
not), which is what lets the app even *load* on an emulator. Going back to 2.x is not an
option — it cannot load on any Fire TV.

---

## 4. Root-cause work completed on the play path

All committed to the 1.2.3.0 migration; each was a real defect found by reading the
libtorrent4j 1.2 source, not guessing:

1. **API surface** — `download(magnet, dir)` (flags dropped), `Sha1Hash(hexString)`
   (no `from_hex`). ✅ builds & runs.
2. **DHT not auto-started** — 1.2's `start(SessionParams)` does **not** start the DHT from
   `enable_dht` alone. Added explicit `session.startDht()` + `isDhtRunning()` guard. ✅
3. **DHT bootstrap routers** — set `dht_bootstrap_nodes` and `listen_interfaces` explicitly,
   because the settings pack handed to `SessionParams` is the *whole* config (unset = empty). ✅
4. **Torrent added in PAUSED state** — this is the subtle one. 1.2's `download()` strips
   `AUTO_MANAGED` but leaves libtorrent's default `PAUSED` flag, and with no auto-manager the
   torrent **never announces**. libtorrent4j's own `fetchMagnet()` calls `th.resume()` for
   exactly this reason. We now `resume()` the handle the instant it exists. ✅
5. **Diagnostics** — startup logs `Torrent engine available (abi=…, api=…)`; the play path
   logs DHT node count and `Torrent added and resumed`. These make a real-device failure
   readable in one glance.

**Status:** all of the above are **code-complete and load-verified**, but **playback itself
is still unproven** because the only test bed available (emulator) has no UDP. On the
emulator the new logs read exactly as expected for a UDP-blocked host: `DHT nodes after
20000ms: 0`, torrent added and resumed, then metadata timeout.

---

## 5. Confidence assessment

| Claim | Confidence | Basis |
| --- | --- | --- |
| App launches on API 22/25/28/30 | **High** | executed on all four |
| Native `libtorrent4j.so` loads on those APIs | **High** | executed + full symbol analysis |
| ARM binary is CPU-compatible with Fire TV | **High** | ELF/ABI/symbol analysis (armeabi-v7a, VFPv3, NEON) |
| UI/artwork/navigation/consent all work | **High** | executed, screenshotted |
| DHT + resume fixes are correct | **Medium** | derived from libtorrent4j 1.2 source; logs behave as predicted, but no peer discovery possible in emulator |
| **Video actually plays on a Fire Stick** | **Unverified** | requires real UDP; emulator cannot test |

---

## 6. Test cases to run on real hardware

Two public-domain films the user supplied (used only in `app/src/debug/assets/`, never in
the shipped catalogue):

- **The Kid (1921)** — `0697BC07EBC5914085C2A3BCE646509086BF6265`
- **The Gold Rush (1925)** — `BB5A1F6D17D3F8E01DE20D42FB9860157A24456C`

### Matrix (each cell = one deterministic run)

| # | Device / API | Title | Expected |
| --- | --- | --- | --- |
| 1 | Fire TV Stick 2nd Gen (API 22) | The Kid | frames render within ~2 min |
| 2 | Fire TV Stick 2nd Gen (API 22) | The Gold Rush | frames render |
| 3 | API 22 | resume after leaving player | resumes at saved position |
| 4 | API 22 | play with sharing **declined** | clear "cannot play" message, no crash |
| 5 | API 22 | play with no network | typed error, no crash |
| 6+ | API 25 / 28 / 30 (if available) | The Kid + The Gold Rush | frames render |

### What to capture per run (the app already logs these)

```
adb connect <fire-tv-ip>:5555
adb logcat -c
adb shell am start -n com.torfilx.tv/com.torfilx.tv.MainActivity
# play a title, then:
adb logcat -d | grep Torfilx
```

Key lines and how to read them:

| Log line | Meaning |
| --- | --- |
| `Torrent engine available (abi=armeabi-v7a, api=22)` | native load OK |
| `DHT nodes after 20000ms: N` | **N > 0 on real hardware = the emulator was the only blocker.** N = 0 on real hardware = a genuine DHT bug to chase |
| `Torrent added and resumed; waiting for metadata` | the PAUSED fix is in effect |
| `state=PLAYING` (MediaSession) | **success — video is playing** |
| `MetadataTimeout: No peers responded` | no peers found (expected on emulator; a real bug on hardware) |

### Decision tree after the first real-hardware run

- **`state=PLAYING` appears** → playback works. Fill in the matrix, then ship the release
  build (with the user's 2000-title catalogue).
- **`DHT nodes > 0` but still `MetadataTimeout`** → DHT is healthy; problem is tracker/peer
  connection. Next step: log `tracker_error_alert` / `tracker_reply_alert` from the alert loop.
- **`DHT nodes = 0` on real hardware** → real DHT bug. Check UDP listen-port binding and
  whether the router is filtering; consider adding an explicit DHT bootstrap with
  `dhtAnnounce` after nodes appear.

---

## 7. Fallbacks (only if 1.2.x proves unworkable on hardware)

1. **Add more/healthier trackers to the catalogue magnets** — the supplied magnets use only
   `udp://` trackers, several long-dead (coppersurfer, leechers-paradise, rarbg). Adding
   live `udp://` + `http://` trackers gives peer discovery a path that does not depend on
   DHT at all. **Cheapest first thing to try if DHT is slow on hardware.**
2. **Pure-JVM BitTorrent engine** (e.g. ttorrent-style) — removes the native ABI problem
   entirely; significant rewrite of `:core:torrent`.
3. **Raise the floor to Fire OS 6+ and use libtorrent 2.x** — abandons the user's own 2nd
   Gen stick and all API 22 hardware. Last resort.

---

## 8. Current repo state

- `app/src/debug/assets/catalog.json` — the two public-domain test films; **debug variant
  only**. Release builds use the real catalogue automatically.
- Release/CI: **v0.1.7 is the published tag**, shipping the user's 2000-title catalogue.
- The DHT-start, bootstrap-nodes, and PAUSED-resume fixes are **local, uncommitted** — they
  are correct by source analysis but not yet playback-verified, so they have not been
  released.
- The user's `core/data/src/main/assets/catalog.json` has **never been modified** (0 diffs).

---

## 9. Catalogue over the peer network (real hardware)

Whether the emulator reaches the DHT depends on the host network (section 1 found no UDP; on 2026-09-12
the development emulator reached 78 DHT nodes and completed a lookup), so the catalogue transport is
proven two other ways: automatically, on a private libtorrent network on a desktop JVM with one
process per session (`process/SwarmNetworkTest`, plus `SingleSessionTransportTest`), and by hand on
a real Fire TV as below. See `docs/CATALOGUE_P2P.md` for the format and the tool.

### Setup

1. On a PC with working UDP, build and publish a test release from a test key:
   ```
   catalog-publisher keygen --out C:/keys/test-publisher.seed
   catalog-publisher build --catalog core/data/src/main/assets/catalog.json --version 900 \
     --seed C:/keys/test-publisher.seed --out build/catalogue-test
   catalog-publisher publish --release build/catalogue-test/torfilx-catalogue-900 \
     --seed C:/keys/test-publisher.seed --salt torfilx-catalog-v1 --hours 2
   ```
2. Install a debug build that trusts that key:
   `./gradlew :app:installDebug -Ptorfilx.cataloguePublisherKeys=<printed public key>`
3. `adb logcat -c`, open the app, turn on sharing.

### Matrix

| # | Action | Expected |
| --- | --- | --- |
| 1 | Wait 20 s after sharing turns on | `CatalogUpdate: Checking…`, then `Catalog: Catalogue 900 is now in use`; Movies header shows `catalogue 900` with no restart |
| 2 | Force-stop and reopen | `Catalog: Using the downloaded catalogue 900 … verified`; still 900 |
| 3 | Publish from a *different* seed under the same salt | Nothing changes: that pointer is under a key the build does not trust |
| 4 | Settings → Use the built-in catalogue | Header back to the bundled version; the next check reports release 900 as refused |
| 5 | Settings → Find peers with DHT off → Check for a new catalogue | "Turn on Find peers with DHT…", no lookup in the log |
| 6 | Sharing off | Session stops; no `CatalogUpdate` lines at all |
| 7 | Stop `publish` for 3 h, then Check | "No catalogue has been published…" (the pointer expired); the catalogue in use is kept |
| 8 | Second launch of the day | `Torrent: DHT state restored from the last session`; the check finds nodes within seconds |
| 9 | Press Home, wait 30 s, reopen the app, play a film | `Torrent session stopped`, then `Torrent session started` on return. If instead `libtorrent did not finish shutting down within 20 s` appears, the next start may log `still shutting down; not starting a new one yet`; a retry a few seconds later must work and the app must never freeze |

### Reading a failure

- `DHT found no nodes` on hardware: UDP is blocked on that network, exactly as for films.
- `NOT_FOUND` with nodes > 0: the publisher was not running in the last two hours, or keys differ.
- `NO_PEERS`: the pointer resolved but nobody seeded the torrent; keep `publish` running.
- `REJECTED (BAD_SIGNATURE)`: the release was built with a different seed than the pointer's key.

## 10. TV shows (real hardware)

Everything below is covered by unit tests on a desktop JVM (`ShowRulesTest`, `EndOfEpisodeTest`,
`CatalogShowParseTest`, `ShowRepositoryTest`, `DetailsViewModelTest`, `HomeViewModelTest`,
`LibraryViewModelTest`, `EpisodeAutoplayTest`, `PlaybackControllerShowTest`). What a JVM cannot show is
focus on a remote, a real swarm between episodes, and the app leaving the screen, so those are checked
by hand on a Fire OS 5 stick and on a Fire OS 7 or 8 device.

### Setup

1. Build a test release that contains one show of two seasons with a working magnet per episode, from
   a test key, with `--min-version-code` set to the build under test (see `docs/CATALOGUE_P2P.md`).
2. Install a debug build that trusts that key, as in section 9, and let the release install.
3. `adb logcat -c`, then read along with `adb logcat -d | grep -E "Torfilx/(Playback|Catalog|Torrent)"`.

### Matrix

| # | Action | Expected |
| --- | --- | --- |
| 1 | Shows tab: scroll the grid to its last show with the D-pad | Never dead-ends; the header line counts shows ("N shows · v… · catalogue N") |
| 2 | Open the show: header → season chips → episode list → back up; Back | Focus never gets stuck or lost; Back leaves; a 20-season show scrolls its chips |
| 3 | First episode with sharing off | The consent dialog; accept plays, "Not now" stays on the show |
| 4 | Play S1 E1, leave at 5 minutes | Home: Continue Watching card "S1 E1 · Xm left"; hero says "▶ Resume S1 E1"; the show's button says the same; returning from the player puts focus on that episode's row |
| 5 | Let S1 E1 finish with autoplay on | "Next episode in 10 s"; S1 E2 starts in place; S1 E1 shows ✓ in the list afterwards; the log shows the old torrent stopped |
| 6 | Let three episodes autoplay without touching the remote | The fourth ending asks "Are you still watching?"; Continue counts down to the next; Stop leaves |
| 7 | Press Home during the countdown, return after a minute | The card waits with Play focused and no timer; nothing downloaded meanwhile (Settings sharing figures unchanged) |
| 8 | Finish the last episode | "You've reached the end"; Watch again starts S1 E1 from the beginning |
| 9 | Autoplay off in Settings, finish an episode | The next-episode card with Play and Back; nothing starts by itself |
| 10 | "Keep seeding after playback" off, watch three episodes | Each finished torrent is removed with its files (`Torrent` log); Settings' disk usage falls back |
| 11 | Quality preference "Direct only", then "Auto", on a 1080p stick | Play from the hero works under both; Auto never picks a 2160p source when a smaller one exists |
| 12 | Publish the show release with `--min-version-code` above the installed build | Settings: "A newer catalogue needs a newer version of the app"; the films keep playing; installing the newer build installs the release on its next check |
| 13 | Swap in a catalogue release while on the show's details, and while an episode plays | The details follow the new catalogue; playback carries on; the episode's end reads the new catalogue |
| 14 | Menu on an episode with two qualities; Menu on a season chip | "Play in 720p/1080p" and Mark watched; Mark season watched/unwatched; focus returns to the row or chip afterwards |
| 15 | (Season packs, phase 2) Two consecutive episodes from one pack | Each plays its own file; seeking works in both; the contribution page names the pack |
| 16 | Finish S1 E2 and go Home; Menu → Remove on the show's card; then finish S1 E3 | Continue Watching shows "S1 E3 · <name>" with no bar after S1 E2; Remove hides it and it stays hidden across a restart; finishing S1 E3 brings it back as S1 E4 |
| 17 | Upgrade from the previous release build with progress saved, then back up and restore | The app opens with progress intact (Room 2 → 3); the backup file says `"version":2`; a backup file from the previous build restores |
| 18 | Autoplay on, a new swarm for the next episode: watch the log through the countdown, then once more pressing Back during it | "Warming … during the countdown" at the card; the next episode starts with little or no "looking for peers"; after Back the warmed torrent stops (seeds, or is removed with seeding off) |
| 19 | Mid-episode, press the remote's next key; press Back; press next twice; say "Alexa, next" | The episode pauses behind a "Next episode" card; Back or "Keep watching" resumes it where it was; next twice plays the next episode; Alexa does the same as the key |
| 20 | Search for three or more letters of an episode's name | The show appears after any title matches, its card reading "S1 E3 · <episode name>"; it opens the show |
