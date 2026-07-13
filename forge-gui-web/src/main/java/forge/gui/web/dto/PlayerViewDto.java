package forge.gui.web.dto;

import forge.card.MagicColor;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Floating mana by color symbol (W/U/B/R/G/C); only nonzero pools are included, null if empty. */
    public Map<String, Integer> manaPool;
    /** Player counters (poison, energy, experience, …) by name; null if the player has none. */
    public Map<String, Integer> counters;
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
        d.commanders = CardViewDto.fromAll(p.getCommanders());
        // getZoneSize null-guards the underlying collection (an empty zone's view can be null),
        // so this is safe even before/without the zone being populated.
        d.handSize = p.getZoneSize(ZoneType.Hand);
        d.librarySize = p.getZoneSize(ZoneType.Library);
        d.manaPool = manaPoolOf(p);
        d.counters = CardViewDto.counterMap(p.getCounters());
        return d;
    }

    /** The five colors plus colorless, in WUBRG(C) order, keyed by their one-letter symbol. */
    private static final byte[] MANA_COLORS = {
        MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS,
    };
    private static final String[] MANA_SYMBOLS = {"W", "U", "B", "R", "G", "C"};

    private static Map<String, Integer> manaPoolOf(final PlayerView p) {
        Map<String, Integer> pool = null;
        for (int i = 0; i < MANA_COLORS.length; i++) {
            final int amount = p.getMana(MANA_COLORS[i]);
            if (amount > 0) {
                if (pool == null) {
                    pool = new LinkedHashMap<>();
                }
                pool.put(MANA_SYMBOLS[i], amount);
            }
        }
        return pool;
    }
}
