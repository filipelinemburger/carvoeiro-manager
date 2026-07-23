package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Formation;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
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
            PlayerRole role = PlayerRole.resolve(profile.roles(), p);
            double fitnessMult = fitnessMultById.getOrDefault(p.getId(), 1.0);
            // Each term is exactly 1.0 at its neutral value (form 0, morale 0, full fitness), so a
            // neutral squad's strength is unchanged — keep it that way to preserve seeded-result determinism.
            double mult = (1.0 + 0.02 * p.getForm()) * (1.0 + MORALE_COEFF * p.getMorale()) * fitnessMult;
            attackSum += role.attackContribution(p) * mult;
            staminaSum += p.getStamina();
            double def = role.defenseContribution(p) * mult;
            if (p.getPosition() == Position.GK) {
                gkDefense = def;
            } else {
                outfieldDefenseSum += def;
                outfieldCount++;
            }
        }

        double attack = attackSum / xi.size();
        double outfieldDefense = outfieldCount > 0 ? outfieldDefenseSum / outfieldCount : 0.0;
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
