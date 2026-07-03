package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Formation;
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
            PlayerRole role = resolveRole(profile, p);
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

        double tempo = profile.pressing().tempoMult();
        double aggression = 1.0 + profile.pressing().aggressionAdd() + profile.marking().aggressionAdd();

        double staminaAvg = staminaSum / xi.size();
        double fitnessDecay = DECAY_BASE * profile.pressing().decayMult() * clamp(70.0 / staminaAvg, 0.7, 1.4);

        double setPieceThreat = setPieceThreat(xi, profile);

        return new TeamMatchInput(attack, defense, tempo, aggression, setPieceThreat, fitnessDecay);
    }

    private PlayerRole resolveRole(TacticalProfile profile, Player p) {
        PlayerRole role = profile.roles() == null ? null : profile.roles().get(p.getId());
        if (role == null || !role.isValidFor(p.getPosition())) {
            return PlayerRole.defaultFor(p.getPosition());
        }
        return role;
    }

    private double setPieceThreat(List<Player> xi, TacticalProfile profile) {
        UUID takerId = profile.penaltyTakerId();
        Player taker = null;
        if (takerId != null) {
            taker = xi.stream().filter(p -> p.getId().equals(takerId)).findFirst().orElse(null);
        }
        int finishing = taker != null
                ? taker.getFinishing()
                : xi.stream().mapToInt(Player::getFinishing).max().orElse(0);
        return finishing / 99.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
