package br.com.carvoeiromanager.common;

/** Squad-size limits shared by every place that gates adding a player to the managed club. */
public final class SquadRules {

    /**
     * Ceiling on the professional squad. Raised from 40 to 45 when loans arrived: a club that
     * lends several players can have them all return at the same rollover, and the cap is
     * otherwise only checked when adding one player at a time.
     */
    public static final int MAX_SQUAD_SIZE = 45;

    /** Floor the managed squad may not drop below — every departure path counts against it. */
    public static final int MIN_SQUAD_SIZE = 14;

    private SquadRules() {
    }
}
