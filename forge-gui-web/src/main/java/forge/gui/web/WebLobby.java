package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pre-game lobby for the single web seat. When a browser connects, instead of immediately starting a
 * match we send it the available deck choices and let the player pick the number of AI opponents and
 * each deck. When the browser sends {@code startGame}, we build and start the match with a fresh
 * {@link WebGuiGame} (which takes over the session's action handler for in-game input).
 *
 * <p>Reconnect: if the game has already started, we resync the running game; otherwise we re-send the
 * setup so the reloaded browser sees the lobby again.</p>
 */
final class WebLobby {
    private final WebGuiBase guiBase;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile WebSession session;
    private volatile WebGuiGame activeGui;

    WebLobby(final WebGuiBase guiBase) {
        this.guiBase = guiBase;
    }

    /** First connection: bind the session and present the lobby. */
    void onConnect(final WebSession newSession) {
        attachLobby(newSession);
    }

    /** Reconnection: resync the running game, or re-present the lobby if it hasn't started. */
    void onReconnect(final WebSession newSession) {
        session = newSession;
        guiBase.setActiveSession(newSession);
        final WebGuiGame gui = activeGui;
        if (started.get() && gui != null) {
            gui.resync();
        } else {
            attachLobby(newSession);
        }
    }

    private void attachLobby(final WebSession newSession) {
        session = newSession;
        guiBase.setActiveSession(newSession);
        newSession.setActionHandler(this::onLobbyAction);
        sendSetup(newSession);
    }

    private void sendSetup(final WebSession target) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("decks", WebGameHost.availableDecks());
        payload.put("maxAi", 5);
        payload.put("defaultAi", 1);
        target.send("setup", payload);
    }

    private void onLobbyAction(final String type, final JsonNode payload) {
        if (!"startGame".equals(type)) {
            return; // ignore anything else while in the lobby
        }
        if (!started.compareAndSet(false, true)) {
            return; // a game is already starting/running
        }
        try {
            final WebGameHost.GameConfig config = WebGameHost.GameConfig.from(payload);
            final WebGuiGame gui = new WebGuiGame(session); // takes over the session action handler
            activeGui = gui;
            WebGameHost.startCommander(config, gui);
        } catch (final Exception e) {
            System.out.println("[web] failed to start game: " + e.getMessage());
            started.set(false); // let the player try again
            if (session != null) {
                session.setActionHandler(this::onLobbyAction);
                final Map<String, Object> err = new HashMap<>();
                err.put("message", "Could not start the game: " + e.getMessage());
                session.send("message", err);
            }
        }
    }
}
