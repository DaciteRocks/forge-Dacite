package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One connected browser. Owns JSON encoding, the outbound send, and the reply-future pool that
 * lets the (blocking) game thread ask the browser a question and wait for the answer.
 *
 * <p>This mirrors the role of {@code ReplyPool} + {@code sendAndWait} in the Netty network client
 * ({@code forge.gamemodes.net.GameProtocolSender} / {@code RemoteClientGuiGame}) — but over
 * WebSocket + JSON instead of Netty + Java serialization.</p>
 *
 * <p>Phase 0 uses only {@link #send(String, Object)} (fire-and-forget). {@link #sendAndWait} is
 * scaffolded here for Phase 2+ interaction dialogs.</p>
 */
public final class WebSession {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How the session pushes a raw JSON string to the browser (wired to the Javalin WsContext). */
    private final Consumer<String> transport;

    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public WebSession(final Consumer<String> transport) {
        this.transport = transport;
    }

    /** A message envelope: {@code {"type": ..., "id": ..., "payload": ...}}. */
    public static final class Message {
        public String type;
        public Long id;      // present only for questions that expect a reply
        public Object payload;
    }

    /** Fire-and-forget notification to the browser (e.g. render this game state). */
    public void send(final String type, final Object payload) {
        final Message msg = new Message();
        msg.type = type;
        msg.payload = payload;
        writeJson(msg);
    }

    /**
     * Ask the browser a question and block the calling (game) thread until it replies.
     * The browser must echo the {@code id} in its reply: {@code {"replyTo": id, "value": ...}}.
     *
     * @return the parsed {@code value} node, or {@code null} on timeout/disconnect.
     */
    public JsonNode sendAndWait(final String type, final Object payload, final long timeout, final TimeUnit unit) {
        final long id = nextRequestId.getAndIncrement();
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        final Message msg = new Message();
        msg.type = type;
        msg.id = id;
        msg.payload = payload;
        writeJson(msg);

        try {
            return future.get(timeout, unit);
        } catch (final Exception e) {
            return null;
        } finally {
            pending.remove(id);
        }
    }

    /** Called by {@link WebServer} for every message the browser sends. */
    public void onClientMessage(final String json) {
        try {
            final JsonNode node = MAPPER.readTree(json);
            final JsonNode replyTo = node.get("replyTo");
            if (replyTo != null && replyTo.canConvertToLong()) {
                // It's an answer to a pending sendAndWait().
                final CompletableFuture<JsonNode> future = pending.remove(replyTo.asLong());
                if (future != null) {
                    future.complete(node.get("value"));
                }
                return;
            }
            // Otherwise it's a spontaneous player action (passPriority, selectCard, ...).
            // Phase 2 will route node.get("type")/node.get("payload") to the IGameController.
            System.out.println("[web] client action (unhandled in Phase 0): " + json);
        } catch (final Exception e) {
            System.out.println("[web] failed to parse client message: " + e.getMessage());
        }
    }

    /** Fail all outstanding questions so the game thread doesn't hang forever on disconnect. */
    public void onClose() {
        pending.values().forEach(f -> f.complete(null));
        pending.clear();
    }

    private void writeJson(final Message msg) {
        try {
            transport.accept(MAPPER.writeValueAsString(msg));
        } catch (final Exception e) {
            System.out.println("[web] failed to serialize " + msg.type + ": " + e.getMessage());
        }
    }
}
