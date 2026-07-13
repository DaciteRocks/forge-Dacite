package forge.gui.web.dto;

import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON projection of a {@link CardView}. Public fields are serialized directly by Jackson.
 *
 * <p><b>Hidden information:</b> a {@link CardView} exposes the real name/type even for cards the
 * viewer isn't allowed to see (an opponent's hand, a face-down permanent). We must NOT leak those,
 * so {@link #from} takes the local viewers and, when a card {@code !canBeShownToAny(viewers)},
 * emits only a hidden placeholder (id + zone + tapped + faceDown) with no identity.</p>
 */
public final class CardViewDto {
    public int id;
    public String name;
    public String type;
    public String text;
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
    public boolean sick;
    public Integer attachedToId;
    public Map<String, Integer> counters;

    public static CardViewDto from(final CardView c, final Iterable<PlayerView> viewers) {
        if (c == null) {
            return null;
        }
        final CardViewDto d = new CardViewDto();
        d.id = c.getId();
        d.zone = c.getZone() != null ? c.getZone().name() : null;
        final PlayerView controller = c.getController();
        d.controllerId = controller != null ? controller.getId() : null;
        d.tapped = c.isTapped();

        // Hidden to the viewers (opponent's hand, a face-down permanent): emit only a placeholder —
        // no name/type/text/P-T — so we don't leak information the player isn't allowed to see.
        if (!c.canBeShownToAny(viewers)) {
            d.faceDown = true;
            return d;
        }

        d.name = c.getName();
        d.text = c.getText();
        d.damage = c.getDamage();
        d.attacking = c.isAttacking();
        d.blocking = c.isBlocking();
        d.faceDown = c.isFaceDown();
        // isSick(): summoning-sick *creature on the battlefield*, not hasSickness() which also
        // flags a just-played land/artifact (isFirstTurnControlled && !haste, ignoring type/zone).
        d.sick = c.isSick();
        final CardView attachedTo = c.getAttachedTo();
        d.attachedToId = attachedTo != null ? attachedTo.getId() : null;
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

    /** Ids of a collection of cards (e.g. equipment/auras attached to a permanent), or null if none. */
    static List<Integer> idsOf(final Iterable<CardView> cards) {
        if (cards == null) {
            return null;
        }
        final List<Integer> out = new ArrayList<>();
        for (final CardView c : cards) {
            if (c != null) {
                out.add(c.getId());
            }
        }
        return out.isEmpty() ? null : out;
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

    static List<CardViewDto> fromAll(final Iterable<CardView> cards, final Iterable<PlayerView> viewers) {
        if (cards == null) {
            return null;
        }
        final List<CardViewDto> out = new ArrayList<>();
        for (final CardView c : cards) {
            out.add(from(c, viewers));
        }
        return out;
    }
}
