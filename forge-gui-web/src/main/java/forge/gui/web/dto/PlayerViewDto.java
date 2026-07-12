package forge.gui.web.dto;

import forge.game.card.CardView;
import forge.game.player.PlayerView;

import java.util.List;

/**
 * JSON projection of a {@link PlayerView}. Zones are serialized as the View exposes them, which
 * already respects visibility (e.g. an opponent's {@code getHand()} yields only revealed cards),
 * so no extra hidden-information filtering is needed here.
 */
public final class PlayerViewDto {
    public int id;
    public String name;
    public int life;
    public boolean ai;
    public boolean hasPriority;
    public int handSize;
    public int librarySize;
    public List<CardViewDto> hand;
    public List<CardViewDto> battlefield;
    public List<CardViewDto> graveyard;
    public List<CardViewDto> exile;
    public List<CardViewDto> command;
    public List<CardViewDto> commanders;

    public static PlayerViewDto from(final PlayerView p) {
        if (p == null) {
            return null;
        }
        final PlayerViewDto d = new PlayerViewDto();
        d.id = p.getId();
        d.name = p.getLobbyPlayerName();
        d.life = p.getLife();
        d.ai = p.isAI();
        d.hasPriority = p.getHasPriority();
        d.hand = CardViewDto.fromAll(p.getHand());
        d.battlefield = CardViewDto.fromAll(p.getBattlefield());
        d.graveyard = CardViewDto.fromAll(p.getGraveyard());
        d.exile = CardViewDto.fromAll(p.getExile());
        d.command = CardViewDto.fromAll(p.getCommand());
        d.commanders = CardViewDto.fromAll(commandersOf(p));
        d.handSize = count(p.getHand());
        d.librarySize = count(p.getLibrary());
        return d;
    }

    private static Iterable<CardView> commandersOf(final PlayerView p) {
        final List<CardView> commanders = p.getCommanders();
        return commanders;
    }

    private static int count(final Iterable<CardView> cards) {
        if (cards == null) {
            return 0;
        }
        int n = 0;
        for (final CardView ignored : cards) {
            n++;
        }
        return n;
    }
}
