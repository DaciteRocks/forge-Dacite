package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import forge.game.player.RegisteredPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-game lobby for the single web seat. When a browser connects, instead of immediately starting a
 * match we send it the available deck choices and let the player pick the number of AI opponents and
 * each deck. When the browser sends {@code startGame}, we validate the decks, build a fresh
 * {@link WebGuiGame} (which takes over the session's action handler for in-game input), and start.
 *
 * <p>Reconnect: if the game has already started, we resync the running game; otherwise we re-present
 * the setup so the reloaded browser sees the lobby again.</p>
 *
 * <p>All connect / reconnect / start transitions are serialized on {@link #lock} so a reconnect can
 * never observe a half-built "started but no gui yet" state and clobber a live game's input handler.</p>
 */
final class WebLobby {
    private final WebGuiBase guiBase;
    private final Object lock = new Object();

    // All guarded by lock.
    private boolean started;
    private WebSession session;
    private WebGuiGame activeGui;

    WebLobby(final WebGuiBase guiBase) {
        this.guiBase = guiBase;
    }

    /** First connection: present the lobby (or resync, if somehow a game is already running). */
    void onConnect(final WebSession newSession) {
        synchronized (lock) {
            bind(newSession);
            if (started && activeGui != null) {
                activeGui.resync();
            } else {
                attachLobby(newSession);
            }
        }
    }

    /** Reconnection: resync the running game, or re-present the lobby if it hasn't started. */
    void onReconnect(final WebSession newSession) {
        synchronized (lock) {
            bind(newSession);
            if (started && activeGui != null) {
                activeGui.resync();
            } else {
                attachLobby(newSession);
            }
        }
    }

    /** Match-over callback (concede, or a game/match that ended): tear the game state down and put
     * the browser back on the setup screen. The engine has already ended the game by this point. */
    void returnToLobby() {
        synchronized (lock) {
            started = false;
            activeGui = null;
            final WebSession s = session;
            if (s != null) {
                attachLobby(s); // re-take the action handler from the dead game and re-present setup
            }
        }
    }

    private void bind(final WebSession newSession) {
        session = newSession;
        guiBase.setActiveSession(newSession);
    }

    private void attachLobby(final WebSession target) {
        target.setActionHandler(this::onLobbyAction);
        sendSetup(target, null);
    }

    private void sendSetup(final WebSession target, final String error) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("decks", WebGameHost.availableDecks());
        payload.put("maxAi", 5);
        payload.put("defaultAi", 1);
        if (error != null) {
            payload.put("error", error);
        }
        target.send("setup", payload);
    }

    private void onLobbyAction(final String type, final JsonNode payload) {
        if (!"startGame".equals(type)) {
            return; // ignore anything else while in the lobby
        }

        // Validate + take over the handler atomically. Validation failures leave the lobby open;
        // once we pass the point of no return (started = true, gui installed) we never reset it.
        final List<RegisteredPlayer> players;
        final WebSession s;
        final WebGuiGame gui;
        synchronized (lock) {
            if (started) {
                return; // a game is already starting/running
            }
            s = session;
            try {
                players = WebGameHost.buildPlayers(WebGameHost.GameConfig.from(payload));
            } catch (final Exception e) {
                if (s != null) {
                    sendSetup(s, e.getMessage() != null ? e.getMessage() : "invalid deck selection");
                }
                return; // not started — the player can pick again
            }
            started = true;
            gui = new WebGuiGame(s); // installs its own action handler for in-game input
            activeGui = gui;
        }

        // Launch outside the lock (startMatch spawns the game thread). If it throws we do NOT reset
        // started — a partial match may exist and a second start would double-run the engine.
        try {
            WebGameHost.startMatch(players, gui, this::returnToLobby);
        } catch (final Exception e) {
            System.out.println("[web] startMatch failed: " + e.getMessage());
            if (s != null) {
                final Map<String, Object> err = new HashMap<>();
                err.put("message", "The game failed to start. Please reload to try again.");
                s.send("message", err);
            }
        }
    }
}
