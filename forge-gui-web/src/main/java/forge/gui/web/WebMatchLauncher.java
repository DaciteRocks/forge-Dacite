package forge.gui.web;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Phase 0 entry point. Installs the headless {@link WebGuiBase}, initializes Forge (loads card
 * data), starts the web host, and — when a browser connects — boots a Commander-vs-AI match with a
 * {@link WebGuiGame} in the human seat (via {@link WebGameHost#startCommanderVsAi}).
 *
 * <p><b>Run</b> from the repo root (so Forge finds {@code res/}), then open
 * {@code http://<lan-ip>:7070} on your phone.</p>
 */
public final class WebMatchLauncher {
    private static final int DEFAULT_PORT = 7070;

    private WebMatchLauncher() {
    }

    public static void main(final String[] args) {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        // Install the headless GUI base and load card data (blocks until the DB is ready).
        final WebGuiBase guiBase = new WebGuiBase();
        GuiBase.setInterface(guiBase);
        System.out.println("[web] initializing Forge (loading card data)...");
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            // Route zone searches (library/graveyard/exile — e.g. a tutor like Three Visits) through
            // the chooseSingleEntityForEffect MODAL instead of the board-click input, since the web
            // UI doesn't render those hidden/compact zones as clickable cards. Battlefield/hand still
            // use board-click, which the web UI does support.
            preferences.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
            return null;
        });
        System.out.println("[web] Forge initialized.");

        final WebServer server = new WebServer(port);
        // Single game per JVM. The first browser gets the lobby (choose opponents + decks); once it
        // sends startGame the match begins. A reconnecting browser rebinds the same session (handled
        // in WebServer) and the lobby either resyncs the running game or re-presents setup.
        final WebLobby lobby = new WebLobby(guiBase);
        server.setOnConnect(lobby::onConnect);
        server.setOnReconnect(lobby::onReconnect);
        server.start();

        // Keep the process alive.
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
