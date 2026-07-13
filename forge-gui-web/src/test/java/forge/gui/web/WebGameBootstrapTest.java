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

        final JsonNode players = gameView.get("players");
        Assert.assertEquals(players.size(), 2, "Commander vs AI has exactly 2 players");
        boolean sawCommander = false;
        for (final JsonNode p : players) {
            Assert.assertEquals(p.get("life").asInt(), 40, "Commander starts at 40 life");
            Assert.assertTrue(p.path("librarySize").asInt() > 0, "each player has a dealt library");
            Assert.assertFalse(p.path("name").asText().isEmpty(), "each player has a name");

            // Each Commander player has a commander, tracked by id (the client marks it wherever it
            // is instead of a separate zone).
            final JsonNode commanderIds = p.path("commanderIds");
            Assert.assertTrue(commanderIds.isArray() && commanderIds.size() >= 1,
                    "each Commander player should have at least one commander id");

            // Validate CardViewDto mapping end-to-end on the command zone: the commander is a real
            // card with a name, type, and rules text.
            for (final JsonNode c : p.path("command")) {
                if (idsContain(commanderIds, c.path("id").asInt(-1))) {
                    Assert.assertFalse(c.path("name").asText().isEmpty(), "commander card has a name");
                    Assert.assertFalse(c.path("type").asText().isEmpty(), "commander card has a type line");
                    Assert.assertFalse(c.path("text").asText().isEmpty(), "commander card has rules text");
                    sawCommander = true;
                }
            }
        }
        Assert.assertTrue(sawCommander, "the commander card should be present in the command zone");

        // Phase 2: prove the interaction loop reaches the engine. The dealt board sits at the
        // mulligan decision (turn 0, no phase). Repeatedly send selectButtonOk — the universal
        // "proceed" click that keeps the opening hand at mulligan and passes priority afterwards —
        // and assert the game advances into a real turn/phase. That can only happen if the action
        // routed through WebGuiGame -> IGameController -> the blocking input latch.
        final String startPhase = phaseKey(gameView);
        JsonNode advancedView = null;
        final long passDeadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < passDeadline) {
            final JsonNode gv = latestSetGameView(recorded);
            if (gv != null && !phaseKey(gv).equals(startPhase)) {
                advancedView = gv;
                break;
            }
            session.onClientMessage("{\"type\":\"selectButtonOk\"}");
            Thread.sleep(150);
        }
        Assert.assertNotNull(advancedView, "clicking OK from the web client should advance the game past " + startPhase);
        final String advancedPhase = advancedView.path("phase").asText();
        Assert.assertNotEquals(advancedPhase, "null", "the advanced view should report a real phase");
        Assert.assertFalse(advancedPhase.isEmpty(), "the advanced view should report a real phase");

        // Reconnect resync re-sends the seat + the cached board snapshot (never reads the live
        // GameView off the game thread). Proves the snapshot was cached during play.
        final long viewsBefore = countType(recorded, "setGameView");
        gui.resync();
        Assert.assertTrue(countType(recorded, "setGameView") > viewsBefore, "resync should re-send the cached game view");
        Assert.assertTrue(countType(recorded, "openView") >= 1, "resync should re-send the seat (openView)");
    }

    private static long countType(final List<String> recorded, final String type) {
        return recorded.stream().filter(m -> m.contains("\"type\":\"" + type + "\"")).count();
    }

    private static String phaseKey(final JsonNode gameView) {
        return gameView.path("turn").asInt() + ":" + gameView.path("phase").asText();
    }

    private static boolean idsContain(final JsonNode idArray, final int id) {
        for (final JsonNode n : idArray) {
            if (n.asInt() == id) {
                return true;
            }
        }
        return false;
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
