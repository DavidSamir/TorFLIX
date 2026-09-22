# TORFILX

A Netflix-style Fire TV app whose library is a **signed catalogue of public-domain films and TV shows**, played
over BitTorrent. There is no media server and no backend: the app ships a catalogue, keeps it current
from the peer network, streams each title from its magnet link, and (with the viewer's consent) shares
what it has downloaded.

## What it does

- **10-foot UI** built with Jetpack Compose + `androidx.tv:tv-material`: hero banner, focus-scaled
  rows, details, on-screen-keyboard search, Movies and Shows grids, My List, Settings — all driven by
  the D-pad alone.
- **Shows as shows**: a show opens to its seasons and episodes; its button resumes or plays the
  next episode; an episode ending counts down into the next one (autoplay is a setting); Continue
  Watching holds one card per show. Specials are listed but never chained into.
- **Torrent streaming, not downloading**: only the video file is fetched, pieces are prioritised in
  play order with deadlines, and a loopback HTTP server feeds ExoPlayer while the download continues.
  Seeking into a not-yet-downloaded part re-prioritises and buffers instead of failing.
- **Explicit sharing consent**: off by default. The first attempt to play explains that streaming
  uploads to others and exposes your IP, with a real "Not now".
- **Storage budget**: never uses more than a configurable share of *free* space (default 50%, always
  keeping a 500 MB reserve), evicting oldest-touched titles first and never the one playing.
- **Local watch state**: Continue Watching, resume positions and My List live in Room on the device.
- **A catalogue that updates itself, without a server**: the maintainer signs a catalogue release and
  publishes a pointer to it in the BitTorrent DHT; with sharing on, the app finds it, downloads it as a
  small torrent, verifies every byte against the publisher key built into the APK, swaps it in while
  running, and helps seed it. The bundled copy is the fallback. See `docs/CATALOGUE_P2P.md`.

## Requirements

- JDK 17 or 21 (Android Studio's bundled JBR works)
- Android SDK with platform 37 and build-tools; `local.properties` pointing at it (`sdk.dir=...`)
- A Fire TV / Android TV device or emulator (arm64-v8a, armeabi-v7a or x86_64)

## Device support

Scope as of **August 2026**. The build's floor is deliberately low — `minSdk 22`, ARM-only release
ABIs, no Google Play Services anywhere in the graph, `leanback` declared optional — so the APK
installs on nearly anything Android-based with an HDMI port. Distribution is sideload-only; the app
is on no store.

| Constraint | Value | Why |
| --- | --- | --- |
| `minSdk` / `targetSdk` / `compileSdk` | 22 / 34 / 36 | API 22 is Fire OS 5, the oldest live Fire TV stick |
| Release ABIs | `armeabi-v7a` + `arm64-v8a` | every Fire TV is 32-bit ARM; arm64 covers Android TV boxes. `x86`/`x86_64` are debug-only (emulator) |
| Play Services | none | nothing depends on GMS, so uncertified and AOSP boxes work as-is |
| `android.software.leanback` | `required="false"` | installs on non-leanback Fire OS builds and on TV emulators |
| Signing | v1 + v2 + v3 | Fire OS 5 rejects a v2-only APK ("problem parsing the package") |

### The ten Android TV systems that matter, and where we stand

Ordered by installed base / OS share. Vendors do not publish per-brand Android TV unit counts, so
rows 3–10 are ranked on share and shipment evidence rather than exact numbers.

| # | System | OS base / API | Scale | Runs the release APK? |
| --- | --- | --- | --- | --- |
| 1 | **Fire TV sticks & cubes** — Fire OS 5/6/7/8 | Android 5.1 / 7.1 / 9 / 11 → API 22/25/28/30, all `armeabi-v7a` | 250M+ Fire TVs sold | ✅ **Proven** — the only tier tested on real hardware, all four API levels (see `TESTING_PLAN.md`) |
| 2 | **Fire TV smart TVs** — Omni, Insignia, Toshiba, Panasonic; Fire OS 8/14/16 | Android 11 / 14 / 16 → API 30/34/36 | Fire TV ≈ 17% of the US streaming-OS market | ✅ Expected, untested. Fire OS 16 is new — see the 16 KB note below |
| 3 | **Google TV built into sets** — TCL, Hisense, Sony Bravia, Philips, Sharp | Android 12 → 14 (TCL's 2026 line ships 14) | Android TV + Google TV: 300M monthly-active devices | ✅ Expected, untested |
| 4 | **Google's own players** — Google TV Streamer 4K, Chromecast with Google TV | Android 14 / Android 12→14 | Google TV is the #3 US streaming OS, ≈14% | ✅ Expected, untested |
| 5 | **Walmart onn. Google TV** — 4K Pro, 4K Plus | Android 12 → 14, Amlogic ARM | the US budget-volume box | ✅ Expected, untested |
| 6 | **Classic Android TV sets** — Sony, Philips, TCL 2016–2022 | Android TV 8–11 | most of the pre-Google-TV installed base | ✅ Expected — far above the API 22 floor |
| 7 | **NVIDIA Shield TV / Pro** | Android TV 11, Tegra X1+ (arm64) | still the enthusiast standard in 2026 | ✅ Expected — best-case hardware for torrent streaming |
| 8 | **Xiaomi TV Box S 3rd Gen / TV Stick** | Google TV 14, Amlogic S905X5M (arm64) | the main international box | ✅ Expected, untested |
| 9 | **Operator-tier boxes** — Airtel, Jio, Deutsche Telekom, Bouygues, pay-TV | Android TV 9–11 | large carrier-subsidised base | ⚠️ **Conditional** — the OS is fine, but many are locked: no unknown-sources, no ADB. Blocked by the operator, not by us |
| 10 | **Generic AOSP boxes** — MECOOL, X96, H96, Ugoos | Android 9–14, Amlogic ARM, no GMS | large grey-market volume | ✅ Expected — the zero-GMS design is exactly what makes these work |
| — | **Amazon Vega OS** — Fire TV Stick 4K Select (2025), Fire TV Stick HD (2026) | **Linux, not Android** | every *future* Fire TV Stick | ❌ **Impossible** — cannot run an APK at all; no sideloading, no Downloader |

**Nine of the ten run the current release APK**: one proven on hardware, seven expected but never
tested on a device, and one (operator boxes) gated by the operator's lock rather than by anything in
this build. Vega OS is listed as an eleventh row on purpose — it is not an Android TV system, but it
is where Amazon is moving all new hardware, so omitting it would overstate the app's reach.

### Beyond Android: the wider TV OS market, and what a port would cost

Only two of the ten biggest TV operating systems run Android — and they are the two we already ship
to. The rest are Linux or Darwin platforms where an APK is not a file the system recognises.

Shares below come from three datasets that disagree by design: CTVMA counts TV sets shipped and
**excludes HDMI dongles** (which structurally undercounts Roku, Fire TV and Google TV), Parks
Associates counts US broadband households, Omdia counts European shipments.

Port difficulty is scored 1–5 on how much of *this* codebase survives:

| Level | Meaning |
| --- | --- |
| **1 — free** | the existing release APK installs and runs; the work is verification |
| **2 — days** | same codebase; a build-config change plus on-device testing |
| **3 — months, engine survives** | new language and UI, but libtorrent's C++ still compiles and runs on-device |
| **4 — a year+, engine moves** | the runtime forbids third-party native code, so the torrent engine must be replaced or relocated to a remote gateway — a different product |
| **5 — not open to us** | no public SDK, or no install path we control; effort does not change the outcome |

| # | OS | Base | Global sets (CTVMA '24) | Other reach signal | App model | Port difficulty |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **Google TV / Android TV** | Android | 5.9% | 300M monthly-active devices; #1 in Europe at 32% | Android APK | **1** — this is us |
| 2 | **Roku OS** | Linux | 6.4% | #1 in the US at 28%; 100M+ streaming households | BrightScript + SceneGraph | **4** — no native modules and no raw sockets; then channel certification |
| 3 | **Samsung Tizen** | Linux | **12.8% (#1)** | #2 in the US at 23% | HTML5 web app (`.wgt`) | **4** — the web runtime bans native code; then Samsung store review |
| 4 | **Amazon Fire OS** | Android | 6.4% | 250M+ Fire TVs sold; ≈17% of the US | Android APK | **1** — primary target, tested on API 22/25/28/30 |
| 5 | **LG webOS** | Linux | 7.4% | ≈12% overall, 52% of the premium OLED tier | HTML5 / Enact (`.ipk`) | **4** — same shape as Tizen |
| 6 | **Hisense VIDAA** | Linux | **7.8% (#2)** | overtakes webOS in Europe during 2026 | HTML5 (VIDAA U SDK) | **5** — SDK access is partner-gated |
| 7 | **Apple tvOS** | Darwin | — (no sets) | ≈8% of US households | Swift / SwiftUI | **3** — technically the *easiest* non-Android port: libtorrent compiles for tvOS and AVPlayer replaces ExoPlayer. App Store review and the absence of any sideload path are what stop it, not the code |
| 8 | **Vizio SmartCast** | Linux | 5.8% | ≈18.5–20M monthly actives, US only | none published | **5** — no third-party developer programme exists at all |
| 9 | **Xperi TiVo OS** | Linux | 2.3% | Vestel OEM, the European growth play | HTML5 | **5** — partner-only |
| 10 | **Titan OS** | Linux | inside "other" | Philips and Sharp, Europe | HTML5 | **5** — partner-only |
| — | **Amazon Vega OS** | Linux | new | every *future* Fire TV Stick | React Native (Kepler SDK) | **5** — approval-gated programme, sideloading removed entirely |

Level 2 never appears above because it only occurs *inside* the Android rows: re-adding `x86_64` for
an x86 box, or rebuilding the native libraries 16 KB-aligned for Android 15+ arm64.

The recurring wall is distribution, not code. Every platform in rows 2–10 is store-gated with review,
and their developer modes are testing tools rather than distribution channels — a BitTorrent client
does not pass Roku's, Samsung's, LG's or Apple's certification whatever the catalogue holds. So a
port is not one rewrite; it is a rewrite into a channel that then rejects it. Sideload-only delivery
works on Android-based TV OSes and nowhere else, which is exactly the two rows we already reach.

### Watch items

1. **Vega OS erosion.** Every future Fire TV Stick is Vega. Our best-tested platform is a *shrinking*
   installed base, and reaching new Amazon hardware would mean a rewrite against Amazon's Kepler SDK,
   not a port.
2. **Google's sideload lockdown.** Developer verification (identity + a $25 registration) begins
   September 2026 in Brazil, Indonesia, Singapore and Thailand, expanding through 2027, and covers
   certified Google TV devices. Rows 3–8 are the growth path and they are what this hits.
3. **16 KB page sizes.** `libtorrent4j` is pinned to 1.2.3.0 — the only build whose native symbols
   resolve on API 22 — and it is almost certainly not 16 KB-aligned. Harmless on Fire TV (4 KB,
   32-bit) but a latent `.so` load failure on Android 15/16 arm64, i.e. Fire OS 16 and the newest
   Google TV sets. Verify before claiming row 2.
4. **x86 is release-stripped.** An x86 Android TV box or ChromeOS will not run a release build; the
   debug variant still does.

Sources for the market figures, which age quickly:
[Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html) ·
[Fire OS 16](https://www.aftvnews.com/android-16-is-coming-to-fire-tv-smart-tvs-via-newly-revealed-fire-os-16/) ·
[Vega OS has no sideloading](https://www.aftvnews.com/these-are-the-fire-tvs-that-dont-support-sideloading-or-downloader-due-to-vega-os-replacing-fire-os/) ·
[all future sticks are Vega](https://9to5google.com/2026/04/17/amazon-fire-tv-android-vega-switch/) ·
[250M Fire TVs sold](https://www.thurrott.com/smart-tech/smart-home/313174/amazon-has-now-sold-over-250-million-fire-tv-devices) ·
[Google TV at 300M devices](https://www.androidheadlines.com/2026/05/google-tv-300-million-devices-growth-2026.html) ·
[Google TV is the #3 US streaming OS](https://9to5google.com/2026/07/29/google-tv-is-reportedly-the-3-streaming-os-in-the-us/) ·
[smart TV OS share](https://www.sammobile.com/news/despite-samsungs-tv-dominance-tizen-os-trails-roku-fire-tv-and-google-tv/) ·
[Google TV brands](https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1721907871) ·
[Android 14 for TV](https://www.androidauthority.com/android-tv-14-features-3362883/) ·
[Xiaomi TV Box S 3rd Gen](https://www.mi.com/global/product/xiaomi-tv-box-s-3rd-gen/specs/) ·
[sideloading restrictions](https://www.androidauthority.com/how-android-sideloading-restrictions-may-work-3595355/) ·
[16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) ·
[CTVMA global OS ranking](https://www.nexttv.com/news/hisenses-vidaa-is-now-the-no-2-smart-tv-os-globally-behind-samsung-tizen-trade-group-says-chart-of-the-day) ·
[Parks Associates, US Q1 2026](https://www.parksassociates.com/blogs/pr-video-services-ott-pay-tv/roku-and-samsung-dominate-connected-tv-platforms) ·
[Omdia on emerging European TV OS](https://www.broadbandtvnews.com/2026/04/23/emerging-tv-os-platforms-to-take-30-share-in-europe-by-2030-says-omdia/) ·
[Vizio SmartCast actives](https://www.lightreading.com/video-streaming/walmart-tunes-up-smart-tv-play-with-2-3b-deal-for-vizio) ·
[TV app runtimes compared](https://www.forasoft.com/learn/video-streaming/articles-streaming/smart-tv-players-tizen-webos-roku-vidaa)

## Build and run

```bash
./gradlew :app:assembleDebug          # build
./gradlew test                        # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.torfilx.tv.debug/com.torfilx.tv.MainActivity
```

Sideloading onto a Fire TV Stick:

```bash
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## The catalogue

A new install starts from `core/data/src/main/assets/catalog.json`, described by
`catalog-manifest.json` beside it. From then on the app keeps the catalogue current from the peer
network: the maintainer publishes signed releases with `tools/catalog-publisher`, and every release is
verified against the publisher key built into the APK before it replaces anything. The format, the
trust rules and the publishing runbook are in [`docs/CATALOGUE_P2P.md`](docs/CATALOGUE_P2P.md).

Each entry looks like this. `id` is the key watch progress and My List are stored under; the
publisher pins it once, so renaming a title never loses anyone's progress:

```json
[
  {
    "id": "catalog-the-kid-1921",
    "title": "The Kid",
    "year": "1921",
    "image_url": "https://…/poster.jpg",
    "overview": "optional",
    "genres": ["Comedy"],
    "runtimeMinutes": 68,
    "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }]
  }
]
```

A show is an entry with `"type": "show"` and seasons of episodes in place of magnets. Every episode
has its own id, because that is what the viewer's progress on it is stored under:

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
      "episodes": [
        {
          "id": "show-the-twilight-zone-1959-s01e01",
          "number": 1,
          "name": "Where Is Everybody?",
          "runtimeMinutes": 25,
          "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }]
        }
      ]
    }
  ]
}
```

An episode's name is `"name"`, never `"title"`: the release format counts `"title"` keys to check
that nothing was lost, and it counts one per top-level entry. Season `0` is Specials. The full rules
are in [`docs/CATALOGUE_P2P.md`](docs/CATALOGUE_P2P.md).

Magnets are validated when the file loads: an entry whose `xt=urn:btih:` is not a 40-character hex
(or 32-character base32) info hash is dropped with a log line rather than failing later in the
player. Only content that may be freely redistributed belongs here — seeding *is* redistribution.

## Module layout

```
:app                 single activity, navigation, Hilt entry points
:core:model          domain types + pure rules (resume thresholds, source selection)
:core:common         typed errors, dispatchers, clock, logging (with export buffer)
:core:ui             theme, focus behaviour, cards/rows/hero/keyboard/consent dialog
:core:data           catalogue in use + updater, Room (progress, My List, search history), settings
:core:catalogue      pure JVM: catalogue release format, ids, Ed25519 verification, fetch sequence
:core:catalogue-swarm pure JVM: DHT pointer, release torrents, DHT state (libtorrent, shared with the tool)
:core:torrent        libtorrent4j engine, sequential streaming, loopback HTTP bridge, budget
:tools:catalog-publisher  desktop CLI: keygen, pin, build, verify, publish, fetch
:core:player         ExoPlayer factory, MediaSession service, playback state machine
:feature:*           home, library, details, search, player, settings
:core:testing        fixtures and fakes
```

## Notes for a real Fire TV

- Display frame-rate matching (24/50/60 Hz) is on by default; turn it off in Settings if your TV
  handles mode switches badly.
- Tunneled playback is enabled where the device reports support (Amazon's recommendation for 4K/HDR)
  and can be disabled in Settings if an AV receiver glitches.
- Media keys and Alexa transport commands work through `MediaSession`.

## Legal and content policy

**This project is provided for personal and educational use.** It is a BitTorrent client with a TV
interface: the application ships no media, hosts nothing, and operates no tracker or index service.
Every title it shows comes from `core/data/src/main/assets/catalog.json`, which is supplied by
whoever builds the app, or from a catalogue release signed with the publisher key that build trusts.

The catalogue committed to this repository is intended to hold **public-domain films and episodes** — works whose
copyright has expired and which may be freely copied and redistributed (for example Chaplin's *The
Kid*, 1921, and *The Gold Rush*, 1925).

Two things follow from how BitTorrent works, and they are worth stating plainly:

- Streaming a title over BitTorrent **also uploads it** to everyone else in that swarm. Downloading
  is receiving; seeding is *distributing*. Distributing a copyrighted film without permission is
  unlawful in most jurisdictions, and an "educational purposes" notice in a README does not change
  that.
- Your home IP address is visible to every other peer sharing the same title.

Because of this, sharing is **off by default** and cannot start until it has been explicitly enabled
through the consent screen, and it can be turned off again at any time in Settings.

Whoever populates `catalog.json` and builds or distributes the resulting APK is responsible for
ensuring they have the right to copy and redistribute those files. The maintainers of this codebase
are not responsible for catalogues assembled by third parties.

**Testing disclaimer:** This app is provided for testing and evaluation purposes only. It is not a
production streaming service, and it may contain unfinished features, bugs, or experimental behavior.
