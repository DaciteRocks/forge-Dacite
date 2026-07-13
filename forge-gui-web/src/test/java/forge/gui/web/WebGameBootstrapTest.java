package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tier-2 integration test: boot a real headless Commander-vs-AI game with a {@link WebGuiGame} whose
 * transport records the JSON instead of sending it over a socket, and assert the initial
 * {@code setGameView} the engine emits. This is the executable form of Phase 0's exit criterion.
 *
 * <p>Requires Forge card data ({@code res/}). If the engine can't initialize (no card data in the
 * environment), the test <b>skips</b> rather than fails, so it is safe in the normal suite while
 * still validating the bootstrap where data is present. Run from the repo root for {@code res/}.</p>
 */
public class WebGameBootstrapTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private boolean engineReady;

    @BeforeClass
    public void initEngine() {
        try {
            GuiBase.setInterface(new WebGuiBase());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            engineReady = true;
        } catch (final Throwable t) {
            // No card data (res/) in this environment — the test will skip, not fail.
            engineReady = false;
        }
    }

    @Test(timeOut = 120_000)
    public void bootCommanderEmitsInitialGameView() throws Exception {
        if (!engineReady) {
            throw new SkipException("Forge card data not available in this environment");
        }

        final List<String> recorded = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(recorded::add);
        final WebGuiGame gui = new WebGuiGame(session);

        WebGameHost.startCommanderVsAi(gui);

        // The FIRST setGameView fires at openView, before cards are dealt (prepareAllZones), so
        // libraries are momentarily empty. Poll for a later update where the board is actually
        // dealt (both players have a populated library) — that's "the game is set up and rendering".
        JsonNode gameView = null;
        final long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            final JsonNode candidate = latestSetGameView(recorded);
            if (candidate != null && allPlayersHaveLibrary(candidate)) {
                gameView = candidate;
                break;
            }
            Thread.sleep(100);
        }

        Assert.assertNotNull(gameView, "engine should emit a setGameView with the dealt board");
        final JsonNode players = gameView.get("players");
        Assert.assertEquals(players.size(), 2, "Commander vs AI has exactly 2 players");
        for (final JsonNode p : players) {
            Assert.assertEquals(p.get("life").asInt(), 40, "Commander starts at 40 life");
            Assert.assertTrue(p.path("librarySize").asInt() > 0, "each player has a dealt library");
        }
    }

    private static boolean allPlayersHaveLibrary(final JsonNode gameView) {
        final JsonNode players = gameView.path("players");
        if (players.size() < 2) {
            return false;
        }
        for (final JsonNode p : players) {
            if (p.path("librarySize").asInt() <= 0) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode latestSetGameView(final List<String> recorded) throws Exception {
        JsonNode found = null;
        for (final String msg : recorded) {
            final JsonNode n = MAPPER.readTree(msg);
            if ("setGameView".equals(n.path("type").asText())) {
                found = n.get("payload");
            }
        }
        return found;
    }
}
