package forge.gui.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Embedded HTTP + WebSocket host. Serves the browser bundle from the classpath
 * ({@code src/main/resources/web/}) and exposes a single {@code /game} WebSocket per client.
 *
 * <p>Phase 0 supports one connected browser at a time (the single human seat). Multiple sessions
 * (Phase 5) will key by connection.</p>
 */
public final class WebServer {
    private final int port;
    private Javalin app;

    /** Notified when a browser connects; receives the freshly-created session for that connection. */
    private Consumer<WebSession> onConnect = s -> { };

    /** Connected sessions keyed by WebSocket connection id, so inbound messages/closes route to the
     * right session (a shared field would misroute replies and let a reconnect clobber a live game). */
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();

    /** Most recently connected session (Phase 0 single-client convenience). */
    private volatile WebSession lastSession;

    public WebServer(final int port) {
        this.port = port;
    }

    public void setOnConnect(final Consumer<WebSession> handler) {
        this.onConnect = handler;
    }

    public WebSession getSession() {
        return lastSession;
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
                final WebSession newSession = new WebSession(text -> {
                    try {
                        ctx.send(text);
                    } catch (final Exception e) {
                        System.out.println("[web] send failed: " + e.getMessage());
                    }
                });
                sessions.put(ctx.sessionId(), newSession);
                lastSession = newSession;
                System.out.println("[web] browser connected: " + ctx.sessionId());
                onConnect.accept(newSession);
            });
            ws.onMessage(ctx -> {
                final WebSession s = sessions.get(ctx.sessionId());
                if (s != null) {
                    s.onClientMessage(ctx.message());
                }
            });
            ws.onClose(ctx -> {
                System.out.println("[web] browser disconnected: " + ctx.sessionId());
                final WebSession closed = sessions.remove(ctx.sessionId());
                if (closed != null) {
                    closed.onClose();
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
