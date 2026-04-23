package forge.game;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Tracks consecutive activations of the same SpellAbility by the same player so opponents
 * can auto-yield priority on repeated deterministic loops (e.g. Eater of the Dead + Phenax
 * tap-mill grant). After the active player repeats an activation N times and each opponent
 * has passed each time, there is no reason for opponents to spend full AI evaluation time
 * on subsequent identical priority windows — the game state from their perspective hasn't
 * meaningfully changed since their last pass decision.
 *
 * Reset whenever the active player changes or a different ability is activated.
 */
public class StackLoopDetector {

    private static final int AUTO_YIELD_THRESHOLD = 3;

    private Player lastActivator;
    private String lastSignature;
    private int consecutiveCount;

    public void recordActivation(final Player activator, final SpellAbility sa) {
        if (activator == null || sa == null) {
            reset();
            return;
        }
        final String sig = signatureOf(sa);
        if (activator.equals(lastActivator) && sig.equals(lastSignature)) {
            consecutiveCount++;
        } else {
            lastActivator = activator;
            lastSignature = sig;
            consecutiveCount = 1;
        }
    }

    public void reset() {
        lastActivator = null;
        lastSignature = null;
        consecutiveCount = 0;
    }

    /**
     * Returns true if {@code forPlayer} can safely auto-pass priority because the active
     * player has been repeating the same activation and this player previously passed on
     * each of those iterations (implied by not interrupting the counter).
     */
    public boolean shouldAutoYield(final Player forPlayer) {
        if (forPlayer == null || lastActivator == null) return false;
        if (forPlayer.equals(lastActivator)) return false;
        return consecutiveCount >= AUTO_YIELD_THRESHOLD;
    }

    public int getConsecutiveCount() {
        return consecutiveCount;
    }

    public Player getLastActivator() {
        return lastActivator;
    }

    private static String signatureOf(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final String hostKey = host != null ? host.getName() + "#" + host.getId() : "?";
        final String api = sa.getApi() != null ? sa.getApi().name() : "?";
        final String desc = sa.getDescription() != null ? sa.getDescription() : "";
        return hostKey + "|" + api + "|" + desc;
    }
}
