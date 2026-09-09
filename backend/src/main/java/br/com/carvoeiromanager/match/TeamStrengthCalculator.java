package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Formation;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleAssignment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure domain service: folds an XI plus its TacticalProfile into a TeamMatchInput.
 * No repository access. See the Phase-2A design spec for the modifier pipeline.
 */
@Service
public class TeamStrengthCalculator {

    private static final double DECAY_BASE = 0.15;
    private static final double MORALE_COEFF = 0.02;   // morale +/-5 -> +/-10% per player, same weight as form
    /** A full side. Attack and outfield defense are divided by this complement, not by the head count. */
    private static final int FULL_XI = 11;

    public TeamMatchInput compute(List<Player> xi, TacticalProfile profile) {
        return compute(xi, profile, Map.of());
    }

    public TeamMatchInput compute(List<Player> xi, TacticalProfile profile,
                                  Map<UUID, Double> fitnessMultById) {
        double attackSum = 0.0;
        double outfieldDefenseSum = 0.0;
        double gkDefense = 0.0;
        double staminaSum = 0.0;
        int outfieldCount = 0;

        for (Player p : xi) {
            // Focus and familiarity are folded INSIDE the RoleAssignment contribution — never
            // re-applied here.
            RoleAssignment role = RoleAssignment.resolve(profile.roles(), p);
            double fitnessMult = fitnessMultById.getOrDefault(p.getId(), 1.0);
            // Position-fit penalty: playing out of a player's detailed position costs effectiveness.
            // A well-formed squad (every player in an accepting slot) is all-exact → x1.0, so the term
            // is neutral and seeded results stay byte-identical. An empty/absent assignment (legacy or
            // AI before Task 31 wired it) also resolves to x1.0.
            double posMult = positionFitMult(p, profile);
            // Each term is exactly 1.0 at its neutral value (form 0, morale 0, full fitness, exact
            // position), so a neutral squad's strength is unchanged — keep it that way to preserve
            // seeded-result determinism.
            double mult = (1.0 + 0.02 * p.getForm()) * (1.0 + MORALE_COEFF * p.getMorale()) * fitnessMult * posMult;
            double attackContribution = role.attackContribution(p) * mult;
            double defenseContribution = role.defenseContribution(p) * mult;
            // Per-player instruction multipliers, applied ONLY when the player carries one. For the
            // empty instructions map (AI/unset) instr is null and neither contribution is touched, so
            // a neutral squad's strength stays byte-identical. DRIBLAR_MAIS is intentionally inert
            // here (see PlayerInstruction) — it has no strength effect.
            PlayerInstruction instr = profile.instructionFor(p.getId());
            if (instr == PlayerInstruction.SEGURAR_POSICAO) {
                attackContribution *= 0.85;
                defenseContribution *= 1.10;
            } else if (instr == PlayerInstruction.MARCACAO_DURA) {
                defenseContribution *= 1.08;
            }
            attackSum += attackContribution;
            staminaSum += p.getStamina();
            if (p.getPosition() == Position.GK) {
                gkDefense = defenseContribution;
            } else {
                outfieldDefenseSum += defenseContribution;
                outfieldCount++;
            }
        }

        // Divide by the NOMINAL size of a full side, never by the head count actually on the pitch.
        // Averaging over the head count meant a club that lost a BELOW-average player came out
        // stronger: ten men out-rated eleven, and a red card could strengthen the side that received
        // it. Dividing by the full complement instead makes every absentee cost exactly his share of
        // the team's output, whatever put the side short — the loan bar, a suspension, an injury with
        // no cover, or a sending-off (LiveMatchEngine recomputes through this same method).
        // Math.max keeps a full — or, defensively, an over-full — XI dividing by its own size, so a
        // well-formed squad's strength is byte-identical to before and seeded results are unmoved.
        // The outfield nominal is 11 minus the keepers present, so a keeper-less XI (a manager may
        // improvise an outfielder in goal — see LineupService) is measured against eleven outfielders
        // and is penalised on the same terms rather than escaping the size term.
        int keeperCount = xi.size() - outfieldCount;
        int outfieldNominal = Math.max(outfieldCount, FULL_XI - keeperCount);
        double attack = attackSum / Math.max(xi.size(), FULL_XI);
        double outfieldDefense = outfieldNominal > 0 ? outfieldDefenseSum / outfieldNominal : 0.0;
        double defense = 0.6 * outfieldDefense + 0.4 * gkDefense;

        Formation formation = Formation.fromCode(profile.formation());
        attack *= formation.attackMult() * profile.mentality().attackMult();
        defense *= formation.defenseMult() * profile.mentality().defenseMult() * profile.marking().defenseMult();

        // GameStyle scales attack/defense/decay by how well the outfield XI fits the style. For the
        // neutral EQUILIBRADO these are exact x1.0 (and fit is 0.0), preserving seeded determinism.
        List<Player> outfield = xi.stream().filter(p -> p.getPosition() != Position.GK).toList();
        double fit = profile.gameStyle().fit(outfield);
        attack *= profile.gameStyle().attackMult(fit);
        defense *= profile.gameStyle().defenseMult(fit);

        // AttackZone x GameStyle synergy: forcing the play down the wings with a direct style, or
        // through the middle with a passing style, is a natural fit and lifts attack a touch. MISTO
        // (and any non-matching combo, incl. the neutral EQUILIBRADO) yields no synergy, so default
        // settings stay byte-identical.
        if ((profile.gameStyle() == GameStyle.JOGO_DIRETO && profile.attackZone() == AttackZone.LATERAIS)
                || (profile.gameStyle() == GameStyle.JOGO_DE_PASSES && profile.attackZone() == AttackZone.CENTRO)) {
            attack *= 1.02;
        }

        double tempo = profile.pressing().tempoMult();
        double aggression = 1.0 + profile.pressing().aggressionAdd() + profile.marking().aggressionAdd();

        double staminaAvg = staminaSum / xi.size();
        double fitnessDecay = DECAY_BASE * profile.pressing().decayMult()
                * profile.gameStyle().decayMult() * clamp(70.0 / staminaAvg, 0.7, 1.4);

        double cornerThreat = takerThreat(xi, profile.cornerTakerId(), Attribute.CRUZAMENTO);
        double freeKickThreat = takerThreat(xi, profile.freekickTakerId(), Attribute.BOLA_PARADA);
        double penaltyConversion = SetPieces.conversionChance(
                SetPieces.takerOrFallback(xi, profile.penaltyTakerId(), Player::getFinishing)
                        .attribute(Attribute.BOLA_PARADA));

        return new TeamMatchInput(attack, defense, tempo, aggression,
                cornerThreat, freeKickThreat, penaltyConversion, fitnessDecay,
                profile.mentality(), profile.gameStyle());
    }

    /**
     * Position fit from the tactical distance between the assigned slot and the player's playable
     * positions (spec §4.1): natural x1.00, adjacent x0.92, far x0.80, GK-cross x0.50. No slot
     * assignment (legacy/empty) is the x1.0 neutral no-op. Multi-position aware: the BEST of the
     * player's positions counts.
     */
    private double positionFitMult(Player p, TacticalProfile profile) {
        FormationSlots.Slot slot = profile.slotAssignments() == null
                ? null : profile.slotAssignments().get(p.getId());
        if (slot == null) {
            return 1.00;
        }
        int best = Integer.MAX_VALUE;
        for (DetailedPosition mine : p.positions()) {
            for (DetailedPosition accepted : slot.accepted()) {
                best = Math.min(best, mine.distance(accepted));
            }
        }
        return switch (best) {
            case 0 -> 1.00;
            case 1 -> 0.92;
            case 2 -> 0.80;
            default -> 0.50;
        };
    }

    /** The designated taker's attribute when in the XI, else the best in the XI — scaled to ~[0,1]. */
    private double takerThreat(List<Player> xi, UUID takerId, Attribute attribute) {
        if (takerId != null) {
            for (Player p : xi) {
                if (p.getId().equals(takerId)) {
                    return p.attribute(attribute) / 99.0;
                }
            }
        }
        return xi.stream().mapToInt(p -> p.attribute(attribute)).max().orElse(0) / 99.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
