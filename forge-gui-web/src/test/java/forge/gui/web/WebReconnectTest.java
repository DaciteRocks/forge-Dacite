package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for the reconnect plumbing in {@link WebSession}: a dropped socket detaches (without failing
 * the game's pending questions), and the next connection rebinds the same session — re-sending any
 * open question so the game continues instead of restarting.
 */
public class WebReconnectTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void rebindRedirectsSubsequentSendsToTheNewTransport() {
        final List<String> first = new CopyOnWriteArrayList<>();
        final List<String> second = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(first::add);

        session.send("a", null);
        session.rebind(second::add);
        session.send("b", null);

        Assert.assertEquals(first.size(), 1, "the first socket only saw the pre-reconnect message");
        Assert.assertEquals(second.size(), 1, "post-reconnect messages go to the new socket");
    }

    @Test
    public void rebindResendsAnOpenQuestionAndTheReplyStillCompletesIt() throws Exception {
        final List<String> first = new CopyOnWriteArrayList<>();
        final List<String> second = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(first::add);
        final AtomicReference<JsonNode> result = new AtomicReference<>();

        final Thread asker = new Thread(() ->
                result.set(session.sendAndWait("confirm", null, 5, TimeUnit.SECONDS)));
        asker.start();
        final long id = awaitId(first);

        // Socket drops, then the browser reconnects.
        session.detach();
        session.rebind(second::add);

        awaitNonEmpty(second);
        final JsonNode resent = MAPPER.readTree(second.get(0));
        Assert.assertEquals(resent.get("type").asText(), "confirm", "the open question is re-sent on reconnect");
        Assert.assertEquals(resent.get("id").asLong(), id, "re-sent with the same request id");

        // The reconnected browser answers; the original game thread unblocks with that answer.
        session.onClientMessage("{\"replyTo\":" + id + ",\"value\":{\"value\":true}}");
        asker.join(3000);
        Assert.assertNotNull(result.get(), "the game thread should have received the reply");
        Assert.assertTrue(result.get().get("value").asBoolean());
    }

    @Test
    public void detachDoesNotUnblockPendingQuestions() throws Exception {
        // Contrast with onClose(): a transient disconnect must keep the question open for reconnect.
        final List<String> sent = new CopyOnWriteArrayList<>();
        final WebSession session = new WebSession(sent::add);
        final AtomicBoolean done = new AtomicBoolean(false);

        final Thread asker = new Thread(() -> {
            session.sendAndWait("order", null, 2, TimeUnit.SECONDS);
            done.set(true);
        });
        asker.start();
        awaitId(sent);

        session.detach();
        session.send("dropped", null); // must not throw while detached
        Thread.sleep(300);

        Assert.assertFalse(done.get(), "detach must not unblock the question — the game waits for reconnect");
        asker.join(3000); // let the 2s timeout clean it up
    }

    private static long awaitId(final List<String> sent) throws Exception {
        awaitNonEmpty(sent);
        return MAPPER.readTree(sent.get(0)).get("id").asLong();
    }

    private static void awaitNonEmpty(final List<String> list) throws Exception {
        final long deadline = System.currentTimeMillis() + 2000;
        while (list.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        Assert.assertFalse(list.isEmpty(), "expected a message");
    }
}
