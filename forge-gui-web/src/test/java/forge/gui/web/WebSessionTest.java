package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for the WebSocket protocol plumbing: the JSON envelope, the fire-and-forget send,
 * and the blocking sendAndWait / reply-correlation / disconnect behavior. No engine required.
 */
public class WebSessionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void sendWritesEnvelopeWithTypeAndPayloadAndNullId() throws Exception {
        final List<String> sent = new ArrayList<>();
        final WebSession session = new WebSession(sent::add);

        session.send("prompt", Map.of("message", "your turn"));

        Assert.assertEquals(sent.size(), 1, "one message should be written");
        final JsonNode n = MAPPER.readTree(sent.get(0));
        Assert.assertEquals(n.get("type").asText(), "prompt");
        Assert.assertTrue(n.get("id").isNull(), "notifications carry no request id");
        Assert.assertEquals(n.get("payload").get("message").asText(), "your turn");
    }

    @Test
    public void sendAndWaitCorrelatesReplyById() throws Exception {
        // Written by the asker thread (via sendAndWait) and read by this thread — needs a thread-safe list.
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final AtomicReference<JsonNode> result = new AtomicReference<>();

        final Thread asker = new Thread(() ->
                result.set(session.sendAndWait("getChoices", Map.of("min", 1), 5, TimeUnit.SECONDS)));
        asker.start();

        final long id = awaitFirstMessageId(sent);
        final JsonNode outbound = MAPPER.readTree(sent.get(0));
        Assert.assertEquals(outbound.get("type").asText(), "getChoices");
        Assert.assertTrue(id > 0, "questions carry a positive request id");

        // Browser answers, echoing the id.
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":[42]}");
        asker.join(3000);

        Assert.assertNotNull(result.get(), "sendAndWait should return the reply value");
        Assert.assertEquals(result.get().get(0).asInt(), 42);
    }

    @Test
    public void sendAndWaitReturnsNullOnTimeout() {
        final WebSession session = new WebSession(text -> { });
        final JsonNode reply = session.sendAndWait("confirm", null, 100, TimeUnit.MILLISECONDS);
        Assert.assertNull(reply, "a question with no answer times out to null");
    }

    @Test
    public void onCloseUnblocksPendingQuestions() throws Exception {
        // Written by the asker thread (via sendAndWait) and read by this thread — needs a thread-safe list.
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final AtomicReference<JsonNode> result = new AtomicReference<>();
        final AtomicBoolean done = new AtomicBoolean(false);

        final Thread asker = new Thread(() -> {
            result.set(session.sendAndWait("order", null, 5, TimeUnit.SECONDS));
            done.set(true);
        });
        asker.start();
        awaitFirstMessageId(sent);

        session.onClose();       // browser disconnected mid-question
        asker.join(3000);

        Assert.assertTrue(done.get(), "sendAndWait must return after a disconnect, not hang");
        Assert.assertNull(result.get());
    }

    @Test
    public void spontaneousActionMessageIsIgnoredNotThrown() {
        final List<String> sent = new ArrayList<>();
        final WebSession session = new WebSession(sent::add);
        session.onClientMessage("{\"type\":\"passPriority\"}"); // Phase 2 routes this; Phase 0 no-ops
        Assert.assertTrue(sent.isEmpty(), "an unsolicited action must not trigger any outbound message");
    }

    @Test
    public void malformedClientMessageIsIgnored() {
        final List<String> sent = new ArrayList<>();
        final WebSession session = new WebSession(sent::add);
        session.onClientMessage("not json at all");
        Assert.assertTrue(sent.isEmpty(), "malformed input must be swallowed, not echoed or answered");
    }

    @Test
    public void spontaneousActionIsRoutedToActionHandlerWithTypeAndPayload() {
        final List<String> routed = new ArrayList<>();
        final WebSession session = new WebSession(text -> { });
        session.setActionHandler((type, payload) ->
                routed.add(type + ":" + (payload != null ? payload.path("cardId").asInt(-1) : -1)));

        session.onClientMessage("{\"type\":\"selectCard\",\"payload\":{\"cardId\":42}}");

        Assert.assertEquals(routed.size(), 1, "the action should be routed exactly once");
        Assert.assertEquals(routed.get(0), "selectCard:42", "type and payload must reach the handler intact");
    }

    @Test
    public void replyIsCompletedNotRoutedAsAction() throws Exception {
        // A reply to a pending question must complete its future, never fire the action handler.
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> actions = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        session.setActionHandler((type, payload) -> actions.add(type));
        final AtomicReference<JsonNode> result = new AtomicReference<>();

        final Thread asker = new Thread(() ->
                result.set(session.sendAndWait("confirm", null, 5, TimeUnit.SECONDS)));
        asker.start();
        final long id = awaitFirstMessageId(sent);

        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":true}");
        asker.join(3000);

        Assert.assertTrue(actions.isEmpty(), "a reply must not be treated as a spontaneous action");
        Assert.assertNotNull(result.get(), "sendAndWait should return the reply");
        Assert.assertTrue(result.get().asBoolean(), "the reply value should round-trip");
    }

    private static long awaitFirstMessageId(final List<String> sent) throws Exception {
        final long deadline = System.currentTimeMillis() + 2000;
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        Assert.assertFalse(sent.isEmpty(), "a message should have been sent");
        return MAPPER.readTree(sent.get(0)).get("id").asLong();
    }
}
