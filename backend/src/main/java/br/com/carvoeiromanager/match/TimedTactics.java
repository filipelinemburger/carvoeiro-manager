package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Pressing;

/**
 * A tactics change applied at a specific minute for one side during a live match.
 * Analogous to {@link LiveSub}: processed by the engine before that minute's play,
 * does not draw from any RNG, so the match/attr/injury RNG streams remain byte-identical
 * for a given {@code (seed, subSchedule)} when the tactics schedule is empty.
 */
public record TimedTactics(
        int minute,
        boolean home,
        Mentality mentality,
        Pressing pressing,
        Marking marking,
        String formation) {
}
