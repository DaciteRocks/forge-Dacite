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
import forge.gamemodes.net.NetworkGuiGame;
import forge.gui.FThreads;
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
import java.util.concurrent.TimeUnit;

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

    public WebGuiGame(final WebSession session) {
        this.session = session;
        if (session != null) {
            session.setActionHandler(this::onClientAction);
        }
    }

    /** Null-safe notification send — the session may be null (e.g. a GUI created before connect). */
    private void send(final String type, final Object payload) {
        if (session != null) {
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
                case "concede": controller.concede(); break;
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

    /** Push the current full game state, if we have one. */
    private void pushGameView() {
        final GameView gv = getGameView();
        if (gv != null) {
            send("setGameView", GameViewDto.from(gv));
        }
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
        send("openView", myIds);
        pushGameView();
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
        send("finishGame", null);
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

    // ── Interactive questions — Phase 0 stubs (Phase 2/3 make these sendAndWait) ──
    // Returning safe defaults so game setup and passing priority work. Actively
    // answering these (targeting, ordering, combat damage, choices) is Phase 2/3.

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
        return null;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
                                                    final int amount, final boolean atLeastOne, final String amountLabel) {
        return null;
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                      final DelayedReveal delayedReveal, final boolean isOptional) {
        // Mandatory choice auto-picks the first option; optional returns null (skip).
        if (!isOptional && optionList != null && !optionList.isEmpty()) {
            return optionList.get(0);
        }
        return null;
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                        final int min, final int max, final DelayedReveal delayedReveal) {
        // Auto-pick the required minimum so mandatory (min>=1) effects don't silently under-select.
        final List<GameEntityView> result = new ArrayList<>();
        if (optionList != null) {
            for (final GameEntityView entity : optionList) {
                if (result.size() >= min) {
                    break;
                }
                result.add(entity);
            }
        }
        return result;
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
                                             final Iterable<CardView> manipulable, final boolean toTop,
                                             final boolean toBottom, final boolean toAnywhere) {
        return null;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        return defaultOption;
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return null;
    }

    @Override
    public PlayerZoneUpdates openZones(final PlayerView controller, final Collection<ZoneType> zones,
                                       final Map<PlayerView, Object> players, final boolean backupLastZones) {
        return null;
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller,
                                                    final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        return zonesToUpdate;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes,
                           final List<String> options) {
        return defaultIsYes;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
                                     final String noButtonText, final boolean defaultYes) {
        return defaultYes;
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
        return initialInput;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
                                  final List<T> selected, final FSerializableFunction<T, String> display) {
        // The engine does NOT null-guard getChoices — returning null would NPE the game thread
        // (e.g. opening-hand abilities via many()). Auto-pick the required minimum; Phase 2 prompts.
        final List<T> result = new ArrayList<>();
        if (choices != null) {
            for (final T choice : choices) {
                if (result.size() >= min) {
                    break;
                }
                result.add(choice);
            }
        }
        return result;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                             final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
                             final CardView referenceCard, final boolean sideboardingMode) {
        return sourceChoices;
    }
}
