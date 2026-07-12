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
import forge.gui.web.dto.GameViewDto;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final WebSession session;

    public WebGuiGame(final WebSession session) {
        this.session = session;
    }

    /** Push the current full game state, if we have one. */
    private void pushGameView() {
        final GameView gv = getGameView();
        if (gv != null && session != null) {
            session.send("setGameView", GameViewDto.from(gv));
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
        }
        session.send("openView", myIds);
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
        session.send("prompt", payload);
    }

    @Override
    public void showCardPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        pushGameView();
        final Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerView != null ? playerView.getId() : null);
        payload.put("message", message);
        payload.put("cardId", card != null ? card.getId() : null);
        session.send("prompt", payload);
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
        session.send("buttons", payload);
    }

    @Override
    public void message(final String message, final String title) {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("message", message);
        session.send("message", payload);
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
        session.send("finishGame", null);
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
        return null;
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
        return null;
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                        final int min, final int max, final DelayedReveal delayedReveal) {
        return null;
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
        session.send("message", payload);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return initialInput;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
                                  final List<T> selected, final FSerializableFunction<T, String> display) {
        return null;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                             final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
                             final CardView referenceCard, final boolean sideboardingMode) {
        return sourceChoices;
    }
}
