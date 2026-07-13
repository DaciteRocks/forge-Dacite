package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.util.FSerializableFunction;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier-1 tests that a blocking question on {@link WebGuiGame} makes a full round-trip: it emits the
 * right outbound message, blocks the caller, and maps the browser's reply back to the engine's
 * return value. These questions only touch the {@link WebSession}, so no game engine is needed.
 */
public class WebGuiGameDialogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void confirmSendsQuestionAndReturnsTheReply() throws Exception {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final WebGuiGame gui = new WebGuiGame(session);

        final AtomicReference<Boolean> result = new AtomicReference<>();
        final Thread asker = new Thread(() -> result.set(gui.confirm(null, "Pay 2 life?", false, null)));
        asker.start();

        final long id = awaitOutbound(sent, "confirm");
        // The browser replies reply(id, {value:true}); the message value node is therefore {value:true}.
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":{\"value\":true}}");
        asker.join(3000);

        Assert.assertEquals(result.get(), Boolean.TRUE, "confirm should return the browser's yes");
    }

    @Test
    public void getChoicesMapsReplyIndicesBackToChoices() throws Exception {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final WebGuiGame gui = new WebGuiGame(session);
        final FSerializableFunction<String, String> asIs = s -> s;

        final AtomicReference<List<String>> result = new AtomicReference<>();
        final Thread asker = new Thread(() ->
                result.set(gui.getChoices("Pick one", 1, 1, List.of("Ancestral", "Black Lotus", "Timetwister"), null, asIs)));
        asker.start();

        final long id = awaitOutbound(sent, "getChoices");
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":{\"indices\":[1]}}");
        asker.join(3000);

        Assert.assertEquals(result.get(), List.of("Black Lotus"), "the chosen index should map back to its choice");
    }

    @Test
    public void getChoicesFallsBackToMinimumOnCancel() throws Exception {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final WebGuiGame gui = new WebGuiGame(session);
        final FSerializableFunction<String, String> asIs = s -> s;

        final AtomicReference<List<String>> result = new AtomicReference<>();
        // A mandatory min-1 choice that the browser cancels (empty selection) must not come back empty.
        final Thread asker = new Thread(() ->
                result.set(gui.getChoices("Pick one", 1, 1, List.of("A", "B"), null, asIs)));
        asker.start();

        final long id = awaitOutbound(sent, "getChoices");
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":{\"indices\":[]}}");
        asker.join(3000);

        Assert.assertEquals(result.get(), List.of("A"), "an empty reply falls back to the first min choices");
    }

    @Test
    public void getChoicesWithNegativeMinIsAnAcknowledgement() throws Exception {
        // reveal() calls getChoices(msg, -1, -1, items) purely to show cards — an empty reply must
        // come back empty (NOT be forced to the first item), and the client's Confirm must be usable.
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final WebGuiGame gui = new WebGuiGame(session);
        final FSerializableFunction<String, String> asIs = s -> s;

        final AtomicReference<List<String>> result = new AtomicReference<>();
        final Thread asker = new Thread(() ->
                result.set(gui.getChoices("AI can't play these", -1, -1, List.of("Card A", "Card B"), null, asIs)));
        asker.start();

        final long id = awaitOutbound(sent, "getChoices");
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":{\"indices\":[]}}");
        asker.join(3000);

        Assert.assertNotNull(result.get());
        Assert.assertTrue(result.get().isEmpty(), "a reveal/acknowledge must not force-select an item");
    }

    /** Wait for the first outbound message, assert its type, and return its request id. */
    private static long awaitOutbound(final List<String> sent, final String expectedType) throws Exception {
        final long deadline = System.currentTimeMillis() + 3000;
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        Assert.assertFalse(sent.isEmpty(), "a question should have been sent");
        final JsonNode msg = MAPPER.readTree(sent.get(0));
        Assert.assertEquals(msg.get("type").asText(), expectedType);
        return msg.get("id").asLong();
    }
}
