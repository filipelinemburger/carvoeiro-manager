package br.com.carvoeiromanager.common;

/** Squad-size limits shared by every place that gates adding a player to the managed club. */
public final class SquadRules {

    public static final int MAX_SQUAD_SIZE = 40;

    /** Floor the managed squad may not drop below — every departure path counts against it. */
    public static final int MIN_SQUAD_SIZE = 14;

    private SquadRules() {
    }
}
