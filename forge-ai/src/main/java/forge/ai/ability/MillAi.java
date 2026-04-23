package forge.ai.ability;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.ai.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MillAi extends SpellAbilityAi {

    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if (aiLogic.equals("LilianaMill")) {
            // TODO convert to AICheckSVar
            // Only mill if a "Raise Dead" target is available, in case of control decks with few creatures
            return CardLists.filter(ai.getCardsIn(ZoneType.Graveyard), CardPredicates.CREATURES).size() >= 1;
        }
        return true;
    }
    
    @Override
    protected boolean checkPhaseRestrictions(final Player ai, final SpellAbility sa, final PhaseHandler ph) {
        final String aiLogic = sa.getParam("AILogic");
        if ("ExileAndPlayUntilEOT".equals(aiLogic)) {
            return ph.is(PhaseType.MAIN1) && ph.isPlayerTurn(ai); // try to maximize the chance of being able to play the card this turn
        } else if ("ExileAndPlayOrDealDamage".equals(aiLogic)) {
            return (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)) && ph.isPlayerTurn(ai); // Chandra, Torch of Defiance and similar
        } else if ("MillAnytime".equals(aiLogic)) {
            // Dedicated mill payoffs (Phenax grants, Doorkeeper): try to activate on our MAIN2
            // so the mill progresses each turn, but only when it's safe to leave the creature
            // tapped through the intervening opponents' turns. If we'd lose crucial blockers,
            // fall back to the single EOT mill before our turn.
            if (wouldLoopOnSelfUntap(ai, sa)) {
                return false;
            }
            if (ph.is(PhaseType.MAIN2) && ph.isPlayerTurn(ai) && safeToTapBlockerForMill(ai, sa.getHostCard())) {
                return true;
            }
            return ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn().equals(ai);
        }
        if (!sa.isPwAbility()) { // Planeswalker abilities are only activated at sorcery speed
            if ("You".equals(sa.getParam("Defined")) && !(!isSorcerySpeed(sa, ai) && ph.is(PhaseType.END_OF_TURN)
                    && ph.getNextTurn().equals(ai))) {
                return false; // only self-mill at opponent EOT
            }
        }
        if (sa.getHostCard().isCreature() && sa.getPayCosts().hasTapCost()) {
            // creatures with a tap cost to mill (e.g. Doorkeeper) are normally held until the opponent's
            // end step before our turn so they remain available to block. For creatures that cannot attack
            // (Defender keyword, 0 power), we'd lose nothing offensive by tapping on our own turn — but
            // we'd still lose blocking on intervening opponents' turns, which matters in multiplayer.
            // Allow MAIN2 activation only if doing so leaves us with enough untapped blockers.
            final Card host = sa.getHostCard();
            final boolean cannotAttack = host.hasKeyword(Keyword.DEFENDER) || host.getNetPower() <= 0;
            if (cannotAttack && ph.is(PhaseType.MAIN2) && ph.isPlayerTurn(ai)
                    && safeToTapBlockerForMill(ai, host)) {
                return true;
            }
            return ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn().equals(ai);
        }
        return !ph.getPhase().isBefore(PhaseType.MAIN2) || sa.hasParam("ActivationPhases")
                || ComputerUtil.castSpellInMain1(ai, sa);
    }

    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        /*
         * TODO:
         * - logic in targetAI looks dodgy
         * - decide whether to self-mill (eg. delirium, dredge, bad top card)
         * - interrupt opponent's top card (eg. Brainstorm, good top card)
         * - check for Laboratory Maniac effect (needs to check for actual
         * effect due to possibility of "lose abilities" effect)
         */

        if (("You".equals(sa.getParam("Defined")) || "Player".equals(sa.getParam("Defined")))
                && ai.getCardsIn(ZoneType.Library).size() < 10) {
            // prevent self and each player mill when library is small
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        
        if (sa.usesTargeting() && !targetAI(ai, sa, false)) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        if (sa.hasParam("NumCards") && (sa.getParam("NumCards").equals("X") || sa.getParam("NumCards").equals("Z"))
                && sa.getSVar("X").startsWith("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int cardsToDiscard = getNumToDiscard(ai, sa);
            sa.setXManaCostPaid(cardsToDiscard);
            if (cardsToDiscard <= 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAffordX);
            }
        }
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    private boolean targetAI(final Player ai, final SpellAbility sa, final boolean mandatory) {
        final Card source = sa.getHostCard();

        if (sa.usesTargeting()) {
            sa.resetTargets();
            final Map<Player, Integer> list = Maps.newHashMap();
            for (final Player o : ai.getOpponents()) {
                if (!sa.canTarget(o)) {
                    continue;
                }

                int numCards;
                if (sa.hasParam("NumCards")) {
                    // need to set as target for some calculate
                    sa.getTargets().add(o);
                    numCards = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumCards"), sa);
                    sa.getTargets().remove(o);
                } else {
                    numCards = 1;
                }

                // if it would mill none, try other one
                if (numCards <= 0) {
                    if (sa.hasParam("NumCards") && (sa.getParam("NumCards").equals("X") || sa.getParam("NumCards").equals("Z"))) {
                        if (source.getSVar("X").startsWith("Count$xPaid")) {
                            // Spell is PayX based
                        } else if (source.getSVar("X").startsWith("Remembered$ChromaSource")) {
                            // Cards like Sanity Grinding
                        } else {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }

                final CardCollectionView pLibrary = o.getCardsIn(ZoneType.Library);
                if (pLibrary.isEmpty()) {
                    continue;
                }

                // if that player can be milled, select this one.
                if (numCards >= pLibrary.size()) {
                    sa.getTargets().add(o);
                    return true;
                }

                list.put(o, numCards);
            }

            // can't target opponent?
            if (list.isEmpty()) {
                if (mandatory && !sa.isTargetNumberValid() && sa.canTarget(ai)) {
                    sa.getTargets().add(ai);
                    return true;
                }
                // TODO Obscure case when you know what your top card is so you might?
                // want to mill yourself here
                return sa.isTargetNumberValid();
            }

            // In multiplayer, if an opponent is already close to being decked, focus them so we
            // can finish the kill. Otherwise pick the opponent where we'd mill the most — locking
            // early onto one full-library opponent wastes mills while other opponents build a board.
            Map.Entry<Player, Integer> best = null;
            if (ai.getOpponents().size() > 1) {
                final int FINISH_THRESHOLD = 25;
                for (final Map.Entry<Player, Integer> e : list.entrySet()) {
                    int lib = e.getKey().getCardsIn(ZoneType.Library).size();
                    if (lib >= FINISH_THRESHOLD) {
                        continue;
                    }
                    if (best == null || lib < best.getKey().getCardsIn(ZoneType.Library).size()) {
                        best = e;
                    }
                }
            }
            if (best == null) {
                // default: mill the opponent we'd hit hardest
                best = Collections.max(list.entrySet(), Map.Entry.comparingByValue());
            }

            sa.getTargets().add(best.getKey());
        }
        return true;
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        return targetAI(aiPlayer, sa, true) ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (!targetAI(aiPlayer, sa, mandatory)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (sa.hasParam("NumCards") && (sa.getParam("NumCards").equals("X") && sa.getSVar("X").equals("Count$xPaid"))) {
            // Set PayX here to maximum value.
            sa.setXManaCostPaid(getNumToDiscard(aiPlayer, sa));
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#confirmAction(forge.game.player.Player, forge.card.spellability.SpellAbility, forge.game.player.PlayerActionConfirmMode, java.lang.String)
     */
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        if ("TimmerianFiends".equals(sa.getParam("AILogic"))) {
            return SpecialCardAi.TimmerianFiends.consider(player, sa);
        }

        return true;
    }

    /*
     * return num of cards to discard
     */
    private int getNumToDiscard(final Player ai, final SpellAbility sa) {
        // need list of affected players
        List<Player> list = Lists.newArrayList();
        if (sa.usesTargeting()) {
            list.addAll(Lists.newArrayList(sa.getTargets().getTargetPlayers()));
        } else {
            list.addAll(AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("Defined"), sa));
        }

        // get targeted or defined Player with largest library 
        final Player m = Collections.max(list, PlayerPredicates.compareByZoneSize(ZoneType.Library));

        int cardsToDiscard =  m.getCardsIn(ZoneType.Library).size();

        // if ai is in affected list too, try to not mill himself
        if (list.contains(ai)) {
            cardsToDiscard = Math.min(ai.getCardsIn(ZoneType.Library).size() - 5, cardsToDiscard);
        }

        return Math.min(ComputerUtilCost.setMaxXValue(sa, ai, sa.isTrigger()), cardsToDiscard);
    }

    /**
     * Decides whether to stop a self-untap mill loop (e.g. Eater of the Dead + Phenax
     * tap-mill grant). The combo is self-fueling: each cycle mills `t` cards into the
     * target's graveyard; some fraction of those are creatures, which become new fuel for
     * the next untap. Net fuel change per cycle is roughly `t × r − 1`, where `r` is the
     * creature density of the target library (~0.25 for a typical Commander deck, often
     * higher for creature-based decks).
     *
     * If `t × r ≥ 1` the loop self-sustains — we let it run as long as any fuel exists.
     * If `t × r < 1` we compute how many cycles the fuel buys and check whether that's
     * enough to deck the target. Otherwise we cap activations to preserve wall clock.
     */
    private static boolean wouldLoopOnSelfUntap(final Player ai, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null) return false;

        SpellAbility untapLoop = null;
        for (SpellAbility other : host.getAllSpellAbilities()) {
            if (other == sa) continue;
            if (other.getPayCosts() == null || !other.getPayCosts().isAbility()) continue;
            if (!"0".equals(other.getPayCosts().toString().trim())) continue;
            SpellAbility cur = other;
            while (cur != null) {
                if ("Untap".equals(cur.getApi() == null ? null : cur.getApi().name())
                        && "Self".equals(cur.getParam("Defined"))) {
                    untapLoop = other;
                    break;
                }
                cur = cur.getSubAbility();
            }
            if (untapLoop != null) break;
        }
        if (untapLoop == null) return false;

        final int millPerCycle = Math.max(0, host.getNetToughness());
        if (millPerCycle <= 0) return sa.getActivationsThisTurn() >= 3;

        // Current fuel: cards in graveyards matching the untap ability's ValidTgts.
        int fuel = Integer.MAX_VALUE;
        final String validTgts = untapLoop.getParam("ValidTgts");
        final String origin = untapLoop.getParam("Origin");
        final boolean fuelIsCreature = validTgts != null && validTgts.contains("Creature");
        if ("Graveyard".equals(origin) && validTgts != null) {
            fuel = 0;
            for (Player p : ai.getGame().getPlayers()) {
                for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
                    if (fuelIsCreature && c.isCreature()) fuel++;
                    else if (validTgts.contains("Card")) fuel++;
                }
            }
        }

        if (fuel <= 0) return true; // no fuel, nothing the loop can do right now

        // Estimate fuel density in the target library (creature fraction). We can't see the
        // library, but we can infer from visible zones on the assumption decks are roughly
        // homogeneous: combine (graveyard creatures + battlefield creatures + hand size / 4)
        // against total cards the opponent has encountered. Default to 0.25 if unknown.
        if (sa.usesTargeting() && !sa.getTargets().isEmpty()) {
            for (final Player tgt : sa.getTargets().getTargetPlayers()) {
                final int lib = tgt.getCardsIn(ZoneType.Library).size();
                if (lib <= millPerCycle) return false; // one more cycle decks them — go

                final double density = estimateFuelDensity(tgt, fuelIsCreature);
                final double netPerCycle = millPerCycle * density - 1.0;

                if (netPerCycle >= 0) {
                    // Self-sustaining — as long as we have any fuel, the loop runs to target=0.
                    return false;
                }

                // Fuel depletes. Cycles available before fuel hits 0:
                //   F + N*(t*r - 1) >= 0  =>  N <= F / (1 - t*r)
                double cyclesAvailable = fuel / (1.0 - netPerCycle * -1.0);
                // Additional cycles from compounding (approximate): integrate growing grave.
                // Each cycle adds millPerCycle * density creatures and spends 1 fuel, so the
                // effective additional cycles from those compounded gains add N * density * t / 1
                // — expressed more carefully: we can keep going while fuel(n) > 0. Solve geom:
                double cardsMilled = cyclesAvailable * millPerCycle;
                if (cardsMilled >= lib) return false;
            }
        }

        // Can't finish the kill from current fuel + density — cap to preserve clock.
        return sa.getActivationsThisTurn() >= 3;
    }

    private static double estimateFuelDensity(final Player target, final boolean fuelIsCreature) {
        if (!fuelIsCreature) return 1.0; // any-card fuel: every milled card refuels
        int visibleCards = 0;
        int visibleCreatures = 0;
        for (ZoneType z : new ZoneType[] { ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile }) {
            for (Card c : target.getCardsIn(z)) {
                visibleCards++;
                if (c.isCreature()) visibleCreatures++;
            }
        }
        if (visibleCards < 10) return 0.25; // too little info, use Commander default
        return Math.max(0.1, Math.min(0.6, (double) visibleCreatures / visibleCards));
    }

    /**
     * Returns true if it's safe to tap this wall/defender during our MAIN2 to mill, rather than
     * holding it as a blocker. Walls tapped in MAIN2 stay tapped through all opponents' turns in
     * multiplayer — losing that blocking is only acceptable when we aren't under credible
     * combat pressure or still have other defenders.
     */
    private static boolean safeToTapBlockerForMill(final Player ai, final Card self) {
        int oppPressure = 0;
        for (final Player opp : ai.getOpponents()) {
            for (final Card c : opp.getCreaturesInPlay()) {
                if (c.hasKeyword(Keyword.DEFENDER) && !c.hasKeyword("CARDNAME can attack as though it didn't have defender.")) {
                    continue;
                }
                if (c.isTapped() && !c.hasKeyword(Keyword.VIGILANCE)) {
                    // tapped attackers won't hit us this cycle; lightly count them
                    oppPressure += Math.max(0, c.getNetPower()) / 2;
                } else {
                    oppPressure += Math.max(0, c.getNetPower());
                }
            }
        }

        if (oppPressure == 0) {
            return true; // no threats — free to tap everything
        }

        // Count how much blocking we retain if we tap this one away. Summoning-sick creatures
        // can still block — sickness only restricts attacking and activating tap costs — so they
        // count toward remaining defense.
        int remainingToughness = 0;
        int remainingBlockers = 0;
        for (final Card c : ai.getCreaturesInPlay()) {
            if (c == self) {
                continue;
            }
            if (c.isTapped()) {
                continue;
            }
            if (c.hasKeyword("CARDNAME can't block.")) {
                continue;
            }
            remainingToughness += Math.max(0, c.getNetToughness());
            remainingBlockers++;
        }

        // Safe if we can still absorb the incoming pressure, or if life is high enough to
        // weather a round of damage even without this blocker.
        if (remainingToughness >= oppPressure) {
            return true;
        }
        if (remainingBlockers >= 2 && ai.getLife() > oppPressure + 10) {
            return true;
        }
        return false;
    }
}
