package forge.gui.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** Serializes the connect/reconnect/close transitions so a stale close can't detach a session a
     * concurrent reconnect just rebound (a check-then-act on activeConnectionId alone races). */
    private final Object lifecycleLock = new Object();

    /** If a disconnect isn't followed by a reconnect within the grace window, the game is treated as
     * abandoned and its pending questions are failed so the game thread can't park indefinitely. */
    private static final long RECONNECT_GRACE_SECONDS = 120;
    private final ScheduledExecutorService graceTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "web-reconnect-grace");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> graceTask;

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
                final boolean firstConnect;
                final WebSession s;
                synchronized (lifecycleLock) {
                    activeConnectionId = ctx.sessionId();
                    cancelGraceTask(); // a reconnect within the grace window keeps the game alive
                    if (session == null) {
                        session = new WebSession(transport);
                        firstConnect = true;
                    } else {
                        session.rebind(transport); // atomic with the close-side detach decision
                        firstConnect = false;
                    }
                    s = session;
                }
                // Start the game / push the resync outside the lock (they may be slow).
                if (firstConnect) {
                    System.out.println("[web] browser connected (new game): " + ctx.sessionId());
                    onConnect.accept(s);
                } else {
                    System.out.println("[web] browser reconnected: " + ctx.sessionId());
                    onReconnect.accept(s);
                }
            });
            ws.onMessage(ctx -> {
                // Only accept input from the active connection, so a stale/duplicate socket can't
                // inject spurious actions (pass/concede) into the live game. Stale replies are
                // already harmless (their pending id is gone), but actions must be gated.
                if (!ctx.sessionId().equals(activeConnectionId)) {
                    return;
                }
                final WebSession s = session;
                if (s != null) {
                    s.onClientMessage(ctx.message());
                }
            });
            ws.onClose(ctx -> {
                synchronized (lifecycleLock) {
                    // Re-check under the lock: a stale socket closing after a reconnect must not
                    // detach the session the reconnect just rebound.
                    if (ctx.sessionId().equals(activeConnectionId) && session != null) {
                        System.out.println("[web] browser disconnected (awaiting reconnect): " + ctx.sessionId());
                        session.detach();
                        scheduleGraceTask(ctx.sessionId());
                    }
                }
            });
        });

        app.start(port);
        System.out.println("[web] Forge web client on http://<this-machine-lan-ip>:" + port + "  (open in your phone browser)");
    }

    /** Cancel any pending abandonment timer (a browser (re)connected in time). Caller holds the lock. */
    private void cancelGraceTask() {
        if (graceTask != null) {
            graceTask.cancel(false);
            graceTask = null;
        }
    }

    /** After a disconnect, if no reconnect replaces {@code closedId} within the grace window, treat
     * the game as abandoned: terminally fail its pending questions so the game thread can proceed. */
    private void scheduleGraceTask(final String closedId) {
        graceTask = graceTimer.schedule(() -> {
            synchronized (lifecycleLock) {
                if (closedId.equals(activeConnectionId) && session != null) {
                    System.out.println("[web] no reconnect within grace window; abandoning game.");
                    session.onClose();
                }
            }
        }, RECONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        graceTimer.shutdownNow();
        if (app != null) {
            app.stop();
        }
    }
}
