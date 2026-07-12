# forge-gui-web

Browser-based thin client for Forge. The rules engine runs unchanged on the host JVM; the browser
is a dumb renderer that receives game state as JSON over a WebSocket and (from Phase 2) sends the
player's actions back. This is the web sibling of the Netty network client — see
[`docs/Development/Web-Client-Spec.md`](../docs/Development/Web-Client-Spec.md) for the full design.

## What's here (Phase 0)

| File | Role |
|---|---|
| `WebServer` | Embedded Javalin HTTP + WebSocket host; serves the browser bundle from `resources/web/` and a `/game` socket. |
| `WebSession` | One connected browser: JSON encode/decode, outbound send, and the reply-future pool for blocking questions (`sendAndWait`, used from Phase 2). |
| `WebGuiGame` | Server-side `IGuiGame` (extends `NetworkGuiGame`) that streams state to the browser. Mirrors `RemoteClientGuiGame`. |
| `dto/` | `GameView`/`PlayerView`/`CardView`/`StackItem` → JSON projections. |
| `WebMatchLauncher` | `main()` that starts the host. Contains the one deliberately-stubbed piece: the Commander-vs-AI match bootstrap (see its Javadoc). |
| `resources/web/` | `index.html` + `app.js` — the Phase 0 renderer (plain JS now; migrates to TypeScript under `web/` in Phase 1). |

## Run (Phase 0)

Build the module (first time pulls Javalin):

```
mvn -pl forge-gui-web -am -DskipTests install
```

Then run `forge.gui.web.WebMatchLauncher` with the **repo root as the working directory** (so Forge
finds `res/`), e.g. from your IDE, or:

```
mvn -pl forge-gui-web -DskipTests exec:java -Dexec.mainClass=forge.gui.web.WebMatchLauncher
```

Open `http://<this-machine-LAN-ip>:7070` on your phone (find the IP with `ipconfig` / `ifconfig`).
The page connects and shows "connected". Once `WebMatchLauncher.startCommanderVsAi(...)` is wired up
(the first-build task), a Commander-vs-AI game boots and the board renders live.

## Build notes for maintainers

- The Node/TypeScript frontend build (Phase 1+) will live under `web/` and emit into
  `src/main/resources/web/`. Keep it behind an optional Maven profile so a normal `mvn install`
  never requires Node; commit the built bundle so the module builds without a frontend toolchain.
- No engine changes: this module only *adds* code and depends on `forge-gui`.
