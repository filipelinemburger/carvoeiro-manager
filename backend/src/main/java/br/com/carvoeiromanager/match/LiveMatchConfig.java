package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable inputs for a full live-match simulation. {@code fitness} is the effective fitness at
 * kickoff for EVERY player who might appear (both XIs and both benches). {@code subSchedule} is the
 * ordered list of substitutions applied so far; the timeline is a pure function of these inputs.
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
        List<LiveSub> subSchedule) {
}
