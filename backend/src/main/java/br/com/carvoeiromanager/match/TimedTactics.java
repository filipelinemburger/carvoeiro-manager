package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Pressing;

/**
 * A tactics change applied at a specific minute for one side during a live match.
 * Analogous to {@link LiveSub}: processed by the engine before that minute's play,
 * does not draw from any RNG, so the match/attr/injury RNG streams remain byte-identical
 * for a given {@code (seed, subSchedule)} when the tactics schedule is empty.
 *
 * <p>{@code gameStyle} is NULLABLE: old persisted schedules (JSON serialized before Task 23)
 * carry no such field, so Jackson deserializes it as null. The engine treats a null
 * {@code gameStyle} as "keep the side's current style" (see
 * {@link LiveMatchEngine#applyTacticChanges}), preserving byte-identical behaviour for those
 * legacy schedules; a non-null value applies the chosen style from that minute onward.
 *
 * <p>{@code attackZone} is NULLABLE for the same reason (Task 25): schedules serialized before
 * the field existed deserialize it as null, which the engine treats as "keep the side's current
 * zone"; a non-null value applies the chosen zone from that minute onward.
 */
public record TimedTactics(
        int minute,
        boolean home,
        Mentality mentality,
        GameStyle gameStyle,
        AttackZone attackZone,
        Pressing pressing,
        Marking marking,
        String formation) {
}
