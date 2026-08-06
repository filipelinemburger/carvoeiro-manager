package br.com.carvoeiromanager.competition;

/** How a competition phase is played. */
public enum PhaseKind {
    /** Every member plays every other; produces a table. */
    ROUND_ROBIN,
    /** Seeded ties; produces winners, no table. */
    KNOCKOUT
}
