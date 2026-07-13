package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckgenUtil;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boots a Commander game from a lobby configuration: one human seat (driven by the given web GUI)
 * plus a chosen number of AI opponents, each with a chosen deck (a saved deck, a preconstructed
 * deck, or a freshly generated one). Reuses Forge's normal {@link HostedMatch} start path, so the
 * engine cannot tell the seats are browsers rather than desktop windows.
 *
 * <p>Requires {@code FModel} to be initialized and {@code GuiBase} set (see {@link WebMatchLauncher}
 * and the integration test).</p>
 */
public final class WebGameHost {
    /** Deck-choice id for "generate a fresh random Commander deck". */
    static final String RANDOM_DECK = "random";
    private static final String SAVED_PREFIX = "saved:";
    private static final String PRECON_PREFIX = "precon:";
    private static final int MAX_AI = 5;

    private WebGameHost() {
    }

    /** Convenience for the integration test / a quick default: one AI, both decks random. */
    public static HostedMatch startCommanderVsAi(final IGuiGame humanGui) {
        return startCommander(new GameConfig(1, RANDOM_DECK, List.of(RANDOM_DECK)), humanGui);
    }

    /** Build the players from the lobby config and start the match; the first seat is the human. */
    public static HostedMatch startCommander(final GameConfig config, final IGuiGame humanGui) {
        final RegisteredPlayer human = RegisteredPlayer.forCommander(resolveDeck(config.humanDeck, false))
                .setPlayer(GamePlayerUtil.getGuiPlayer());

        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(human);
        final int aiCount = Math.max(1, Math.min(MAX_AI, config.aiCount));
        for (int i = 0; i < aiCount; i++) {
            final String choice = i < config.aiDecks.size() ? config.aiDecks.get(i) : RANDOM_DECK;
            players.add(RegisteredPlayer.forCommander(resolveDeck(choice, true))
                    .setPlayer(GamePlayerUtil.createAiPlayer("Opponent " + (i + 1))));
        }

        final Set<GameType> variants = EnumSet.noneOf(GameType.class);
        final HostedMatch match = new HostedMatch();
        match.startMatch(GameType.Commander, variants, players, human, humanGui);
        return match;
    }

    /** Resolve a deck-choice id to an actual {@link Deck}, falling back to a generated one. */
    private static Deck resolveDeck(final String choice, final boolean forAi) {
        if (choice != null && choice.startsWith(SAVED_PREFIX)) {
            final Deck d = FModel.getDecks().getCommander().get(choice.substring(SAVED_PREFIX.length()));
            if (d != null) {
                return d;
            }
        } else if (choice != null && choice.startsWith(PRECON_PREFIX)) {
            final Deck d = FModel.getDecks().getCommanderPrecons().get(choice.substring(PRECON_PREFIX.length()));
            if (d != null) {
                return d;
            }
        }
        // "random", unknown, or a missing named deck -> generate one.
        return DeckgenUtil.generateCommanderDeck(forAi, GameType.Commander);
    }

    /** The deck choices to offer in the lobby: Random, then saved decks, then preconstructed decks. */
    public static List<Map<String, String>> availableDecks() {
        final List<Map<String, String>> out = new ArrayList<>();
        out.add(deckOption(RANDOM_DECK, "🎲 Random (generated)"));
        addAll(out, FModel.getDecks().getCommander(), SAVED_PREFIX, "");
        addAll(out, FModel.getDecks().getCommanderPrecons(), PRECON_PREFIX, " (precon)");
        return out;
    }

    private static void addAll(final List<Map<String, String>> out, final IStorage<Deck> decks,
                               final String prefix, final String labelSuffix) {
        if (decks == null) {
            return;
        }
        for (final Deck deck : decks) {
            if (deck != null && deck.getName() != null) {
                out.add(deckOption(prefix + deck.getName(), deck.getName() + labelSuffix));
            }
        }
    }

    private static Map<String, String> deckOption(final String id, final String label) {
        final Map<String, String> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("label", label);
        return o;
    }

    /** The lobby's chosen configuration: how many AI, and the deck id for the human and each AI. */
    public static final class GameConfig {
        public final int aiCount;
        public final String humanDeck;
        public final List<String> aiDecks;

        public GameConfig(final int aiCount, final String humanDeck, final List<String> aiDecks) {
            this.aiCount = aiCount;
            this.humanDeck = humanDeck != null ? humanDeck : RANDOM_DECK;
            this.aiDecks = aiDecks != null ? aiDecks : new ArrayList<>();
        }

        /** Parse a {@code startGame} payload: {@code {aiCount, human, ai:[...]}}. Lenient defaults. */
        public static GameConfig from(final JsonNode payload) {
            if (payload == null) {
                return new GameConfig(1, RANDOM_DECK, List.of(RANDOM_DECK));
            }
            final JsonNode aiCountNode = payload.get("aiCount");
            final int aiCount = aiCountNode != null && aiCountNode.canConvertToInt() ? aiCountNode.asInt() : 1;
            final JsonNode humanNode = payload.get("human");
            final String human = humanNode != null && humanNode.isTextual() ? humanNode.asText() : RANDOM_DECK;
            final List<String> aiDecks = new ArrayList<>();
            final JsonNode aiNode = payload.get("ai");
            if (aiNode != null && aiNode.isArray()) {
                for (final JsonNode n : aiNode) {
                    aiDecks.add(n != null && n.isTextual() ? n.asText() : RANDOM_DECK);
                }
            }
            return new GameConfig(aiCount, human, aiDecks);
        }
    }

    /** null if the deck is Commander-legal, else a human-readable problem (for a lobby warning). */
    public static String commanderProblem(final Deck deck) {
        return deck == null ? "no deck" : DeckFormat.Commander.getDeckConformanceProblem(deck);
    }
}
