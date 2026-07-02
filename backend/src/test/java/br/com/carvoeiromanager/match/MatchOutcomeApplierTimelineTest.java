package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeApplierTimelineTest {

    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());

    private Player player(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void appliesTimelineInjuriesAndSurfacesManagedOnes() {
        Player p = player(Position.MID);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        applier.applyInjuriesFromTimeline(
                List.of(new TimedInjury(p.getId(), true, 55, InjurySeverity.MINOR, 3)),
                Map.of(p.getId(), p), true, acc);

        assertThat(p.getInjuryMatches()).isEqualTo(3);
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.MINOR);
        assertThat(acc.newInjuries()).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo("MINOR"));
    }

    @Test
    void oppositionInjuriesDoNotSurfaceInSummary() {
        Player p = player(Position.MID);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        // home=false means opposition; managedIsHome=true — injury should be applied but NOT surfaced
        applier.applyInjuriesFromTimeline(
                List.of(new TimedInjury(p.getId(), false, 70, InjurySeverity.MINOR, 2)),
                Map.of(p.getId(), p), true, acc);

        assertThat(p.getInjuryMatches()).isEqualTo(2);
        assertThat(acc.newInjuries()).isEmpty();
    }

    @Test
    void missingPlayerIdIsSkippedGracefully() {
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        applier.applyInjuriesFromTimeline(
                List.of(new TimedInjury(UUID.randomUUID(), true, 10, InjurySeverity.MINOR, 3)),
                Map.of(), true, acc);

        assertThat(acc.newInjuries()).isEmpty();
    }
}
