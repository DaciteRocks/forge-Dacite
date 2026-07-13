package forge.gui.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.function.Consumer;

/**
 * Embedded HTTP + WebSocket host. Serves the browser bundle from the classpath
 * ({@code src/main/resources/web/}) and exposes a single {@code /game} WebSocket per client.
 *
 * <p>Gameplay-only, single human seat: one persistent {@link WebSession} for the whole game. A
 * dropped socket (phone backgrounding, screen lock) does not end the game — the next connection
 * rebinds that same session and triggers a resync. Multiple simultaneous humans are out of scope.</p>
 */
public final class WebServer {
    private final int port;
    private Javalin app;

    /** Notified when the FIRST browser connects and the single game session is created. */
    private Consumer<WebSession> onConnect = s -> { };

    /** Notified when a browser RE-connects to the already-running game (after a dropped socket), so
     * the host can push a full resync. The session has already been rebound to the new connection. */
    private Consumer<WebSession> onReconnect = s -> { };

    /** The single game session (this client is gameplay-only, one human seat). Persists across
     * socket drops: a reconnecting browser rebinds this same session rather than starting over. */
    private volatile WebSession session;

    /** Connection id currently bound to {@link #session}, so a stale old socket's close is ignored. */
    private volatile String activeConnectionId;

    public WebServer(final int port) {
        this.port = port;
    }

    public void setOnConnect(final Consumer<WebSession> handler) {
        this.onConnect = handler;
    }

    public void setOnReconnect(final Consumer<WebSession> handler) {
        this.onReconnect = handler;
    }

    public WebSession getSession() {
        return session;
    }

    public void start() {
        app = Javalin.create(config -> {
            // Serve index.html + app.js from the jar/classpath under /web.
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        app.ws("/game", ws -> {
            ws.onConnect(ctx -> {
                final Consumer<String> transport = text -> {
                    try {
                        ctx.send(text);
                    } catch (final Exception e) {
                        System.out.println("[web] send failed: " + e.getMessage());
                    }
                };
                activeConnectionId = ctx.sessionId();
                if (session == null) {
                    session = new WebSession(transport);
                    System.out.println("[web] browser connected (new game): " + ctx.sessionId());
                    onConnect.accept(session);
                } else {
                    // Reconnect: rebind the live session to this socket and resync the board.
                    session.rebind(transport);
                    System.out.println("[web] browser reconnected: " + ctx.sessionId());
                    onReconnect.accept(session);
                }
            });
            ws.onMessage(ctx -> {
                final WebSession s = session;
                if (s != null) {
                    s.onClientMessage(ctx.message());
                }
            });
            ws.onClose(ctx -> {
                // Only detach if this is the current socket — a stale socket closing after a
                // reconnect must not tear down the live session.
                if (ctx.sessionId().equals(activeConnectionId)) {
                    System.out.println("[web] browser disconnected (awaiting reconnect): " + ctx.sessionId());
                    final WebSession s = session;
                    if (s != null) {
                        s.detach();
                    }
                }
            });
        });

        app.start(port);
        System.out.println("[web] Forge web client on http://<this-machine-lan-ip>:" + port + "  (open in your phone browser)");
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
