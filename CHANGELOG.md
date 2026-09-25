# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-09-24

First public release. Android port of Amperfy for iOS, baseline iOS release 2.1.0.

### Added

- Subsonic / OpenSubsonic support with MD5 token + salt authentication and automatic
  fallback to legacy plain-text authentication.
- Ampache support (API 5, XML) with session-based authentication and token refresh.
- Multiple accounts with per-account library, downloads, settings and theme color.
- Home screen with configurable sections and a Home Preferences editor.
- Library browsing: artists, albums (list / grid), songs, favorites, newest and recently
  played albums, genres, radios, podcasts and directories; editable library entries.
- Global search with grouped results, All / Cached scope and search history.
- Playlists: list, detail, edit (rename, reorder, delete), add-songs browser and playlist
  selector.
- Player with large / compact modes, artwork / lyrics / visualizer views, ratings and
  favorites, playback rate, sleep timer, skip buttons and manual playback mode.
- Three-section queue (context / user / previous) with cross-section reordering, repeat
  and shuffle modes, and a separate podcast queue.
- Audio chain: 10-band equalizer with presets, ReplayGain and an audio visualizer.
- OpenSubsonic lyrics with line highlighting and on-disk cache.
- Scrobbling with "now playing", play-time threshold and offline retry queue.
- Downloads and offline playback for songs and podcast episodes, Downloads screen,
  cache limits and auto-caching of latest songs and podcast episodes.
- Background playback with media session and notification controls.
- Playback state and queue persistence across restarts.
- Swipe actions and long-press preview menus on list rows.
- Settings: display, player, swipe actions, library, artwork, equalizer, account, support
  (event log, log export) and licenses.
- Chinese pinyin sorting and alphabet index.

[Unreleased]: https://github.com/angelo1002888/Amperfy4Android/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/angelo1002888/Amperfy4Android/releases/tag/v1.0.0
