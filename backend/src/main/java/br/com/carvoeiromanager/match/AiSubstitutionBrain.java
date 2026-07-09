package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, RNG-free substitution brain for the AI side of a live match. Evaluated once
 * per minute by the engine; returns at most ONE decision, in strict priority order:
 * injury (replace a hobbled player) > posture (personnel for an extreme posture) > fatigue
 * (freshen a tired starter). Non-injury subs stop at four so one sub is always held back for
 * a late injury. All selection is deterministic: "best" = highest overall, ties broken by
 * list order; re-simulations therefore replay identical substitutions.
 */
public final class AiSubstitutionBrain {

    /** Why a substitution was made — the engine uses this to bump the right counters. */
    public enum Kind { INJURY, POSTURE, FATIGUE }

    public record SubDecision(UUID outId, UUID inId, Kind kind) { }

    static final int MAX_SUBS = 5;
    private static final int NON_INJURY_SUB_LIMIT = 4;   // one sub always held back for injuries
    private static final int FATIGUE_SUB_LIMIT = 2;
    private static final int FATIGUE_FROM_MINUTE = 65;
    private static final int FATIGUE_THRESHOLD = 70;     // kickoff fitness below this = tired
    private static final int MIN_DEF_ON_PITCH = 3;
    private static final int MIN_ATT_ON_PITCH = 1;

    private AiSubstitutionBrain() {
    }

    public static Optional<SubDecision> decide(
            int minute,
            AiManagerBrain.Posture posture,
            boolean postureSubMade,
            int fatigueSubsMade,
            int totalSubsMade,
            List<Player> onPitch,
            Set<UUID> hobbled,
            List<Player> benchAvailable,
            Map<UUID, Integer> kickoffFitness) {

        if (benchAvailable.isEmpty() || totalSubsMade >= MAX_SUBS) return Optional.empty();

        // 1. Injury: the first hobbled on-pitch player (pitch order) that has positional cover.
        for (Player out : onPitch) {
            if (!hobbled.contains(out.getId())) continue;
            Optional<Player> cover = bestByPosition(benchAvailable, out.getPosition());
            if (cover.isPresent()) {
                return Optional.of(new SubDecision(out.getId(), cover.get().getId(), Kind.INJURY));
            }
        }

        if (totalSubsMade >= NON_INJURY_SUB_LIMIT) return Optional.empty();

        // 2. Posture: once per match, swap personnel to match an extreme posture.
        if (!postureSubMade) {
            if (posture == AiManagerBrain.Posture.ALL_OUT) {
                Optional<SubDecision> swap =
                        postureSwap(onPitch, benchAvailable, Position.DEF, MIN_DEF_ON_PITCH, Position.ATT);
                if (swap.isPresent()) return swap;
            } else if (posture == AiManagerBrain.Posture.PARK_THE_BUS) {
                Optional<SubDecision> swap =
                        postureSwap(onPitch, benchAvailable, Position.ATT, MIN_ATT_ON_PITCH, Position.DEF);
                if (swap.isPresent()) return swap;
            }
        }

        // 3. Fatigue: freshen the most tired outfield starter from minute 65.
        if (minute >= FATIGUE_FROM_MINUTE && fatigueSubsMade < FATIGUE_SUB_LIMIT) {
            Player tired = null;
            for (Player p : onPitch) {
                if (p.getPosition() == Position.GK) continue;
                int fit = kickoffFitness.getOrDefault(p.getId(), 100);
                if (fit >= FATIGUE_THRESHOLD) continue;
                if (tired == null || fit < kickoffFitness.getOrDefault(tired.getId(), 100)) tired = p;
            }
            if (tired != null) {
                Optional<Player> fresh = bestByPosition(benchAvailable, tired.getPosition());
                if (fresh.isPresent()) {
                    return Optional.of(new SubDecision(tired.getId(), fresh.get().getId(), Kind.FATIGUE));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Take off the lowest-overall on-pitch {@code outPosition} player for the best bench
     * {@code inPosition} player. Allowed only while the current on-pitch count of the outgoing
     * position strictly exceeds the floor.
     */
    private static Optional<SubDecision> postureSwap(List<Player> onPitch, List<Player> bench,
                                                     Position outPosition, int minOutPositionOnPitch,
                                                     Position inPosition) {
        long count = onPitch.stream().filter(p -> p.getPosition() == outPosition).count();
        if (count <= minOutPositionOnPitch) return Optional.empty();
        Optional<Player> in = bestByPosition(bench, inPosition);
        if (in.isEmpty()) return Optional.empty();
        Player out = null;
        for (Player p : onPitch) {
            if (p.getPosition() != outPosition) continue;
            if (out == null || p.getOverall() < out.getOverall()) out = p;
        }
        return Optional.of(new SubDecision(out.getId(), in.get().getId(), Kind.POSTURE));
    }

    /** Highest-overall bench player of the given position; ties keep the first in bench order. */
    private static Optional<Player> bestByPosition(List<Player> bench, Position position) {
        Player best = null;
        for (Player p : bench) {
            if (p.getPosition() != position) continue;
            if (best == null || p.getOverall() > best.getOverall()) best = p;
        }
        return Optional.ofNullable(best);
    }
}
