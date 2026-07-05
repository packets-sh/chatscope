# ChatScope

A client-side **Fabric mod for Minecraft 1.21.11** that mirrors your in-game chat, server info, and player list to a live web dashboard — with **fully configurable sections and filters**.

While Minecraft is running, open **<http://localhost:25534>** in any browser and you get a real-time view of your chat that you can organise however you like.

![sections and settings](https://github.com/packets-sh/chatscope) <!-- add a screenshot here -->

## Features

- **Live chat**, updated instantly over a WebSocket (no refreshing). Minecraft colours, bold/italic, and hover tooltips (item stats, entity info) are preserved.
- **Configurable sections** — split chat into as many named sections as you want, each matched by your own regular expressions. Anything unmatched lands in a catch-all section. Sections are collapsible and resizable.
- **Filters** — hide noisy messages entirely with your own regex patterns.
- **Live settings panel** — add, rename, reorder, enable/disable sections and filters right in the browser. Changes apply instantly and are saved locally. Invalid regexes are flagged and simply ignored.
- **Player list** like the in-game tab list: skins/heads, ping bars, game mode, and UUID on hover. Search it, and switch between **Online** and **Offline** (everyone seen in the chat log).
- **Player profiles** — click a player for an interactive 3D view of their skin plus their full logged message history, with search and pagination.
- **Per-server chat log** stored in a local SQLite database, split into a table per server so history never mixes between servers.
- **Quality of life**: dark/light theme, accessibility mode (whitens everything after a player's rank), mention sound, live viewer count, connection uptime, chat search, and export to a text file.
- No external frontend frameworks — just HTML, CSS, and JavaScript.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**.
2. Put the ChatScope jar **and** [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
3. Launch Minecraft. The log prints `ChatScope running on http://localhost:25534`.
4. Open <http://localhost:25534> in a browser.

## Configuring sections & filters

Click the **⚙ settings** button in the top bar.

- **Sections** — each has a name, an on/off toggle, and one or more regex patterns (one per line). A message goes to the **first enabled section whose pattern matches**; the order matters, so use the ↑ / ↓ buttons to prioritise. Whatever matches nothing falls into the catch-all section (rename it at the bottom).
- **Filters** — each is a single regex. A message matching **any enabled filter** is hidden completely.

Patterns are standard JavaScript regular expressions, matched against the plain (colour-code-stripped) text of each message. Examples:

| Goal | Pattern |
| --- | --- |
| A "Staff" section for staff chat | `^\[Staff\]` or `\[SC\]` |
| Route deaths together | `was slain\|was shot\|drowned\|fell` |
| Hide a shop spam prefix | `^SHOP »` |
| Hide anything indented (centered banners) | `^ {6,}\S` |

Your configuration lives in the browser (localStorage), so each viewer can customise their own layout.

## Remote access

The server binds to `localhost` only. To view the dashboard from another device, run a tunnel or reverse proxy (for example [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)) in front of port `25534`. There is no authentication built in, so only expose it to people you trust, or add auth at the proxy layer.

## Building

Requirements: **JDK 21**. Gradle is provided by the wrapper.

```bash
./gradlew build          # Windows: gradlew.bat build
```

The mod jar is written to `build/libs/chatscope-<version>.jar` (use the one *without* `-sources`).

## Notes

- Player heads and skins load from [Crafatar](https://crafatar.com) by UUID, falling back to [mc-heads.net](https://mc-heads.net) by name for offline-mode servers.
- The chat log database lives at `<game dir>/chatscope/chat.db` and can be opened with any SQLite browser.
- Runs entirely client-side; the embedded web server is bound to localhost.

## Project layout

```
src/main/java/com/chatscope/
├── ChatScope.java           entry point, wiring, web server lifecycle
├── chat/                    chat event listener + bounded history buffer
├── players/                 tab list polling
├── server/                  connection tracking + shared dashboard state
├── web/                     embedded HTTP server (NanoHTTPD)
├── websocket/               WebSocket sockets + broadcast thread
├── db/                      per-server SQLite chat log
├── model/                   immutable JSON-serializable records
└── util/                    Text→segments conversion, JSON helpers
src/main/resources/web/      index.html, style.css, app.js
```

## License

MIT — see [LICENSE](LICENSE).
