package forge.gui.web.dto;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON projection of the current {@link CombatView}. Combat is modeled as a flat list of bands, one
 * per attacker: which entity it is attacking (a player or a planeswalker) and which creatures are
 * blocking it. The browser highlights attackers/blockers and draws the attacker→defender assignment.
 */
public final class CombatDto {
    public List<Band> bands;

    /** A single attacker, its defender, and its assigned blockers. */
    public static final class Band {
        public int attackerId;
        public String attackerName;
        public Integer defenderId;
        public String defenderName;
        public List<Integer> blockerIds;
    }

    /** Returns null when there is no combat (nothing attacking), so the field is omitted client-side. */
    public static CombatDto from(final CombatView c) {
        if (c == null || c.getNumAttackers() == 0) {
            return null;
        }
        final List<Band> bands = new ArrayList<>();
        for (final CardView attacker : c.getAttackers()) {
            if (attacker == null) {
                continue;
            }
            final Band band = new Band();
            band.attackerId = attacker.getId();
            band.attackerName = attacker.getName();
            final GameEntityView defender = c.getDefender(attacker);
            if (defender != null) {
                band.defenderId = defender.getId();
                band.defenderName = defender.getName();
            }
            band.blockerIds = CardViewDto.idsOf(c.getBlockers(attacker));
            bands.add(band);
        }
        final CombatDto d = new CombatDto();
        d.bands = bands;
        return d;
    }
}
