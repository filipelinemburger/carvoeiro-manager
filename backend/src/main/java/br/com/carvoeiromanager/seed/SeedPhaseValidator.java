package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.competition.FixtureScheduler;

import java.util.List;

/**
 * Checks a seeded competition's phase configuration before a career is created.
 *
 * <p>Every rule here corresponds to a way a bad config used to fail <em>later</em> and worse:
 *
 * <ul>
 *   <li>A non-power-of-two {@code qualifiers} throws inside {@code FixtureScheduler.knockoutPairings}
 *       mid-season, during an {@code advance()} that then rolls back — leaving the career
 *       permanently unable to pass that round.
 *   <li>A {@code rounds} count that disagrees with what the scheduler produces throws
 *       {@code IndexOutOfBoundsException} out of {@code SeasonCalendarGenerator.phasedUnits}.
 *   <li>A knockout phase without exactly two reserved rounds fails in
 *       {@code PhaseResolver.materialize}.
 *   <li>Too few or too many knockout phases for the qualifier count leaves the bracket
 *       unresolvable, and season end then throws out of {@code FinalClassification}.
 * </ul>
 *
 * <p>Failing at career creation turns all of those into one clear message before any row is
 * written, instead of a career that dies partway through a season.
 */
public final class SeedPhaseValidator {

    private SeedPhaseValidator() {
    }

    public static void validate(SeedCompetition competition) {
        List<SeedPhase> phases = competition.phases();
        if (phases == null || phases.isEmpty()) {
            return; // a plain league; nothing to check
        }
        String where = "competition '" + competition.name() + "'";
        int members = competition.memberShortNames().size();

        if (!"ROUND_ROBIN".equals(phases.get(0).kind())) {
            throw new IllegalStateException(where + ": the first phase must be ROUND_ROBIN — a "
                    + "knockout first phase is never materialized, because PhaseResolver only fills "
                    + "in the phase after a completed one");
        }

        int qualifiers = 0;
        int knockoutPhases = 0;
        for (SeedPhase phase : phases) {
            String at = where + ", phase '" + phase.name() + "'";
            if ("ROUND_ROBIN".equals(phase.kind())) {
                // An odd field needs a bye round per club, so it is `members` rounds a leg, not
                // `members - 1`. FixtureScheduler owns that arithmetic; asking it keeps this
                // check and the fixtures it guards from ever disagreeing.
                int roundsPerLeg = FixtureScheduler.roundsPerLeg(members);
                int expectedRounds = phase.legs() == 1 ? roundsPerLeg : roundsPerLeg * 2;
                if (phase.rounds() != expectedRounds) {
                    throw new IllegalStateException(at + ": rounds=" + phase.rounds() + " but "
                            + members + " members over " + phase.legs() + " leg(s) produce "
                            + expectedRounds);
                }
                if (phase.qualifiers() != null) {
                    qualifiers = phase.qualifiers();
                    if (qualifiers < 2 || Integer.bitCount(qualifiers) != 1) {
                        throw new IllegalStateException(at + ": qualifiers=" + qualifiers
                                + " must be a power of two and at least 2");
                    }
                    if (qualifiers > members) {
                        throw new IllegalStateException(at + ": qualifiers=" + qualifiers
                                + " exceeds the " + members + " members");
                    }
                }
            } else {
                knockoutPhases++;
                if (phase.legs() != 2 || phase.rounds() != 2) {
                    throw new IllegalStateException(at + ": a knockout phase must be two-legged "
                            + "over exactly 2 rounds (got legs=" + phase.legs()
                            + ", rounds=" + phase.rounds() + ") — PhaseResolver materializes ties "
                            + "onto exactly two reserved rounds");
                }
                if (phase.qualifiers() != null) {
                    throw new IllegalStateException(at + ": a knockout phase must not declare "
                            + "qualifiers; how many advance follows from the bracket");
                }
            }
        }

        if (qualifiers == 0) {
            if (knockoutPhases > 0) {
                throw new IllegalStateException(where + ": has knockout phases but no round-robin "
                        + "phase declares qualifiers, so nothing seeds the bracket");
            }
            return;
        }
        int expectedKnockoutPhases = Integer.numberOfTrailingZeros(qualifiers); // log2
        if (knockoutPhases != expectedKnockoutPhases) {
            throw new IllegalStateException(where + ": " + qualifiers + " qualifiers need exactly "
                    + expectedKnockoutPhases + " knockout phase(s) to reach one winner, but "
                    + knockoutPhases + " are declared");
        }
    }
}
