# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project overview

Amperfy4Android is an unofficial Android port of Amperfy for iOS. The porting baseline is
**Amperfy iOS 2.1.0** (upstream repository https://github.com/BLeeEZ/amperfy, tag `2.1.0`).
It supports Subsonic / OpenSubsonic and Ampache (API 5, XML) servers and uses a modern
Android stack: Jetpack Compose, Hilt, Room, Media3 (ExoPlayer).

Feature parity with iOS 2.1.0 has been reached. Remaining gaps (Android Auto, Google
Assistant, X-Callback-URL settings, localization, ...) are listed in `README.md`.
Deliberate deviations from iOS are documented in code comments next to the affected code.

When iOS behaviour needs to be checked, read the upstream sources at tag 2.1.0. Names
(classes, settings keys, menu labels) are kept identical to iOS wherever possible so the
two code bases can be cross-referenced.

## Build commands

```bash
./gradlew assembleDebug             # build debug APK
./gradlew installDebug              # install on a connected device
./gradlew assembleRelease           # build release APK (falls back to debug signing without keystore.properties)
./gradlew test                      # all JVM unit tests
./gradlew testDebugUnitTest         # debug JVM unit tests
./gradlew lint                      # lint
./gradlew :app:compileDebugKotlin   # compile Kotlin only
scripts/run-gates.sh --unit-only    # static boundary checks + JVM unit tests
scripts/run-gates.sh                # + instrumentation tests on a connected device
```

## Architecture

### Core pattern

Lightweight MVVM + AppDelegate + Clean Architecture:

- **UI**: Jetpack Compose screens and reusable components.
- **ViewModel**: thin `@HiltViewModel` classes that inject only `AppDelegate`.
- **AppDelegate** (`core/AppDelegate.kt`): the single dependency aggregator (`@Singleton`),
  aligned with the iOS `AppDelegate`.
- **Repository**: six domain interfaces (Library / Playlist / Podcast / Directory / Search /
  MediaUrl). `MusicRepository` is a facade that aggregates them and exists only for
  assembly; business code always uses the narrow domain entry points.
- **Data**: local (Room) + remote (Subsonic API or Ampache API family, chosen per account).

### Principles

1. **AppDelegate pattern.** `AppDelegate` exposes `player`, `downloader`, `credentials`,
   `settings`, `eventLogger`, `accounts`, `accountSettings`, `audioAnalyzer` and the six
   repository entry points `library` / `playlists` / `podcasts` / `directories` / `search` /
   `mediaUrls` (each is the active account's instance). ViewModels inject `AppDelegate`
   only. `music`, `downloader` and `backgroundLibrarySyncer` are computed getters that
   resolve to the active account.

2. **Extension functions for reuse** (`data/model/SongQueueExtensions.kt`), e.g.
   `song.addToQueueNext(appDelegate, scope)`.

3. **Server is the source of truth.** Sync to the server first; update the local database
   only after success. Room queries return `Flow<T>`. Initial sync (managed by
   `CredentialsManager.needsInitialSync()`) covers genres / artists / albums / playlists /
   podcasts and does **not** fetch album songs; `core/BackgroundLibrarySyncer.kt` fills album
   songs progressively (started after launch / initial sync, stopped before logout /
   resync) and also handles auto-caching of the latest podcast episodes. Album detail
   screens sync on demand as a fallback. Sync is guarded by real connectivity
   (`BaseSubsonicRepository.isSyncAllowed`); only the initial sync fails fast.

4. **Room database** (`data/local/db/`): 22 tables. Account-scoped tables use the
   composite primary key `(account_id, server_id)` with a foreign key to `account_scope`
   and `ON DELETE CASCADE`, so logging out deletes the account's data by removing one row.
   Account metadata is not stored in the database (`CredentialsManager` is the only source).
   `event_log` and `playback_state` are global. Schema version is **1** and exported to
   `app/schemas/`. **Never use `fallbackToDestructiveMigration`**: schema changes require an
   explicit `Migration`, a migration test and the regenerated schema JSON. Derived sort /
   search keys (pinyin) are generated on write by `LibraryTextKeyNormalizer`; alphabetical
   ordering is pushed down to SQL via `sort_key`.

5. **Settings** (`data/local/SettingsManager.kt`, `SettingsPreferences.kt`): every setting
   is a `StateFlow<T>`, accessed through `appDelegate.settings`. Account-level settings
   (theme color, auto-cache switches, scrobble streamed items, artwork download policy)
   live in `data/local/AccountSettingsStore.kt`.

6. **Credentials and multiple accounts.** `data/local/CredentialsManager.kt` stores
   credentials in EncryptedSharedPreferences namespaced by account ident.
   `core/AccountManager.kt` handles login / switch / logout. `core/AccountComponentsRegistry.kt`
   builds per-account components (repositories, DownloadManager, ScrobbleSyncer,
   BackgroundLibrarySyncer, and either Retrofit + `SubsonicApi` or `AmpacheAuthSession` +
   `AmpacheApi`, chosen by `credentialsManager.getBackendApi(ident)`).

7. **Dependency injection.** Hilt, single module `di/AppModule.kt`. Composables use
   CompositionLocals (`LocalCredentialsManager`, `LocalMediaUrlRepository`, URL building only).

8. **Player** (three layers): `PlaybackService` (foreground service with MediaSession),
   `PlayerManager` (`@Singleton`; MediaController, queues, repeat / shuffle, music vs.
   podcast mode with separate queues, cross-section drag via `movePlayable`), and
   `PlaybackStateManager` (persists state and the five queue sections to Room and restores
   them with freshly built URLs).

9. **URL building.** `AccountBaseUrlInterceptor` (one per account) rewrites scheme / host /
   port per request on a shared OkHttpClient. `SubsonicUrlBuilder` produces authenticated
   stream / download / cover URLs; these methods take explicit credentials because
   `PlayerManager` builds URLs by `playable.accountId`. All other API calls resolve
   credentials inside the repository; Retrofit endpoints get their auth parameters from the
   per-account `SubsonicAuthInterceptor`.

## Implementation constraints

### Room

- Multi-table writes run inside `db.withTransaction { }`. Order-sensitive whole-table
  replacements have a single transactional entry point (e.g. `replacePlaylistSongs`,
  `replaceAllQueues`); never update positions row by row.
- Observed reads use DAO `Flow<T>`; one-shot reads use suspend queries.
- Business code never touches DAOs directly. It goes through the `data/local/store/`
  boundary interfaces (methods take `accountId`, exchange domain models or scalars only).
- `import androidx.room` is allowed only under `data/local/db/` (enforced by
  `scripts/run-gates.sh`).

### Subsonic API

- Response shape: `SubsonicResponse.subsonicResponse.albumList2.album`.
- `userRating` 0-5; `starred` ISO date string → `Long` timestamp.
- Authentication is MD5 token + salt (`u`, `v=1.13.0`, `c`, `t=md5(password+salt)`,
  `s=<random 16-char salt per request>`). Legacy accounts send `v=1.11.0` and plain `p`.
  Login probes Ampache → Subsonic token → legacy and stores the result with the
  credentials. `data/remote/SubsonicAuthParams.kt` is the single source of truth for auth
  parameters; Retrofit requests get them from `SubsonicAuthInterceptor`, self-contained
  URLs (stream / download / getCoverArt) from `SubsonicUrlBuilder`. Because the salt
  changes per request, Coil cache keys strip auth parameters (`utils/ArtworkCacheKey.kt`).

### Ampache API

- Location: `data/remote/ampache/` (`AmpacheAuthSession`, `AmpacheApi`, `AmpacheDto`,
  `AmpacheApiVersion`, `parser/` SAX parsers) and `data/repository/ampache/`
  (`BaseAmpacheRepository`, six domain implementations, `AmpacheDtoMappers`, facade).
- Session-based auth: handshake with `sha256(ts + sha256(password))`; the token lives in
  memory only and is renewed five minutes before expiry (one handshake shared via Mutex).
  Login verification is a handshake; no ping / goodbye.
- No Retrofit: OkHttp with absolute URLs; XML parsed with `javax.xml.parsers` SAX (XXE
  disabled). Errors are converted to `AmpacheApiException` in the parser layer and logged
  through `runAmpache` with sanitized URLs.
- Token freshness: `MediaUrlRepository` URL methods are non-suspend and use the current
  token; ExoPlayer's `ResolvingDataSource` and Coil's `AmpacheArtworkAuthInterceptor`
  refresh the token at load time via `core/AmpacheUrlAuthRefresher`. Coil cache keys strip
  `auth` / `ssid`.
- Mapping: `cover_art` stores the `<art>` URL without auth; `starred` placeholder `0L`;
  bit rate in kbps; `Song.streamUrl` is null; directories are catalog → `artist-<id>` →
  `album-<id>`; podcast titles / descriptions are HTML-unescaped twice.

### Compose UI

- ViewModels: `@HiltViewModel` + `hiltViewModel()`; state via `collectAsState()`.
- Navigation: route-based Navigation Compose (`ui/navigation/AmperfyNavigation.kt`).
- **List rows and dividers** (aligned with iOS `UITableView` defaults): row containers are
  full width, horizontal 16dp padding is inside the row; dividers use
  `ui/components/HairlineDivider.kt` (always one physical pixel; never
  `HorizontalDivider(thickness = Dp.Hairline)`), inset 16dp on the start side and running
  to the screen edge; section boundaries and the last divider of a list are full width;
  grouped section headers use `ui/components/GroupedSectionHeader.kt` (40dp, not sticky;
  sticky headers only in the player queue). Section mode and index-bar labels per sort
  option are defined in `ui/util/SortSectionUtils.kt`. When an index bar is visible, row
  end padding is `maxOf(16.dp, LocalListRowTrailingInset.current)` computed by
  `rememberIndexBarRowEndPadding`.
- **Search bar** (`ui/components/SearchBarReveal.kt`, `LibrarySearchState`): hidden on
  entry, revealed by pull-down, hidden again on scroll-up when the content scrolls, kept
  visible while active. Screens only provide placeholder text, scope segments and
  callbacks. Only `SearchScreen` has a permanent search field.
- **Sheets**: bottom sheets stop at the status bar with a 10dp top corner radius; sheet
  backgrounds use the elevated colors in `ColorExtensions`; iOS-style overscroll is in
  `ui/components/IOSOverscroll.kt`; `ui/components/SheetWindowFix.kt` mirrors the status bar
  icon appearance into sheet windows.
- **Context menus and previews**: all entity menus are built in
  `ui/components/contextmenu/EntityPreviewActionBuilder.kt` (one builder per entity type);
  list-row long press, Home cards and detail-screen "More" menus share the same builders.
  Menu actions and swipe actions share the `SwipeActionType` execution path.
- Long-press previews, list rows and swipe actions must stay behaviourally identical
  across screens; do not duplicate menu construction.

### Colors

`ui/theme/Theme.kt`, `ColorExtensions.kt`. Always use `MaterialTheme.colorScheme.xxx`;
never hard-code colors. Rating stars use `colorScheme.gold`, favorite hearts
`colorScheme.redHeart`. `AmperfyTheme(themeColor)` overrides only `primary` / `onPrimary`
per account; `MainActivity` observes the active account's settings to switch colors.

### Icons

`ui/theme/AmperfyIcons.kt` is the only entry point (`AmperfyIconPaths.kt` is generated by
`scripts/icons/convert-cupertino-icons.mjs` from the MIT-licensed Cupertino Icons font;
`AmperfyIconPathsCustom.kt` holds hand-drawn paths). **Never import Material icons in
business code** (enforced by `scripts/run-gates.sh`). Each property's KDoc names the
corresponding iOS `AmperfyImage` constant. SF Symbols path data must not be committed.

### Default artwork

`ui/util/DefaultArtwork.kt` paints per-type placeholder artwork with the account theme
color (mirrors iOS `UIImage.createArtwork`). Artwork slots always use the real cover URL as
the model and `rememberDefaultArtworkPainter(type)` as placeholder / error / fallback.

### Audio chain (frozen)

Audio offload / passthrough must never be enabled: the EQ / ReplayGain / visualizer
`AudioProcessor`s in `player/audio/` depend on the PCM path.

## Common tasks

### Add a screen

1. Create the composable in `ui/screens/`.
2. Create a `@HiltViewModel` that injects `AppDelegate`.
3. Collect state with `collectAsState()`.
4. Register the route in `ui/navigation/AmperfyNavigation.kt`.
5. For lists: integrate `SwipeableItem`, reading `settings.swipeActionSettings` filtered
   by `SwipeDisplaySettings.filter`.

### Add a ViewModel

`@HiltViewModel class X @Inject constructor(private val appDelegate: AppDelegate)`; reach
managers through `appDelegate.library`, `appDelegate.player`, etc.; use the queue
extension functions for queue operations.

### Change the database schema

1. Update entities in `data/local/db/entity/`.
2. Bump `version` in `AmperfyDatabase.kt`, write and register an explicit `Migration`.
3. Commit the regenerated `app/schemas/` JSON and add a migration test.
4. Never use `fallbackToDestructiveMigration`.

### Add a server API call

1. Subsonic: add the endpoint to `SubsonicApi.kt` and DTOs to `SubsonicDto.kt`.
   Ampache: add the action to `AmpacheApi.kt`, a DTO and a SAX parser.
2. Add the method to the domain interface and to both implementation families.
3. Call it from the ViewModel through the narrow entry point (e.g. `appDelegate.library`).

## Technical constraints

- Min SDK 26, target SDK 35.
- Kotlin 2.2.10 (built into AGP 9.2.1; the project does not declare the `kotlin-android`
  plugin), Compose compiler plugin at the same version, Java 17.
- Room schema version 1, explicit migrations only.
- Audio offload disabled (see above).

## Project structure

```
app/src/main/java/com/amperfy/
├── core/            # AppDelegate, AccountManager, AccountComponentsRegistry, syncers, EventLogger
├── data/
│   ├── model/       # Domain models + SongQueueExtensions
│   ├── remote/      # Subsonic API, DTOs, auth, URL builder; ampache/ session + API + parsers
│   ├── local/       # CredentialsManager, SettingsManager, AccountSettingsStore
│   │   ├── db/      # Room: AmperfyDatabase, DbModule, entity/dao/store/mapper
│   │   └── store/   # Persistence boundary interfaces
│   ├── repository/  # Six domain interfaces + Subsonic implementations + facade; ampache/
│   └── download/    # DownloadManager
├── player/          # PlayerManager, PlaybackService, PlaybackStateManager; audio/
├── ui/
│   ├── screens/     # Screens + ViewModels (player/, settings/)
│   ├── components/  # Reusable components (swipe/, contextmenu/)
│   ├── theme/       # Material 3 theme, iOS colors, icons
│   ├── navigation/  # Navigation graph + CompositionLocals
│   └── util/        # UI helpers
├── di/              # AppModule
├── utils/           # FileLogger, artwork cache helpers
├── MainActivity.kt
└── AmperfyApplication.kt
```

## Working conventions

- Read the upstream iOS sources (tag 2.1.0) when behaviour is unclear. When the true
  behaviour lives inside closed-source framework internals (UIKit defaults, etc.), verify
  on a real iOS device rather than guessing from memory or analogy.
- Keep names aligned with the iOS code base for cross-referencing.
- Respond in the user's language. New code comments and commit messages are written in
  English; existing Chinese comments may stay as they are.
- Do root-cause analysis when fixing bugs and state the root cause in the commit message.
- Do not skip git hooks (`--no-verify`, `--no-gpg-sign`) unless explicitly asked.
- Do not add `Co-Authored-By` lines to commit messages.

## License

GPLv3, consistent with the upstream Amperfy iOS project.
