package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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

    /** How the session pushes a raw JSON string to the browser (wired to the Javalin WsContext).
     * Volatile + swappable so a reconnecting browser can {@link #rebind} without disturbing the
     * game thread or the pending questions. */
    private volatile Consumer<String> transport;

    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** The still-unanswered question messages, kept so we can re-send them to a reconnected browser. */
    private final ConcurrentHashMap<Long, Message> pendingMessages = new ConcurrentHashMap<>();

    /** Routes a spontaneous player action {@code (type, payload)} to the game (set by WebGuiGame). */
    private volatile BiConsumer<String, JsonNode> actionHandler;

    public WebSession(final Consumer<String> transport) {
        this.transport = transport;
    }

    /** Register the handler that turns inbound player actions into {@code IGameController} calls. */
    public void setActionHandler(final BiConsumer<String, JsonNode> handler) {
        this.actionHandler = handler;
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
        pendingMessages.put(id, msg); // retained so a reconnecting browser can be re-shown the question
        writeJson(msg);

        try {
            return future.get(timeout, unit);
        } catch (final Exception e) {
            return null;
        } finally {
            pending.remove(id);
            pendingMessages.remove(id);
        }
    }

    /**
     * Point this session at a new browser connection (a reconnect) and re-send every still-open
     * question, so the game thread — which may be parked in {@link #sendAndWait} — keeps its answer
     * path alive without ever being unblocked with a default.
     */
    public void rebind(final Consumer<String> newTransport) {
        this.transport = newTransport;
        for (final Message msg : pendingMessages.values()) {
            writeJson(msg);
        }
    }

    /** Drop outbound sends while no browser is attached (a transient disconnect). Pending questions
     * are intentionally NOT failed — the game waits for the browser to reconnect (or time out). */
    public void detach() {
        this.transport = t -> { };
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
            // Otherwise it's a spontaneous player action (passPriority, selectCard, ...): route it
            // to the game controller via the registered handler, if any.
            final BiConsumer<String, JsonNode> handler = actionHandler;
            final JsonNode typeNode = node.get("type");
            if (handler != null && typeNode != null && typeNode.isTextual()) {
                handler.accept(typeNode.asText(), node.get("payload"));
            } else {
                System.out.println("[web] client action (no handler): " + json);
            }
        } catch (final Exception e) {
            System.out.println("[web] failed to parse client message: " + e.getMessage());
        }
    }

    /** Fail all outstanding questions (complete with null) so a game thread parked in
     * {@link #sendAndWait} unblocks — e.g. when abandoning a game to return to the lobby. */
    public void failPending() {
        pending.values().forEach(f -> f.complete(null));
        pending.clear();
        pendingMessages.clear();
    }

    /** Terminal shutdown: fail all outstanding questions so the game thread can't hang forever.
     * Used when the game is ending — a transient disconnect uses {@link #detach} instead, which
     * keeps the questions alive for a reconnect. */
    public void onClose() {
        failPending();
    }

    private void writeJson(final Message msg) {
        try {
            transport.accept(MAPPER.writeValueAsString(msg));
        } catch (final Exception e) {
            System.out.println("[web] failed to serialize " + msg.type + ": " + e.getMessage());
        }
    }
}
