package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Pressing;

/**
 * Deterministic, RNG-free "manager brain" for the AI side of a live match. Decides a
 * {@link Posture} from score difference (AI's perspective; negative = losing), minute, and
 * whether the AI side is down a man — shaped by the club's tactical-identity mentality
 * (attacking clubs chase 10' earlier and protect 5' later; defensive clubs the reverse).
 *
 * <p>Memoryless by design: the same inputs always yield the same posture, so the engine can
 * re-evaluate every minute during its deterministic re-simulation and the AI's reactions replay
 * identically. If the score swings back, the posture de-escalates naturally.
 *
 * <p>No reactions in the first half: minute 1-45 is always {@code NORMAL}, and every
 * offset-adjusted threshold clamps to minute 46.
 */
public final class AiManagerBrain {

    /** How the AI manager currently wants to play, from most defensive to most attacking. */
    public enum Posture { PARK_THE_BUS, PROTECT, NORMAL, PUSH, ALL_OUT }

    private static final int SECOND_HALF_START = 46;

    private AiManagerBrain() {
    }

    public static Posture decide(Mentality identityMentality, int scoreDiff, int minute, boolean downAMan) {
        if (minute < SECOND_HALF_START) return Posture.NORMAL;
        int chase = chaseOffset(identityMentality);
        int protect = protectOffset(identityMentality);

        if (scoreDiff <= -2) {
            // A red card never stops the chase.
            if (minute >= clamp(70 + chase)) return Posture.ALL_OUT;
            if (minute >= clamp(46 + chase)) return Posture.PUSH;
            return Posture.NORMAL;
        }
        if (scoreDiff == -1) {
            if (minute >= clamp(80 + chase)) return Posture.ALL_OUT;
            if (minute >= clamp(60 + chase)) return Posture.PUSH;
            return Posture.NORMAL;
        }
        if (scoreDiff == 0) {
            if (downAMan) return Posture.PROTECT;
            // Identity-specific fixed rules — the identity already expresses the offset here.
            if (identityMentality == Mentality.ATTACKING && minute >= 75) return Posture.PUSH;
            if (identityMentality == Mentality.DEFENSIVE && minute >= 85) return Posture.PROTECT;
            return Posture.NORMAL;
        }
        // Winning.
        if (downAMan) {
            return minute >= clamp(75 + protect) ? Posture.PARK_THE_BUS : Posture.PROTECT;
        }
        if (scoreDiff == 1) {
            if (minute >= clamp(85 + protect)) return Posture.PARK_THE_BUS;
            if (minute >= clamp(75 + protect)) return Posture.PROTECT;
            return Posture.NORMAL;
        }
        // A two-goal cushion shuts the game down late but never parks the bus.
        return minute >= clamp(80 + protect) ? Posture.PROTECT : Posture.NORMAL;
    }

    /**
     * Maps a posture onto the club's identity profile. Only mentality/pressing/formation are
     * overridden; marking, per-player roles and set-piece takers are always the identity's —
     * the same swap discipline as a user tactics change.
     */
    public static TacticalProfile profileFor(Posture posture, TacticalProfile identity) {
        return switch (posture) {
            case NORMAL -> identity;
            case PUSH -> with(identity, Mentality.ATTACKING, identity.pressing(), identity.formation());
            case ALL_OUT -> with(identity, Mentality.ATTACKING, Pressing.HIGH, "4-2-4");
            case PROTECT -> with(identity, Mentality.DEFENSIVE, identity.pressing(), identity.formation());
            case PARK_THE_BUS -> with(identity, Mentality.DEFENSIVE, Pressing.LOW, "5-4-1");
        };
    }

    private static TacticalProfile with(TacticalProfile identity, Mentality mentality,
                                        Pressing pressing, String formation) {
        // Per-player instructions belong to the identity and never change with an AI posture swap.
        return new TacticalProfile(mentality, identity.gameStyle(), identity.attackZone(), formation, pressing,
                identity.marking(), identity.roles(), identity.penaltyTakerId(), identity.freekickTakerId(),
                identity.cornerTakerId(), identity.instructions());
    }

    private static int chaseOffset(Mentality identity) {
        return switch (identity) {
            case ATTACKING -> -10;
            case BALANCED -> 0;
            case DEFENSIVE -> 10;
        };
    }

    private static int protectOffset(Mentality identity) {
        return switch (identity) {
            case ATTACKING -> 5;
            case BALANCED -> 0;
            case DEFENSIVE -> -10;
        };
    }

    private static int clamp(int thresholdMinute) {
        return Math.max(SECOND_HALF_START, thresholdMinute);
    }
}
