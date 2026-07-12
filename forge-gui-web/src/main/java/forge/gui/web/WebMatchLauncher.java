package forge.gui.web;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Phase 0 entry point. Starts the web host; when a browser connects, it creates a {@link WebGuiGame}
 * for that session and (TODO) starts a Commander-vs-AI match with that GUI in the human seat.
 *
 * <p><b>Run:</b> execute this {@code main} from the {@code forge-gui-web} module (working dir at the
 * repo root so Forge finds {@code res/}), then open {@code http://<lan-ip>:7070} on your phone.</p>
 *
 * <h3>The one piece that needs the first build to finalize</h3>
 * Wiring the match is deliberately left as a documented TODO below rather than guessed, because the
 * exact {@code HostedMatch}/lobby bootstrap is easy to get wrong without a compiler in the loop.
 * Model it on how the desktop/network host starts a game:
 * <ul>
 *   <li>{@code forge.gamemodes.net.server.FServerManager} + {@code forge.gamemodes.match.HostedMatch}
 *       — how a hosted match is created and how each seat's {@code IGuiGame} is attached.</li>
 *   <li>{@code forge.gui.GuiBase} / {@code forge.gui.interfaces.IGuiBase} — the process-wide GUI hook
 *       ({@code GuiBase.setInterface(...)}) that headless/desktop entry points set before starting.</li>
 *   <li>{@code forge.model.FModel.initialize(...)} — loads card data / preferences before any match.</li>
 *   <li>{@code forge.game.GameRules} + {@code forge.gamemodes.match.LobbySlot} — Commander rules and
 *       the human/AI seat definitions; put a {@code LobbyPlayerAi} opponent in one seat and the human
 *       (backed by this {@code WebGuiGame}) in the other.</li>
 * </ul>
 * The transport + GUI + DTO pipeline in this module is complete and compiles; only this bootstrap
 * call is stubbed. See {@code docs/Development/Web-Client-Spec.md} §7 Phase 0.
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
        server.setOnConnect(session -> {
            guiBase.setActiveSession(session);
            final WebGuiGame gui = new WebGuiGame(session);
            System.out.println("[web] session ready; WebGuiGame created for the human seat.");
            startCommanderVsAi(gui);
        });
        server.start();

        // Keep the process alive.
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * TODO (first-build task): boot a Commander game vs AI with {@code gui} as the human seat's GUI.
     * Intentionally not implemented blind — see the class Javadoc for the exact classes to model on.
     * Until this is filled in, connecting a browser establishes the WebSocket and constructs the
     * {@link WebGuiGame}, but no match is started, so no {@code setGameView} arrives yet.
     */
    private static void startCommanderVsAi(final WebGuiGame gui) {
        System.out.println("[web] TODO: start Commander-vs-AI match and attach this WebGuiGame "
                + "to the human seat (see WebMatchLauncher Javadoc + Web-Client-Spec.md §7).");
    }
}
