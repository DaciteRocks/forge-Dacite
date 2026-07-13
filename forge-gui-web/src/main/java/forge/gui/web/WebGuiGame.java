package forge.gui.web;

import forge.LobbyPlayer;
import forge.ai.GameState;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gui.FThreads;
import forge.gui.web.dto.CardViewDto;
import forge.gui.web.dto.GameViewDto;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side {@link forge.gui.interfaces.IGuiGame} that renders to a browser over WebSocket + JSON.
 *
 * <p>This is the web sibling of {@code forge.gamemodes.net.server.RemoteClientGuiGame}: the engine
 * calls the {@code IGuiGame} contract exactly as it does for a native network client, and each call
 * is turned into a JSON message for the browser. Most of the interface is implemented by
 * {@code AbstractGuiGame}/{@code NetworkGuiGame}; this class implements the ~30 methods those bases
 * leave abstract.</p>
 *
 * <p><b>Phase 0 scope:</b> full-state push (serialize the whole {@link GameView} on every update) and
 * render-oriented notifications. The interactive <em>questions</em> (targeting, ordering, damage,
 * choices) return safe defaults here and become real {@code session.sendAndWait(...)} round-trips in
 * Phases 2–3. See {@code docs/Development/Web-Client-Spec.md}.</p>
 */
public final class WebGuiGame extends NetworkGuiGame {
    /** How long a blocking question waits for the human before giving up (returns the safe default). */
    private static final long DECISION_TIMEOUT_MINUTES = 60;

    private final WebSession session;

    /** The player this browser controls, captured from {@link #openView}. Preferred over the
     * engine's mutable {@code currentPlayer} for routing actions to the right controller. */
    private volatile PlayerView localPlayer;

    /** The seat ids sent in openView, retained so a reconnect can re-send them for a full resync. */
    private volatile List<Integer> myPlayerIds = new ArrayList<>();

    /** The most recent game-state snapshot, built on the game thread. A reconnect re-sends THIS
     * (an immutable DTO) rather than reading the live GameView off-thread, which would race the
     * engine mutating its trackable collections. */
    private volatile GameViewDto lastGameView;

    /** Set once we've left this game (concede or leaving after game-end). Ensures the leave path runs
     * exactly once, and silences all further outbound messages so the engine's deferred game-over
     * callbacks can't repaint a dead board over the lobby the browser was just returned to. */
    private final AtomicBoolean leaving = new AtomicBoolean(false);
    private volatile boolean silenced;

    /** Return-to-lobby callback (set by the lobby that owns this game). */
    private volatile Runnable onLeaveGame;

    public void setOnLeaveGame(final Runnable r) {
        this.onLeaveGame = r;
    }

    public WebGuiGame(final WebSession session) {
        this.session = session;
        if (session != null) {
            session.setActionHandler(this::onClientAction);
        }
    }

    /** Null-safe notification send — the session may be null (e.g. a GUI created before connect).
     * Silenced once we leave the game, so late engine callbacks can't repaint over the lobby. */
    private void send(final String type, final Object payload) {
        if (session != null && !silenced) {
            session.send(type, payload);
        }
    }

    /** Ask the browser a question and block until the reply (or timeout/disconnect → null). */
    private JsonNode ask(final String type, final Object payload) {
        if (session == null) {
            return null;
        }
        return session.sendAndWait(type, payload, DECISION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    // ── Inbound player actions (browser → IGameController) ────────────────────
    // A click in the browser arrives here; we marshal to the game (EDT) thread and invoke the
    // controller, exactly as the Netty server's GameProtocolHandler does for a native net client.

    private void onClientAction(final String type, final JsonNode payload) {
        FThreads.invokeInEdtNowOrLater(() -> dispatchAction(type, payload));
    }

    private void dispatchAction(final String type, final JsonNode payload) {
        if ("requestResync".equals(type)) {
            resync(); // pure state refresh; needs no controller
            return;
        }
        if ("leaveMatch".equals(type)) {
            leaveToLobby(false); // leaving an already-finished game; tear down now (thread settled)
            return;
        }
        // Route to the controller for the player this browser owns (captured in openView), rather
        // than the engine's mutable currentPlayer, so a click can't be misrouted or dropped during
        // a transient current-player change. Falls back to currentPlayer if openView hasn't run yet.
        final PlayerView seat = localPlayer;
        final IGameController controller = seat != null ? getGameController(seat) : getGameController();
        if (controller == null) {
            return;
        }
        try {
            switch (type) {
                case "selectButtonOk": controller.selectButtonOk(); break;
                case "selectButtonCancel": controller.selectButtonCancel(); break;
                case "passPriority": controller.passPriority(); break;
                case "passPriorityUntilEndOfTurn": controller.passPriorityUntilEndOfTurn(); break;
                case "concede": controller.concede(); leaveToLobby(true); break;
                case "undoLastAction": controller.undoLastAction(); break;
                case "alphaStrike": controller.alphaStrike(); break;
                case "selectCard": {
                    final CardView card = findCard(intField(payload, "cardId"));
                    if (card != null) {
                        controller.selectCard(card, Collections.emptyList(), null);
                    }
                    break;
                }
                case "selectPlayer": {
                    final PlayerView player = findPlayer(intField(payload, "playerId"));
                    if (player != null) {
                        controller.selectPlayer(player, null);
                    }
                    break;
                }
                case "useMana": controller.useMana((byte) intField(payload, "color")); break;
                default: System.out.println("[web] unhandled action: " + type);
            }
        } catch (final Exception e) {
            System.out.println("[web] action '" + type + "' failed: " + e.getMessage());
        }
    }

    /**
     * Leave this game and return the browser to the lobby. Runs once. Immediately: silence further
     * board output, unblock any parked question, and swap the browser to the lobby. The engine
     * match-teardown (QUIT) is optionally deferred so it doesn't run {@code endCurrentGame} while the
     * game thread is still unwinding the just-ended game (a concurrent-modification race).
     *
     * @param deferTeardown true for a programmatic leave right at game-over (concede) — defer the
     *                      QUIT so the game thread settles first; false when the human clicks away
     *                      from an already-finished game (the game thread has long settled).
     */
    private void leaveToLobby(final boolean deferTeardown) {
        if (!leaving.compareAndSet(false, true)) {
            return;
        }
        silenced = true;
        if (session != null) {
            session.failPending(); // unblock a game thread parked in a question
        }
        final Runnable leave = onLeaveGame;
        if (leave != null) {
            leave.run(); // return the browser to the lobby now
        }
        if (deferTeardown) {
            FThreads.delayInEDT(750, this::sendQuit);
        } else {
            sendQuit();
        }
    }

    /** Tell the engine to end the match (QUIT). Safe to call when the controller is already gone. */
    private void sendQuit() {
        final PlayerView seat = localPlayer;
        final IGameController controller = seat != null ? getGameController(seat) : getGameController();
        if (controller != null) {
            controller.nextGameDecision(NextGameDecision.QUIT);
        }
    }

    private static int intField(final JsonNode payload, final String field) {
        if (payload == null) {
            return -1;
        }
        final JsonNode n = payload.get(field);
        return n != null && n.canConvertToInt() ? n.asInt() : -1;
    }

    /** Find a card by id across every player's visible zones in the current game view. */
    private CardView findCard(final int id) {
        final GameView gv = getGameView();
        if (id < 0 || gv == null || gv.getPlayers() == null) {
            return null;
        }
        for (final PlayerView p : gv.getPlayers()) {
            final CardView c = firstWithId(id, p.getHand(), p.getBattlefield(), p.getCommand(),
                    p.getGraveyard(), p.getExile(), p.getCommanders());
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    @SafeVarargs
    private static CardView firstWithId(final int id, final Iterable<CardView>... zones) {
        for (final Iterable<CardView> zone : zones) {
            if (zone == null) {
                continue;
            }
            for (final CardView c : zone) {
                if (c != null && c.getId() == id) {
                    return c;
                }
            }
        }
        return null;
    }

    private PlayerView findPlayer(final int id) {
        final GameView gv = getGameView();
        if (id < 0 || gv == null || gv.getPlayers() == null) {
            return null;
        }
        for (final PlayerView p : gv.getPlayers()) {
            if (p.getId() == id) {
                return p;
            }
        }
        return null;
    }

    /** Push the current full game state, if we have one. Runs on the game thread (engine callback),
     * so this is the only place the live GameView is read; the snapshot is cached for reconnects. */
    private void pushGameView() {
        final GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        // Pass the local players so the DTO hides cards they can't see (opponent hands, etc.).
        // canBeShownToAny(empty) is fail-OPEN (shows everything), so never push with no known viewer:
        // fall back to the captured seat, and if we don't even know that yet, skip this push — the
        // next one (once the seat is registered) sends the full state. Fail closed, never leak.
        Set<PlayerView> viewers = getLocalPlayers();
        if (viewers == null || viewers.isEmpty()) {
            if (localPlayer == null) {
                return;
            }
            viewers = Collections.singleton(localPlayer);
        }
        final GameViewDto dto = GameViewDto.from(gv, viewers);
        lastGameView = dto;
        send("setGameView", dto);
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Override
    public void setGameView(final GameView gameView) {
        super.setGameView(gameView);
        pushGameView();
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        final List<Integer> myIds = new ArrayList<>();
        if (myPlayers != null) {
            for (final PlayerView p : myPlayers) {
                myIds.add(p.getId());
            }
            if (localPlayer == null && !myPlayers.isEmpty()) {
                localPlayer = myPlayers.iterator().next(); // single web seat: the human
            }
        }
        myPlayerIds = myIds;
        send("openView", myIds);
        pushGameView();
    }

    /** Re-send the seat and the last game-state snapshot — used when a browser reconnects after a
     * dropped socket, so it repaints from scratch. Safe to call from any thread: it re-sends the
     * cached immutable DTO instead of reading the live GameView. Any open question is re-sent by
     * {@link WebSession#rebind}. */
    public void resync() {
        send("openView", myPlayerIds);
        final GameViewDto dto = lastGameView;
        if (dto != null) {
            send("setGameView", dto);
        }
    }

    @Override
    public void handleGameEvent(final GameEvent event) {
        pushGameView();
    }

    @Override
    public void handleGameEvents(final List<GameEvent> events) {
        pushGameView();
    }

    // ── Prompts / notifications ──────────────────────────────────────────────

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message) {
        pushGameView();
        final Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerView != null ? playerView.getId() : null);
        payload.put("message", message);
        send("prompt", payload);
    }

    @Override
    public void showCardPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        pushGameView();
        final Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerView != null ? playerView.getId() : null);
        payload.put("message", message);
        payload.put("cardId", card != null ? card.getId() : null);
        send("prompt", payload);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2,
                              final boolean enable1, final boolean enable2, final boolean focus1) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("ownerId", owner != null ? owner.getId() : null);
        payload.put("label1", label1);
        payload.put("label2", label2);
        payload.put("enable1", enable1);
        payload.put("enable2", enable2);
        payload.put("focus1", focus1);
        send("buttons", payload);
    }

    @Override
    public void message(final String message, final String title) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("message", message);
        send("message", payload);
    }

    @Override
    public void showCombat() {
        pushGameView();
    }

    @Override
    public void showManaPool(final PlayerView player) {
        pushGameView();
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        pushGameView();
    }

    @Override
    public void setCard(final CardView card) {
        pushGameView();
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
        pushGameView();
    }

    @Override
    public void finishGame() {
        // Natural game end (not a concede — that path has already silenced us). Show the outcome and
        // let the player leave via a "Back to Menu" button (leaveMatch), rather than auto-quitting
        // (which would wipe the result and, in a best-of-N match, skip the remaining games).
        final GameView gv = getGameView();
        final Map<String, Object> payload = new HashMap<>();
        payload.put("winner", gv != null ? gv.getWinningPlayerName() : null);
        send("gameOver", payload);
    }

    // Selectable-card affordances: tell the browser which cards the engine will accept a click on,
    // so it can highlight/enable them. Clicking a non-selectable card is harmless (the engine's
    // input simply ignores it), but the hint makes the legal plays obvious.
    @Override
    public void setSelectables(final Iterable<CardView> cards) {
        super.setSelectables(cards);
        final List<Integer> ids = new ArrayList<>();
        if (cards != null) {
            for (final CardView c : cards) {
                if (c != null) {
                    ids.add(c.getId());
                }
            }
        }
        send("selectables", ids);
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        send("selectables", Collections.emptyList());
    }

    // Render-neutral notifications — no-op in Phase 0.
    @Override public void alertUser() { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void flashIncorrectAction() { }
    @Override public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) { }
    @Override public void updateShards(final Iterable<PlayerView> shardsUpdate) { }
    @Override public void hideZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public void restoreOldZones(final PlayerView playerView, final PlayerZoneUpdates playerZoneUpdates) { }

    // ── Bookkeeping ──────────────────────────────────────────────────────────

    @Override
    public boolean isNetGame() {
        return true;
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return false;
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        // Browser derives the active player from GameView.playerTurnId.
    }

    @Override
    public GameState getGamestate() {
        return null;
    }

    // ── Interactive questions — real sendAndWait round-trips ─────────────────────
    // Each blocking question serializes its options, asks the browser, and maps the reply back to
    // the engine's objects by index. On timeout/disconnect/cancel (ask() -> null) we fall back to a
    // safe default (skip if optional, first `min` if mandatory) so the game thread never wedges.

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
                                             final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0); // no ambiguity — nothing to ask
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("hostCardId", hostCard != null ? hostCard.getId() : null);
        payload.put("hostName", hostCard != null ? hostCard.getName() : null);
        final List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < abilities.size(); i++) {
            final Map<String, Object> option = new HashMap<>();
            option.put("index", i);
            option.put("description", abilities.get(i).getDescription());
            options.add(option);
        }
        payload.put("abilities", options);

        final JsonNode reply = ask("getAbilityToPlay", payload);
        if (reply == null) {
            return null; // timed out / disconnected / cancelled
        }
        final JsonNode index = reply.get("index");
        if (index == null || !index.canConvertToInt()) {
            return null;
        }
        final int i = index.asInt();
        return i >= 0 && i < abilities.size() ? abilities.get(i) : null;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers,
                                                     final int damage, final GameEntityView defender,
                                                     final boolean overrideOrder, final boolean maySkip) {
        // NOTE: returning null is NOT "auto-assign" — the combat resolver treats it as "assign a
        // different attacker first" and re-queues this one, which would loop forever on the last
        // attacker. So we must always return a complete, legal map here.
        final List<CardView> bl = blockers != null ? blockers : new ArrayList<>();
        final boolean hasDefender = defender != null;
        if (!overrideOrder) {
            // Standard rules: assign lethal to each blocker in the already-chosen order, then any
            // trample excess to the defender (a null map key). Always a legal assignment.
            return autoAssignCombat(bl, damage, hasDefender);
        }

        // "Divide combat damage as you choose": the player freely distributes among blockers and,
        // if present, the defender. Targets = blockers, then the defender as a trailing entry.
        final List<Map<String, Object>> targets = new ArrayList<>();
        for (int i = 0; i < bl.size(); i++) {
            final CardView b = bl.get(i);
            final Map<String, Object> o = new HashMap<>();
            o.put("index", i);
            o.put("label", b.getName());
            o.put("lethal", lethalDamage(b));
            targets.add(o);
        }
        if (hasDefender) {
            final Map<String, Object> o = new HashMap<>();
            o.put("index", bl.size());
            o.put("label", defender.getName());
            targets.add(o);
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("attackerName", attacker != null ? attacker.getName() : null);
        payload.put("damage", damage);
        payload.put("blockers", targets);

        final int slots = bl.size() + (hasDefender ? 1 : 0);
        final List<Integer> amounts = WebDialogs.validateDistribution(
                WebDialogs.intArray(ask("assignDamage", payload), "amounts"), slots, damage, 0);
        if (amounts == null) {
            return autoAssignCombat(bl, damage, hasDefender); // bad/absent reply -> legal default
        }
        final Map<CardView, Integer> result = new HashMap<>();
        for (int i = 0; i < bl.size(); i++) {
            result.put(bl.get(i), amounts.get(i));
        }
        if (hasDefender) {
            result.put(null, amounts.get(bl.size())); // null key = the defending player/planeswalker
        }
        return result;
    }

    /** Lethal-first assignment in the given blocker order, with trample excess to the defender. */
    private static Map<CardView, Integer> autoAssignCombat(final List<CardView> blockers, final int damage,
                                                           final boolean hasDefender) {
        final Map<CardView, Integer> map = new HashMap<>();
        int remaining = damage;
        for (final CardView b : blockers) {
            final int assign = Math.min(remaining, lethalDamage(b));
            map.put(b, assign);
            remaining -= assign;
        }
        if (remaining > 0) {
            if (hasDefender) {
                map.put(null, remaining); // trample overflow to the defender
            } else if (!blockers.isEmpty()) {
                map.merge(blockers.get(blockers.size() - 1), remaining, Integer::sum);
            }
        }
        return map;
    }

    private static int lethalDamage(final CardView c) {
        final CardView.CardStateView s = c.getCurrentState();
        return s != null ? Math.max(0, s.getToughness() - c.getDamage()) : 1;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
                                                    final int amount, final boolean atLeastOne, final String amountLabel) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        final List<Object> keys = new ArrayList<>(target.keySet());
        final List<String> labels = new ArrayList<>();
        for (final Object key : keys) {
            labels.add(keyLabel(key));
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("atLeastOne", atLeastOne);
        payload.put("label", amountLabel);
        payload.put("options", WebDialogs.options(labels));

        final JsonNode reply = ask("assignAmount", payload);
        // atLeastOne requires every target to receive >= 1; validateDistribution enforces that.
        final List<Integer> amounts = WebDialogs.validateDistribution(
                WebDialogs.intArray(reply, "amounts"), keys.size(), amount, atLeastOne ? 1 : 0);
        if (amounts == null) {
            return null;
        }
        final Map<Object, Integer> result = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            result.put(keys.get(i), amounts.get(i));
        }
        return result;
    }

    private static String keyLabel(final Object key) {
        return key instanceof GameEntityView ? ((GameEntityView) key).getName() : String.valueOf(key);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                      final DelayedReveal delayedReveal, final boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("optional", isOptional);
        payload.put("options", entityOptions(optionList));

        final int idx = WebDialogs.replyIndex(ask("chooseEntity", payload));
        if (idx >= 0 && idx < optionList.size()) {
            return optionList.get(idx);
        }
        return isOptional ? null : optionList.get(0); // mandatory: fall back to the first option
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                        final int min, final int max, final DelayedReveal delayedReveal) {
        final List<GameEntityView> result = new ArrayList<>();
        if (optionList == null || optionList.isEmpty()) {
            return result;
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("min", min);
        payload.put("max", max);
        payload.put("options", entityOptions(optionList));

        final JsonNode reply = ask("chooseEntities", payload);
        for (final int i : WebDialogs.sanitizeSelection(WebDialogs.replyIndices(reply), min, optionList.size())) {
            result.add(optionList.get(i));
        }
        return result;
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
                                             final Iterable<CardView> manipulable, final boolean toTop,
                                             final boolean toBottom, final boolean toAnywhere) {
        return null; // free-form library manipulation UI is deferred (Phase 4 polish)
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("title", title);
        payload.put("options", WebDialogs.options(options));
        final int idx = WebDialogs.replyIndex(ask("optionDialog", payload));
        return idx >= 0 ? idx : defaultOption;
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return null; // no sideboarding in single-game Commander vs AI
    }

    @Override
    public PlayerZoneUpdates openZones(final PlayerView controller, final Collection<ZoneType> zones,
                                       final Map<PlayerView, Object> players, final boolean backupLastZones) {
        return null; // zone-peek viewer is deferred (Phase 4 polish); effects still resolve
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller,
                                                    final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        return zonesToUpdate;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes,
                           final List<String> options) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("question", question);
        payload.put("cardId", c != null ? c.getId() : null);
        payload.put("cardName", c != null ? c.getName() : null);
        payload.put("options", options); // null -> browser shows Yes/No
        payload.put("defaultYes", defaultIsYes);
        return WebDialogs.replyBool(ask("confirm", payload), defaultIsYes);
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
                                     final String noButtonText, final boolean defaultYes) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("title", title);
        payload.put("yesText", yesButtonText);
        payload.put("noText", noButtonText);
        payload.put("defaultYes", defaultYes);
        return WebDialogs.replyBool(ask("confirmDialog", payload), defaultYes);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("message", message);
        send("message", payload);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("title", title);
        payload.put("initial", initialInput);
        payload.put("isNumeric", isNumeric);
        payload.put("options", inputOptions);
        final JsonNode reply = ask("inputDialog", payload);
        if (reply == null) {
            return initialInput; // timeout -> the engine's initial value
        }
        final JsonNode value = reply.get("value");
        return value != null && !value.isNull() ? value.asText() : null; // explicit cancel -> null
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
                                  final List<T> selected, final FSerializableFunction<T, String> display) {
        final List<T> result = new ArrayList<>();
        if (choices == null || choices.isEmpty()) {
            return result;
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("min", min);
        payload.put("max", max);
        payload.put("options", WebDialogs.options(genericLabels(choices, display)));
        final List<Integer> pre = new ArrayList<>();
        if (selected != null) {
            for (final T s : selected) {
                final int i = choices.indexOf(s);
                if (i >= 0) {
                    pre.add(i);
                }
            }
        }
        payload.put("selected", pre);

        final JsonNode reply = ask("getChoices", payload);
        // The engine does NOT null-guard getChoices, so a mandatory (min>=1) choice must never come
        // back empty — sanitizeSelection falls back to the first `min` on a short/cancelled reply.
        for (final int i : WebDialogs.sanitizeSelection(WebDialogs.replyIndices(reply), min, choices.size())) {
            result.add(choices.get(i));
        }
        return result;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                             final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
                             final CardView referenceCard, final boolean sideboardingMode) {
        if (sourceChoices == null || sourceChoices.size() < 2) {
            return sourceChoices; // nothing to order
        }
        // Only handle full-order prompts (order every item, nothing left behind, no pre-filled
        // destination) — the common case: damage order, arranging the top of library, trigger
        // order. Partial/insert-into-list prompts fall back to the engine's order (Phase 4 polish).
        if (remainingObjectsMin != 0 || remainingObjectsMax != 0
                || (destChoices != null && !destChoices.isEmpty())) {
            return sourceChoices;
        }
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("top", top);
        payload.put("options", WebDialogs.options(genericLabels(sourceChoices, null)));

        final List<Integer> orderIdx = WebDialogs.replyIndices(ask("order", payload));
        // Accept only a full permutation of the source; otherwise keep the engine's original order.
        if (orderIdx.size() == sourceChoices.size()
                && new java.util.HashSet<>(orderIdx).size() == sourceChoices.size()) {
            final List<T> ordered = new ArrayList<>();
            for (final int i : orderIdx) {
                if (i < 0 || i >= sourceChoices.size()) {
                    return sourceChoices;
                }
                ordered.add(sourceChoices.get(i));
            }
            return ordered;
        }
        return sourceChoices;
    }

    /** Build choice options carrying each entity's label AND, for cards, its CardViewDto so the
     * browser can show full card detail on long-press (e.g. when searching the library). */
    private List<Map<String, Object>> entityOptions(final List<? extends GameEntityView> list) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        final Iterable<PlayerView> viewers = getLocalPlayers();
        for (int i = 0; i < list.size(); i++) {
            final GameEntityView e = list.get(i);
            final Map<String, Object> o = new HashMap<>();
            o.put("index", i);
            o.put("label", e != null ? e.getName() : "?");
            if (e instanceof CardView) {
                o.put("card", CardViewDto.from((CardView) e, viewers));
            }
            out.add(o);
        }
        return out;
    }

    private static <T> List<String> genericLabels(final List<T> choices, final FSerializableFunction<T, String> display) {
        final List<String> labels = new ArrayList<>();
        if (choices != null) {
            for (final T c : choices) {
                String label;
                try {
                    label = display != null ? display.apply(c) : String.valueOf(c);
                } catch (final Exception e) {
                    label = String.valueOf(c);
                }
                labels.add(label);
            }
        }
        return labels;
    }
}
