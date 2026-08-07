package br.com.carvoeiromanager.competition;

/**
 * A competition phase as the frontend sees it.
 *
 * <p><b>{@code kind} carries the enum name</b> ({@code "ROUND_ROBIN"} / {@code "KNOCKOUT"}), not
 * a display label — the frontend switches on it. {@code name} is the pt-BR display string (e.g.
 * {@code "Quartas de final"}). Conflating the two bit this project once already (a weather enum
 * shipped its pt-BR label instead of its name and the frontend's lookup silently returned
 * undefined); keep them distinct here too.
 */
public record PhaseResponse(
        String id,
        int ordinal,
        String name,
        String kind,
        int legs,
        int rounds,
        Integer qualifiers,
        boolean complete
) {}
