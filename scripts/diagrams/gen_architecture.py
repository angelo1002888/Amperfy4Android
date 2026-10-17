#!/usr/bin/env python3
"""Generate the Amperfy4Android architecture diagram as light and dark SVG files.

Usage:  python3 scripts/diagrams/gen_architecture.py assets

Writes assets/architecture-light.svg and assets/architecture-dark.svg, which README.md
embeds through a <picture> element so GitHub picks the variant matching the viewer's theme.
Edit the node texts / layout below and re-run; positions are explicit on purpose so the
result stays stable instead of depending on an auto-layout engine.
"""
import sys
from html import escape

W, H = 1440, 1232
FONT = "-apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Helvetica, Arial, sans-serif"

PALETTES = {
    "light": dict(bg="#ffffff", band="#f6f8fa", band_stroke="#d0d7de", node="#ffffff",
                  node_stroke="#afb8c1", text="#1f2328", muted="#59636e", edge="#6e7781",
                  accent=dict(ui="#8250df", vm="#0969da", core="#bf8700", account="#1a7f37",
                              player="#cf222e", local="#0969da", remote="#8250df", servers="#57606a")),
    "dark": dict(bg="#0d1117", band="#161b22", band_stroke="#30363d", node="#21262d",
                 node_stroke="#6e7681", text="#e6edf3", muted="#9198a1", edge="#8b949e",
                 accent=dict(ui="#a371f7", vm="#58a6ff", core="#d29922", account="#3fb950",
                             player="#f85149", local="#58a6ff", remote="#a371f7", servers="#8b949e")),
}

nodes = {}
out = []


def band(p, key, x, y, w, h, title):
    acc = p["accent"][key]
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{p["band"]}" stroke="{p["band_stroke"]}"/>')
    out.append(f'<rect x="{x}" y="{y + 10}" width="4" height="{h - 20}" rx="2" fill="{acc}"/>')
    out.append(f'<text x="{x + 18}" y="{y + 24}" font-size="13" font-weight="700" fill="{acc}" '
               f'letter-spacing="0.6">{escape(title.upper())}</text>')


def node(p, key, nid, x, y, w, h, title, lines=()):
    nodes[nid] = (x, y, w, h)
    acc = p["accent"][key]
    out.append(f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="{h}" rx="8" fill="{p["node"]}" stroke="{p["node_stroke"]}"/>')
    out.append(f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="3" rx="1.5" fill="{acc}"/>')
    total = 1 + len(lines)
    line_h = 15
    block = 17 + (total - 1) * line_h
    ty = y + (h - block) / 2 + 13
    cx = x + w / 2
    out.append(f'<text x="{cx:.1f}" y="{ty:.1f}" font-size="13" font-weight="600" text-anchor="middle" fill="{p["text"]}">{escape(title)}</text>')
    for i, ln in enumerate(lines):
        out.append(f'<text x="{cx:.1f}" y="{ty + 15 + i * line_h:.1f}" font-size="11" text-anchor="middle" fill="{p["muted"]}">{escape(ln)}</text>')


def A(nid, side, frac=0.5):
    x, y, w, h = nodes[nid]
    return {"top": (x + w * frac, y), "bottom": (x + w * frac, y + h),
            "left": (x, y + h * frac), "right": (x + w, y + h * frac)}[side]


def label(p, text, x, y, anchor="middle"):
    out.append(f'<text x="{x:.1f}" y="{y + 4:.1f}" font-size="11" text-anchor="{anchor}" fill="{p["muted"]}" '
               f'paint-order="stroke" stroke="{p["bg"]}" stroke-width="5" stroke-linejoin="round">{escape(text)}</text>')


def edge(p, pts, text=None, at=None, anchor="middle"):
    """Draw an orthogonal polyline through pts (list of (x, y)); label at an explicit point."""
    d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    out.append(f'<path d="{d}" fill="none" stroke="{p["edge"]}" stroke-width="1.5" marker-end="url(#arrow)"/>')
    if text:
        if at is None:
            (x1, y1), (x2, y2) = pts[0], pts[-1]
            at = ((x1 + x2) / 2, (y1 + y2) / 2)
        label(p, text, at[0], at[1], anchor)


def vert(p, a, b, text=None, af=0.5, bf=0.5, mid=None, at=None):
    """a.bottom -> b.top with one elbow when the columns differ."""
    x1, y1 = A(a, "bottom", af)
    x2, y2 = A(b, "top", bf)
    if abs(x1 - x2) < 1:
        pts = [(x1, y1), (x1, y2)]
        at = at or (x1, (y1 + y2) / 2)
    else:
        my = mid if mid is not None else (y1 + y2) / 2
        pts = [(x1, y1), (x1, my), (x2, my), (x2, y2)]
        at = at or ((x1 + x2) / 2, my)
    edge(p, pts, text, at)


def build(theme):
    p = PALETTES[theme]
    out.clear()
    nodes.clear()
    out.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
               f'font-family="{FONT}" role="img" aria-label="Amperfy4Android architecture: Compose UI, ViewModels, '
               f'AppDelegate core, per-account repositories and services, player and audio chain, local Room data, '
               f'remote Subsonic and Ampache APIs, servers.">')
    out.append(f'<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" '
               f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{p["edge"]}"/></marker></defs>')
    out.append(f'<rect width="{W}" height="{H}" fill="{p["bg"]}"/>')

    L, R = 32, W - 32
    CW = R - L  # 1376
    gap = 24

    # ---- Row 1: UI ---------------------------------------------------------
    band(p, "ui", L, 24, CW, 132, "UI layer · Jetpack Compose")
    gw = (CW - 36 - 3 * gap) / 4
    x, y = L + 18, 60
    node(p, "ui", "screens", x, y, gw, 76, "Screens", ["ui/screens: Home, Library, Search,", "detail screens, Player, Settings"]); x += gw + gap
    node(p, "ui", "components", x, y, gw, 76, "Reusable components", ["list rows, swipe actions, context menus,", "search bar reveal, iOS-style sheets"]); x += gw + gap
    node(p, "ui", "theme", x, y, gw, 76, "Theme & icons", ["iOS semantic colors, per-account theme color,", "AmperfyIcons (Cupertino glyphs + custom paths)"]); x += gw + gap
    node(p, "ui", "coil", x, y, gw, 76, "Coil ImageLoader", ["cover art over HTTPS: artwork policy,", "auth-stripped cache keys, Ampache token refresh"])

    # ---- Row 2: ViewModels -------------------------------------------------
    band(p, "vm", L, 180, CW, 92, "ViewModel layer")
    node(p, "vm", "vms", L + 18 + gw + gap, 216, 2 * gw + gap, 40, "@HiltViewModel ViewModels", ["thin: inject AppDelegate only, expose StateFlow to the screens"])

    # ---- Row 3: Core -------------------------------------------------------
    band(p, "core", L, 296, CW, 204, "Core · core/")
    node(p, "core", "appdelegate", L + 18 + gw + gap, 332, 2 * gw + gap, 56, "AppDelegate  (singleton aggregator, mirrors the iOS AppDelegate)",
         ["library · playlists · podcasts · directories · search · mediaUrls · player · downloader · settings · credentials · accounts · eventLogger"])
    x, y = L + 18, 412
    node(p, "core", "accountmanager", x, y, gw, 64, "AccountManager", ["login · switch · logout", "(logout cascades a library wipe)"]); x += gw + gap
    node(p, "core", "registry", x, y, gw, 64, "AccountComponentsRegistry", ["one component set per account;", "Subsonic or Ampache family by backendApi"]); x += gw + gap
    node(p, "core", "eventlogger", x, y, gw, 64, "EventLogger", ["API / download errors → event_log table,", "exported from the Support screen"]); x += gw + gap
    node(p, "core", "network", x, y, gw, 64, "NetworkMonitor", ["connectivity guard for sync,", "Wi-Fi / cellular bitrate choice"])

    # ---- Row 4: per-account components | player ---------------------------
    LANE = 40                      # left lane for the AccountManager → Credentials edge
    AX = L + LANE
    AW = 812
    band(p, "account", AX, 524, AW, 324, "Per-account components · one set per logged-in account")
    PX = AX + AW + gap
    PW = R - PX
    band(p, "player", PX, 524, PW, 324, "Player · player/")

    nw = 236
    x = AX + 18
    mw = 2 * nw + 24 - 60          # facade narrower → room for edge labels on its right
    node(p, "account", "music", x, 560, mw, 64, "MusicRepository facade",
         ["Library · Playlist · Podcast · Directory · Search · MediaUrl", "assembly only — business code uses the narrow interfaces"])
    node(p, "account", "subimpl", x, 676, nw, 64, "Subsonic implementation family", ["data/repository", "OpenSubsonic extensions (lyrics)"])
    node(p, "account", "ampimpl", x + nw + 24, 676, nw, 64, "Ampache implementation family", ["data/repository/ampache", "same six interfaces, XML + session auth"])
    sx = AX + AW - 18 - nw
    node(p, "account", "downloader", sx, 560, nw, 64, "DownloadManager", ["4 parallel downloads, persisted queue,", "resume on launch, writes the file cache"])
    node(p, "account", "scrobble", sx, 648, nw, 64, "ScrobbleSyncer", ["nowPlaying + listen-threshold scrobble,", "offline retry queue"])
    node(p, "account", "bgsync", sx, 736, nw, 64, "BackgroundLibrarySyncer", ["progressive album-song fill,", "latest podcast episode auto-cache"])

    pw = PW - 36
    px = PX + 18
    node(p, "player", "playermanager", px, 560, pw, 56, "PlayerManager", ["context / user / podcast queues, repeat · shuffle,", "playback rate, sleep timer, manual playback"])
    node(p, "player", "statemanager", px, 640, pw - 56, 40, "PlaybackStateManager", ["persists state + five queue sections"])
    node(p, "player", "service", px, 704, pw, 40, "PlaybackService", ["MediaSessionService, foreground notification"])
    node(p, "player", "exoplayer", px, 760, pw, 40, "Media3 ExoPlayer", ["ResolvingDataSource refreshes Ampache tokens on load"])
    node(p, "player", "audiochain", px, 816, pw, 40, "Audio chain (PCM, offload disabled)", ["Equalizer → Gain (ReplayGain) → Visualizer tap"])

    # ---- Row 5: local | remote ---------------------------------------------
    LW = 676
    band(p, "local", L, 900, LW, 236, "Local data")
    RX = L + LW + gap
    RW = R - RX
    band(p, "remote", RX, 900, RW, 236, "Remote data")

    lw = (LW - 36 - 2 * 20) / 3
    x = L + 18
    node(p, "local", "stores", x, 936, lw, 64, "Store boundary interfaces", ["data/local/store — domain models", "in and out, no Room types leak"])
    node(p, "local", "settings", x + lw + 20, 936, lw, 64, "Settings stores", ["SettingsManager (StateFlow) +", "AccountSettingsStore (per account)"])
    node(p, "local", "files", x + 2 * (lw + 20), 936, lw, 64, "File cache", ["songs · podcast episodes · lyrics,", "per-account directories"])
    node(p, "local", "credentials", x, 1044, lw, 64, "CredentialsManager", ["EncryptedSharedPreferences,", "per-account namespace, backend type"])
    node(p, "local", "room", x + lw + 20, 1044, 2 * lw + 20, 64, "Room · AmperfyDatabase", ["22 tables, account_scope tenant root, (account_id, server_id) keys,", "ON DELETE CASCADE on logout, explicit migrations only"])

    rw = (RW - 36 - 2 * 20) / 3
    x = RX + 18
    node(p, "remote", "subapi", x, 936, rw, 64, "Retrofit SubsonicApi", ["AccountBaseUrlInterceptor +", "SubsonicAuthInterceptor (MD5 token)"])
    node(p, "remote", "urlbuilder", x + rw + 20, 936, rw, 64, "SubsonicUrlBuilder", ["stream / download / cover URLs,", "fresh salt per URL"])
    node(p, "remote", "ampapi", x + 2 * (rw + 20), 936, rw, 64, "AmpacheApi + SAX parsers", ["AmpacheAuthSession: handshake,", "token renewed before expiry"])
    node(p, "remote", "okhttp", x + rw / 2, 1044, 2 * rw + 20, 64, "Shared OkHttpClient", ["connection pool, timeouts,", "DetailedLoggingInterceptor (auth params redacted)"])

    # ---- Row 6: servers ----------------------------------------------------
    node(p, "servers", "servers", RX + 18, 1164, RW - 36, 48, "Subsonic / OpenSubsonic servers  ·  Ampache servers (API 5, XML)", ["Navidrome, Gonic, Airsonic, Subsonic, Ampache, …"])

    # ---- Edges --------------------------------------------------------------
    vert(p, "screens", "vms", "collectAsState()", bf=0.25, mid=196)
    vert(p, "vms", "appdelegate", "constructor injection")
    vert(p, "appdelegate", "accountmanager", mid=400)
    vert(p, "appdelegate", "registry", "active account", mid=400)
    vert(p, "appdelegate", "eventlogger", mid=400)
    # AppDelegate → PlayerManager through the gap between EventLogger and NetworkMonitor
    lane_x = (A("eventlogger", "right")[0] + A("network", "left")[0]) / 2
    ax, ay = A("appdelegate", "right", 0.5)
    tx, ty = A("playermanager", "left", 0.5)
    edge(p, [(ax, ay), (lane_x, ay), (lane_x, ty), (tx, ty)], "player", at=(lane_x, 500), anchor="middle")
    vert(p, "registry", "music", "builds", bf=0.5, mid=512)
    vert(p, "registry", "downloader", "builds the services", mid=512)
    vert(p, "music", "subimpl", "Subsonic account", af=0.25)
    vert(p, "music", "ampimpl", "Ampache account", af=0.75)
    # services → facade (labels sit in the gap right of the facade)
    for nid, frac, txt in (("downloader", 0.25, "mediaUrls"), ("scrobble", 0.55, "library"), ("bgsync", 0.85, "library · podcasts")):
        x1, y1 = A(nid, "left", 0.5)
        x2, y2 = A("music", "right", frac)
        lane = sx - 16
        edge(p, [(x1, y1), (lane, y1), (lane, y2), (x2, y2)], txt, at=((x2 + lane) / 2 + 2, y2 - 9))
    # player chain
    vert(p, "playermanager", "statemanager", "persists", af=0.35, bf=0.4)
    x1, y1 = A("playermanager", "bottom", 0.93)
    x2, y2 = A("service", "top", 0.93)
    edge(p, [(x1, y1), (x2, y2)], "MediaController", at=(x1 - 8, (y1 + y2) / 2), anchor="end")
    vert(p, "service", "exoplayer")
    vert(p, "exoplayer", "audiochain", "PCM")
    # ExoPlayer → servers along the right margin, entering the servers box from the top right
    x1, y1 = A("exoplayer", "right", 0.5)
    sx2, sy2 = A("servers", "top", 0.96)
    edge(p, [(x1, y1), (R - 8, y1), (R - 8, 1148), (sx2, 1148), (sx2, sy2)], "stream (or cached file)", at=(R - 14, 1120), anchor="end")
    # core → local
    x1, y1 = A("accountmanager", "bottom", 0.08)
    x2, y2 = A("credentials", "left", 0.5)
    edge(p, [(x1, y1), (x1, 508), (L + 16, 508), (L + 16, y2), (x2, y2)], "accounts + credentials", at=(x1 + 6, 494), anchor="start")
    # repositories → stores / APIs
    col = A("stores", "top", 0.82)[0]
    x1, y1 = A("subimpl", "bottom", (col - nodes["subimpl"][0]) / nw)
    edge(p, [(x1, y1), (col, A("stores", "top")[1])], "Room writes after the server call succeeds", at=(col + 8, 921), anchor="start")
    x1, y1 = A("ampimpl", "bottom", 0.12)
    x2, y2 = A("stores", "top", 0.3)
    edge(p, [(x1, y1), (x1, 866), (x2, 866), (x2, y2)])
    vert(p, "subimpl", "subapi", "JSON", af=0.8, mid=890)
    vert(p, "subimpl", "urlbuilder", af=0.95, mid=878, text="media URLs", at=(A("urlbuilder", "top")[0] + 8, 878))
    vert(p, "ampimpl", "ampapi", "XML", af=0.95, mid=866)
    x1, y1 = A("stores", "bottom", 0.75)
    x2, y2 = A("room", "top", 0.12)
    edge(p, [(x1, y1), (x1, 1022), (x2, 1022), (x2, y2)], "DAOs", at=(x2 + 8, 1022), anchor="start")
    vert(p, "subapi", "okhttp", bf=0.25, mid=1026)
    vert(p, "ampapi", "okhttp", "absolute URLs", bf=0.75, mid=1026)
    vert(p, "okhttp", "servers", "HTTPS")

    out.append('</svg>')
    return "\n".join(out)


if __name__ == "__main__":
    dest = sys.argv[1]
    for theme in ("light", "dark"):
        with open(f"{dest}/architecture-{theme}.svg", "w", encoding="utf-8") as f:
            f.write(build(theme))
    print("written")
