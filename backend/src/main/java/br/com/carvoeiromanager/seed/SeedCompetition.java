package br.com.carvoeiromanager.seed;

import java.util.List;

/**
 * A single competition entry within a SeedCareer, carrying its name, type (e.g. "LEAGUE"),
 * season year, the shortNames of the clubs that participate in it, and its ordered phases.
 * A null or empty {@code phases} means a plain double round-robin league (pre-V47 behaviour).
 *
 * <p>{@code scope} is {@code "NATIONAL"} or {@code "STATE"}; absent means NATIONAL. {@code tier} is
 * the national division (1 = Série A, 2 = B, 3 = C) and must be absent on a STATE competition —
 * {@link SeedScopeValidator} enforces both, because the board sets its league targets by tier.
 */
public record SeedCompetition(
        String name,
        String type,
        int seasonYear,
        List<String> memberShortNames,
        List<SeedPhase> phases,
        String scope,
        Integer tier
) {
    /** Shorthand for callers (mostly tests) that predate scope/tier: a NATIONAL competition, no tier. */
    public SeedCompetition(String name, String type, int seasonYear, List<String> memberShortNames,
                           List<SeedPhase> phases) {
        this(name, type, seasonYear, memberShortNames, phases, null, null);
    }

    /** The scope as declared, with an absent key meaning NATIONAL. */
    public String scopeOrDefault() {
        return scope == null ? "NATIONAL" : scope;
    }
}
