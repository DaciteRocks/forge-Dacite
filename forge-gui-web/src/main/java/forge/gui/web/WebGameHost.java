package forge.gui.web;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Boots a Commander game: one human seat (driven by the given web GUI) versus one AI, using
 * generated Commander decks. Reuses Forge's normal {@link HostedMatch} start path, so the engine
 * cannot tell the seat is a browser rather than a desktop window.
 *
 * <p>Requires {@code FModel} to be initialized and {@code GuiBase} set (see {@link WebMatchLauncher}
 * and the integration test).</p>
 */
public final class WebGameHost {
    private WebGameHost() {
    }

    public static HostedMatch startCommanderVsAi(final IGuiGame humanGui) {
        final Deck humanDeck = DeckgenUtil.generateCommanderDeck(false, GameType.Commander);
        final Deck aiDeck = DeckgenUtil.generateCommanderDeck(true, GameType.Commander);

        final RegisteredPlayer human = RegisteredPlayer.forCommander(humanDeck)
                .setPlayer(GamePlayerUtil.getGuiPlayer());
        final RegisteredPlayer ai = RegisteredPlayer.forCommander(aiDeck)
                .setPlayer(GamePlayerUtil.createAiPlayer());

        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(human);
        players.add(ai);

        final Set<GameType> variants = EnumSet.noneOf(GameType.class);
        final HostedMatch match = new HostedMatch();
        match.startMatch(GameType.Commander, variants, players, human, humanGui);
        return match;
    }
}
