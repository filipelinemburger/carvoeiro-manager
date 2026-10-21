package br.com.carvoeiromanager.seed;

import java.util.Set;

/**
 * Checks a seeded competition's scope and tier before a career is created. The board picks the
 * managed club's league objective by scope and its targets by tier; a national division without a
 * tier would silently get no league objective at all, which is exactly the kind of quiet gap a
 * future seed entry ("Série D") would open.
 */
public final class SeedScopeValidator {

    private static final Set<String> SCOPES = Set.of("NATIONAL", "STATE");

    private SeedScopeValidator() {
    }

    public static void validate(SeedCompetition competition) {
        String where = "competition '" + competition.name() + "'";
        String scope = competition.scopeOrDefault();
        if (!SCOPES.contains(scope)) {
            throw new IllegalStateException(where + ": unknown scope '" + scope + "'; expected one of " + SCOPES);
        }
        if ("NATIONAL".equals(scope) && competition.tier() == null) {
            throw new IllegalStateException(where + ": a NATIONAL competition needs a tier "
                    + "(1 = Série A, 2 = B, 3 = C) — the board sets its league targets by tier");
        }
        if ("STATE".equals(scope) && competition.tier() != null) {
            throw new IllegalStateException(where + ": a STATE competition has no tier, but tier="
                    + competition.tier() + " was given");
        }
    }
}
