package forge.gui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.gui.web.WebGameHost.GameConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tier-1 tests for parsing the lobby's {@code startGame} payload into a {@link GameConfig}. Pure
 * parsing — no engine — so it validates the wire contract without booting a game.
 */
public class WebGameConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GameConfig parse(final String json) throws Exception {
        return GameConfig.from(MAPPER.readTree(json));
    }

    @Test
    public void parsesFullConfig() throws Exception {
        final GameConfig c = parse("{\"aiCount\":3,\"human\":\"saved:Melon Lord\",\"ai\":[\"random\",\"precon:Foo\",\"saved:Bar\"]}");
        Assert.assertEquals(c.aiCount, 3);
        Assert.assertEquals(c.humanDeck, "saved:Melon Lord");
        Assert.assertEquals(c.aiDecks.size(), 3);
        Assert.assertEquals(c.aiDecks.get(1), "precon:Foo");
    }

    @Test
    public void defaultsWhenFieldsMissing() throws Exception {
        final GameConfig c = parse("{}");
        Assert.assertEquals(c.aiCount, 1, "missing aiCount defaults to 1");
        Assert.assertEquals(c.humanDeck, WebGameHost.RANDOM_DECK, "missing human deck defaults to random");
        Assert.assertTrue(c.aiDecks.isEmpty(), "missing ai list -> empty (host fills with random)");
    }

    @Test
    public void nullPayloadYieldsSafeDefault() {
        final GameConfig c = GameConfig.from(null);
        Assert.assertEquals(c.aiCount, 1);
        Assert.assertEquals(c.humanDeck, WebGameHost.RANDOM_DECK);
        Assert.assertEquals(c.aiDecks, java.util.List.of(WebGameHost.RANDOM_DECK));
    }

    @Test
    public void nonTextualDeckEntriesFallBackToRandom() throws Exception {
        final GameConfig c = parse("{\"aiCount\":2,\"ai\":[null,123]}");
        Assert.assertEquals(c.aiDecks.size(), 2);
        Assert.assertEquals(c.aiDecks.get(0), WebGameHost.RANDOM_DECK);
        Assert.assertEquals(c.aiDecks.get(1), WebGameHost.RANDOM_DECK);
    }
}
