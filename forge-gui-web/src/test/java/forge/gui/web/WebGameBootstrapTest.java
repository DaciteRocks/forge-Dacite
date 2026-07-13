package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tier-2 integration test: boot a real headless Commander-vs-AI game with a {@link WebGuiGame} whose
 * transport records the JSON instead of sending it over a socket, and assert the initial
 * {@code setGameView} the engine emits. This is the executable form of Phase 0's exit criterion.
 *
 * <p>Requires Forge card data ({@code res/cardsfolder}). If that data isn't present in the working
 * directory, the test <b>skips</b> — but if the data IS present and initialization fails, the test
 * <b>fails</b> (we do not swallow real regressions). Run from the repo root so {@code res/} resolves.</p>
 */
public class WebGameBootstrapTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private boolean engineReady;
    private HostedMatch match;

    /** Card data lives under one of these (depending on where the suite is launched from). */
    private static boolean cardDataPresent() {
        for (final String base : new String[] {"res/cardsfolder", "forge-gui/res/cardsfolder", "../forge-gui/res/cardsfolder"}) {
            if (new File(base).isDirectory()) {
                return true;
            }
        }
        return false;
    }

    @BeforeClass
    public void initEngine() {
        if (!cardDataPresent()) {
            // No card data in this environment — the test will skip. We deliberately do NOT catch
            // Throwable around initialize(): if data is present, a failure there is a real regression.
            engineReady = false;
            return;
        }
        GuiBase.setInterface(new WebGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        engineReady = true;
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (match != null) {
            match.endCurrentGame();
            match = null;
        }
    }

    @Test(timeOut = 120_000)
    public void bootCommanderEmitsInitialGameView() throws Exception {
        if (!engineReady) {
            throw new SkipException("Forge card data (res/cardsfolder) not available in this environment");
        }

        final List<String> recorded = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(recorded::add);
        final WebGuiGame gui = new WebGuiGame(session);

        match = WebGameHost.startCommanderVsAi(gui);

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

        // Phase is part of the header the browser renders — assert it's populated. (Turn is
        // intentionally not asserted: cards are dealt before turn 1's untap, so turn is still 0 here.)
        Assert.assertFalse(gameView.path("phase").asText().isEmpty(), "the current phase should be reported");

        final JsonNode players = gameView.get("players");
        Assert.assertEquals(players.size(), 2, "Commander vs AI has exactly 2 players");
        boolean sawCommander = false;
        for (final JsonNode p : players) {
            Assert.assertEquals(p.get("life").asInt(), 40, "Commander starts at 40 life");
            Assert.assertTrue(p.path("librarySize").asInt() > 0, "each player has a dealt library");
            Assert.assertFalse(p.path("name").asText().isEmpty(), "each player has a name");

            // Validate the DTO mapping end-to-end: the commander is a real card with a name and type.
            final JsonNode commanders = p.path("commanders");
            Assert.assertTrue(commanders.isArray() && commanders.size() >= 1,
                    "each Commander player should have at least one commander");
            for (final JsonNode c : commanders) {
                Assert.assertFalse(c.path("name").asText().isEmpty(), "commander card has a name");
                Assert.assertFalse(c.path("type").asText().isEmpty(), "commander card has a type line");
                sawCommander = true;
            }
        }
        Assert.assertTrue(sawCommander, "at least one commander card should be present in the view");
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
