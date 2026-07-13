package forge.gui.web;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.concurrent.atomic.AtomicReference;

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
            return null;
        });
        System.out.println("[web] Forge initialized.");

        final WebServer server = new WebServer(port);
        // Single game per JVM. The first browser starts the match; a reconnecting browser rebinds
        // the same session (handled in WebServer) and we push a full resync so it repaints.
        final AtomicReference<WebGuiGame> activeGui = new AtomicReference<>();
        server.setOnConnect(session -> {
            guiBase.setActiveSession(session);
            System.out.println("[web] session ready; starting Commander vs AI.");
            final WebGuiGame gui = new WebGuiGame(session);
            activeGui.set(gui);
            WebGameHost.startCommanderVsAi(gui);
        });
        server.setOnReconnect(session -> {
            guiBase.setActiveSession(session);
            final WebGuiGame gui = activeGui.get();
            if (gui != null) {
                System.out.println("[web] resyncing reconnected browser.");
                gui.resync();
            }
        });
        server.start();

        // Keep the process alive.
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
