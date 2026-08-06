package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable inputs for a full live-match simulation. {@code fitness} is the effective fitness at
 * kickoff for EVERY player who might appear (both XIs and both benches). {@code subSchedule} is the
 * ordered list of substitutions applied so far; the timeline is a pure function of these inputs.
 * {@code tacticsSchedule} is the ordered list of in-match tactics changes; an empty list means the
 * starting tactical profiles are held constant for the full 90 minutes (backward-compatible default).
 * {@code aiHome} names the side under AI-manager control ({@code true} = home) so the engine can
 * produce score-reactive tactics changes for it; {@code null} disables AI reactions entirely.
 * {@code weather} is resolved once by the caller (the home club's climate zone on the fixture's
 * match date) and held fixed for the whole match — weather never changes mid-match.
 *
 * <p>{@code knockoutDecider} is true when this match is the second leg of a knockout tie whose
 * first leg has already been played — i.e. this match is ELIGIBLE to run into extra time.
 * {@code firstLegHomeGoals}/{@code firstLegAwayGoals} carry that first leg's score (both null
 * when not eligible) so {@link LiveMatchEngine#simulate} can check, once it knows this match's
 * own 90-minute score, whether the aggregate is level — the ONLY case that actually extends the
 * simulated timeline past minute 90. A match that isn't a knockout decider never even evaluates
 * that check, so its timeline is byte-identical to before these fields existed.
 */
public record LiveMatchConfig(
        long seed,
        List<Player> homeXi,
        List<Player> awayXi,
        List<Player> homeBench,
        List<Player> awayBench,
        TacticalProfile homeProfile,
        TacticalProfile awayProfile,
        Map<UUID, Integer> fitness,
        List<LiveSub> subSchedule,
        List<TimedTactics> tacticsSchedule,
        Boolean aiHome,
        Weather weather,
        boolean knockoutDecider,
        Integer firstLegHomeGoals,
        Integer firstLegAwayGoals) {
}
