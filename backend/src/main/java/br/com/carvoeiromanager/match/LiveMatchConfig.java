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
        Weather weather) {
}
