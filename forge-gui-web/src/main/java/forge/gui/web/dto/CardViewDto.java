package forge.gui.web.dto;

import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON projection of a {@link CardView} — the read-only, visibility-safe snapshot the engine
 * already produces for a remote GUI. Public fields are serialized directly by Jackson.
 *
 * <p>Phase 0/1 carries the fields a board renderer needs. The View already enforces hidden
 * information (opponent hands, face-down cards), so mirroring it here is safe by construction.</p>
 */
public final class CardViewDto {
    public int id;
    public String name;
    public String type;
    public int power;
    public int toughness;
    public int damage;
    public String loyalty;
    public String manaCost;
    public String zone;
    public Integer controllerId;
    public boolean tapped;
    public boolean attacking;
    public boolean blocking;
    public boolean faceDown;
    public boolean creature;
    public boolean land;
    public Map<String, Integer> counters;

    public static CardViewDto from(final CardView c) {
        if (c == null) {
            return null;
        }
        final CardViewDto d = new CardViewDto();
        d.id = c.getId();
        d.name = c.getName();
        d.damage = c.getDamage();
        d.zone = c.getZone() != null ? c.getZone().name() : null;
        final PlayerView controller = c.getController();
        d.controllerId = controller != null ? controller.getId() : null;
        d.tapped = c.isTapped();
        d.attacking = c.isAttacking();
        d.blocking = c.isBlocking();
        d.faceDown = c.isFaceDown();
        d.counters = counterMap(c.getCounters());

        // Characteristics that depend on the current face/state live on CardStateView.
        final CardView.CardStateView state = c.getCurrentState();
        if (state != null) {
            d.type = state.getType() != null ? state.getType().toString() : null;
            d.power = state.getPower();
            d.toughness = state.getToughness();
            d.loyalty = state.getLoyalty();
            d.manaCost = state.getManaCost() != null ? state.getManaCost().toString() : null;
            d.creature = state.isCreature();
            d.land = state.isLand();
        }
        return d;
    }

    static Map<String, Integer> counterMap(final Map<CounterType, Integer> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final Map.Entry<CounterType, Integer> e : src.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    static List<CardViewDto> fromAll(final Iterable<CardView> cards) {
        if (cards == null) {
            return null;
        }
        final List<CardViewDto> out = new ArrayList<>();
        for (final CardView c : cards) {
            out.add(from(c));
        }
        return out;
    }
}
