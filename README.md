# Amperfy4Android

An Android music player for Subsonic and Ampache servers, ported from
[Amperfy for iOS](https://github.com/BLeeEZ/amperfy).

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange.svg)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-brightgreen.svg)](https://developer.android.com/about/versions)

> **Unofficial port.** This project is an independent Android port of Amperfy for iOS
> (baseline: iOS release 2.1.0). It is not affiliated with, maintained by, or endorsed by the
> upstream Amperfy project or its author, Maximilian Bauer. Please report problems with this
> app here, not to the upstream project.

## Features

- **Servers**: Subsonic / OpenSubsonic (MD5 token + salt authentication, with automatic
  fallback to legacy plain-text authentication for old servers) and Ampache (API 5, XML).
- **Multiple accounts**: log in to several servers or users, switch between them, and log
  out per account. Library data, downloads and settings are isolated per account.
- **Home**: configurable sections (random, recently played, newest, favorites, …) with a
  "Home Preferences" editor for visibility and order.
- **Library**: artists, albums (list or grid), songs, favorites, newest and recently played
  albums, genres, radios, podcasts, and three-level directory browsing. The library entries
  can be edited in place (visibility and drag-reorder).
- **Search**: server-side search plus local playlist search, results grouped into artists,
  albums, playlists and songs, with All / Cached scope and search history.
- **Playlists**: list, detail, edit (rename, drag-reorder, multi-select delete), an
  "add songs" library browser with duplicate detection, and a playlist selector for adding
  songs, albums, artists or the current queue.
- **Player**: large / compact player with shared-element transitions, artwork / lyrics /
  visualizer views, ratings and favorites, playback rate, sleep timer, skip buttons, manual
  playback mode, and per-item playback progress memory for songs and podcast episodes.
- **Queue**: context queue + user queue + previous queue with drag-reorder across sections,
  repeat (off / all / single) and shuffle, plus a separate podcast queue.
- **Audio chain**: 10-band equalizer with presets, ReplayGain loudness normalization from
  server tags, and an audio visualizer with four views.
- **Lyrics**: OpenSubsonic structured lyrics with line highlighting and auto-scroll, cached
  on disk for offline use.
- **Scrobbling**: "now playing" reports, play-time threshold detection, and an offline queue
  that is retried when the network returns.
- **Downloads and offline playback**: parallel downloads for songs and podcast episodes,
  persisted download records that resume after restart, a Downloads screen (retry, cancel,
  clear), cache limits, and optional auto-caching of the latest songs and podcast episodes.
- **Background playback** with a media session and notification controls.
- **State persistence**: playback state and queues are restored after a restart.
- **Swipe actions and long-press previews** on list rows (queue, play, download, remove
  from cache, add to playlist, podcast queue, favorite), matching the iOS context menus.
- **Settings**: display, player, swipe actions, library, artwork, equalizer, account
  (theme color, auto-cache, scrobbling), support (event log, log export) and licenses.
- **Per-account theme color** and an iOS-style semantic color system on top of Material 3.
- **Chinese pinyin sorting and indexing** for libraries with Chinese titles.

## Status

Feature parity with Amperfy for iOS 2.1.0 has been reached; the project is in a full
regression pass. Known gaps and backlog items:

- **Android Auto** (counterpart of iOS CarPlay) is not implemented.
- **Google Assistant / App Shortcuts** (counterpart of iOS Siri Intents) are not implemented.
- The **X-Callback-URL** settings screen is still a placeholder.
- The **Artwork Display** preference has no consumer yet: the app always uses server
  artwork and does not extract embedded ID3 artwork.
- **UI strings are hard-coded in English**; moving them to `strings.xml` for localization
  is pending.
- Credentials are stored with `androidx.security:security-crypto`, which Google has
  deprecated. Migration to a Keystore-backed implementation is pending.
- **Ampache support** is implemented but still needs a full regression pass against real
  servers.

Some behaviour deliberately differs from iOS (for example, sheets do not shrink the
underlying page, and short pages do not show the search bar permanently). Such differences
are documented in code comments next to the affected implementation.

## Tech stack

| Technology | Purpose |
|------------|---------|
| Kotlin | 100% Kotlin |
| Jetpack Compose (Material 3) | Declarative UI |
| Hilt | Dependency injection |
| Room | Local database (22 tables, multi-account tenant model, schema v1) |
| Retrofit + OkHttp | Subsonic API; Ampache uses OkHttp directly with a SAX XML parser |
| Media3 (ExoPlayer) | Audio playback, custom `AudioProcessor` chain for EQ / ReplayGain / visualizer |
| Kotlin Flow + Coroutines | Reactive data and async work |
| Navigation Compose | Navigation |
| Coil | Image loading |
| EncryptedSharedPreferences | Credential storage |
| pinyin4j | Chinese pinyin sort keys |

### iOS → Android mapping

| iOS | Android |
|-----|---------|
| Core Data | Room |
| SwiftUI / UIKit | Jetpack Compose |
| Combine | Kotlin Flow + Coroutines |
| Alamofire | Retrofit + OkHttp |
| AVFoundation | Media3 (ExoPlayer) |
| Keychain | EncryptedSharedPreferences |
| UserDefaults | SharedPreferences + StateFlow |
| AppDelegate | `AppDelegate` (Hilt singleton) |
| CarPlay | Android Auto (not implemented) |
| Siri Intents | Google Assistant (not implemented) |

### Architecture

- **Lightweight MVVM + AppDelegate**: ViewModels depend only on `core/AppDelegate.kt`, the
  single dependency aggregator (player, downloader, credentials, settings, event logger and
  the six repository domains), mirroring the iOS `AppDelegate`.
- **Repository layer**: six domain interfaces (Library / Playlist / Podcast / Directory /
  Search / MediaUrl) with a Subsonic and an Ampache implementation family, assembled per
  account by `core/AccountComponentsRegistry.kt`.
- **Data layer**: Room with a per-account tenant model (composite keys, cascading delete on
  logout) behind `data/local/store/` boundary interfaces; the server is the source of truth,
  so writes go to the server first and to the local database only after success.
- **Player**: `PlaybackService` (foreground media session service), `PlayerManager`
  (queues, repeat / shuffle, podcast mode) and `PlaybackStateManager` (persistence).

## Project structure

```
app/src/main/java/com/amperfy/
├── core/            # AppDelegate, AccountManager, AccountComponentsRegistry, syncers, logging
├── data/
│   ├── model/       # Domain models + queue extension functions
│   ├── remote/      # Subsonic API, DTOs, auth, URL builder; ampache/ session, API, SAX parsers
│   ├── local/       # CredentialsManager, SettingsManager, AccountSettingsStore
│   │   ├── db/      # Room: database, DbModule, entity/dao/store/mapper
│   │   └── store/   # Persistence boundary interfaces
│   ├── repository/  # Six domain interfaces + Subsonic implementations; ampache/ implementations
│   └── download/    # DownloadManager
├── player/          # PlayerManager, PlaybackService, PlaybackStateManager; audio/ EQ, gain, visualizer
├── ui/
│   ├── screens/     # Screen composables + ViewModels (player/, settings/)
│   ├── components/  # Reusable components (swipe/, contextmenu/, …)
│   ├── theme/       # Material 3 theme, iOS color system, icon set
│   ├── navigation/  # Navigation graph + CompositionLocals
│   └── util/        # UI helpers
├── di/              # Hilt module
├── utils/           # File logger, artwork cache helpers
├── MainActivity.kt
└── AmperfyApplication.kt
```

## Building

Requirements:

- JDK 17
- Android Studio (latest stable)
- Android Gradle Plugin 9.2.1 with its built-in Kotlin 2.2.10; Gradle 9.4.1 via the wrapper
- Android SDK: minimum API 26, target API 35

```bash
git clone https://github.com/angelo1002888/Amperfy4Android.git
cd Amperfy4Android

./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install on a connected device
./gradlew test                   # JVM unit tests
./gradlew lint                   # lint
./gradlew :app:compileDebugKotlin  # compile only

scripts/run-gates.sh --unit-only # static boundary checks + JVM unit tests
scripts/run-gates.sh             # additionally runs instrumentation tests on a connected device
```

### Debug login prefill

For faster development you can prefill the login screen of **debug** builds by adding the
following keys to `local.properties` (which is git-ignored):

```properties
debug.serverUrl=https://music.example.com
debug.username=alice
debug.password=secret
```

Release builds never read these values; their prefill is always empty.

### Release signing

Release builds are signed with a keystore described by `keystore.properties` in the
repository root (git-ignored). If the file is missing, the release build type falls back to
the debug signing key so that anyone can build an installable release APK.

```properties
storeFile=/absolute/or/relative/path/to/amperfy-release-key.jks
storePassword=<your-store-password>
keyAlias=<your-key-alias>
keyPassword=<your-key-password>
```

To create a keystore:

```bash
keytool -genkeypair \
  -alias <your-key-alias> \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -keystore amperfy-release-key.jks \
  -storepass <your-store-password> \
  -keypass <your-key-password>
```

Never commit `keystore.properties`, `*.jks` or `*.keystore`. Keep a backup of the keystore:
without it you cannot publish updates under the same signature.

## Third-party notices

- **Amperfy for iOS** by Maximilian Bauer (GPLv3): this project is a port of its
  architecture and behaviour. The launcher icon, the splash logo and `assets/Icon-1024.png`
  are the upstream Amperfy artwork, reused under the GPLv3.
- **Cupertino Icons** (MIT, © 2016 Vladimir Kharlampidi): most in-app icons are glyphs of the
  `cupertino_icons` font converted to vector paths (`scripts/icons/`).
- **compose-swipeBox** (Apache 2.0, © KevinnZou): the swipe gesture components in
  `ui/components/swipe/` are derived from it.
- **Ampache API 5 sample responses** in `app/src/test/resources/ampache/` come from the
  Ampache projects (GPL-family licenses); see the README in that directory.
- **pinyin4j** (GPLv2 or later, © Li Min).
- All other dependencies are Apache 2.0 licensed and listed in the in-app Licenses screen.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the build setup, the test gates and the code
boundaries that are enforced. Existing code comments are largely written in Chinese; new
code comments and commit messages should be in English. Issues and pull requests are welcome
in either English or Chinese.

## License and copyright

This project is licensed under the **GNU General Public License v3.0**; see
[LICENSE](LICENSE).

Copyright © 2026 angelo.

Based on [Amperfy for iOS](https://github.com/BLeeEZ/amperfy),
Copyright © 2019-2025 Maximilian Bauer, licensed under the GPLv3.

## Acknowledgments

- [Maximilian Bauer](https://github.com/BLeeEZ) for Amperfy for iOS, the design and
  behaviour this port follows.
- The [Subsonic](http://www.subsonic.org) and [OpenSubsonic](https://opensubsonic.netlify.app)
  API projects.
- The [Ampache](https://ampache.org) project and its API documentation.
