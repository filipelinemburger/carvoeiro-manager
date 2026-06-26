package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.club.Club;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StandingsCalculatorTest {

    private final StandingsCalculator calculator = new StandingsCalculator();

    /**
     * Given clubs A, B, C and fixtures:
     *   A 2-0 B  -> A wins (3pts), B loses (0pts)
     *   A 1-1 C  -> A draws (1pt),  C draws (1pt)
     *   B 3-1 C  -> B wins (3pts), C loses (0pts)
     *
     * Expected standings:
     *   1. A: 4pts, GD=+2, GF=3 (2 played: 1W 1D 0L, GF=3, GA=1)
     *   2. B: 3pts, GD=0,  GF=3 (2 played: 1W 0D 1L, GF=3, GA=3)
     *   3. C: 1pt,  GD=-2, GF=2 (2 played: 0W 1D 1L, GF=2, GA=4)
     *
     * A before B because: A played earlier first round? Actually A and B have same GD and GF,
     * but A has 4pts vs B 3pts, so A is first. B before C by points (3 > 1).
     */
    @Test
    void ranksByPointsThenGoalDifferenceThenGoalsForThenName() {
        // Given
        Club clubA = makeClub("A");
        Club clubB = makeClub("B");
        Club clubC = makeClub("C");

        // A 2-0 B
        Fixture f1 = makePlayedFixture(clubA.getId(), clubB.getId(), 2, 0);
        // A 1-1 C
        Fixture f2 = makePlayedFixture(clubA.getId(), clubC.getId(), 1, 1);
        // B 3-1 C
        Fixture f3 = makePlayedFixture(clubB.getId(), clubC.getId(), 3, 1);

        List<Club> clubs = List.of(clubA, clubB, clubC);
        List<Fixture> fixtures = List.of(f1, f2, f3);

        // When
        List<StandingRow> standings = calculator.compute(clubs, fixtures);

        // Then - order: A (4pts), B (3pts), C (1pt)
        assertThat(standings).hasSize(3);

        StandingRow rowA = standings.get(0);
        StandingRow rowB = standings.get(1);
        StandingRow rowC = standings.get(2);

        // Club names in order
        assertThat(rowA.clubName()).isEqualTo("A");
        assertThat(rowB.clubName()).isEqualTo("B");
        assertThat(rowC.clubName()).isEqualTo("C");

        // A: 1W 1D 0L, GF=3, GA=1, GD=+2, pts=4
        assertThat(rowA.played()).isEqualTo(2);
        assertThat(rowA.won()).isEqualTo(1);
        assertThat(rowA.drawn()).isEqualTo(1);
        assertThat(rowA.lost()).isEqualTo(0);
        assertThat(rowA.goalsFor()).isEqualTo(3);
        assertThat(rowA.goalsAgainst()).isEqualTo(1);
        assertThat(rowA.goalDifference()).isEqualTo(2);
        assertThat(rowA.points()).isEqualTo(4);

        // B: 1W 0D 1L, GF=3, GA=3, GD=0, pts=3
        assertThat(rowB.played()).isEqualTo(2);
        assertThat(rowB.won()).isEqualTo(1);
        assertThat(rowB.drawn()).isEqualTo(0);
        assertThat(rowB.lost()).isEqualTo(1);
        assertThat(rowB.goalsFor()).isEqualTo(3);
        assertThat(rowB.goalsAgainst()).isEqualTo(3);
        assertThat(rowB.goalDifference()).isEqualTo(0);
        assertThat(rowB.points()).isEqualTo(3);

        // C: 0W 1D 1L, GF=2, GA=4, GD=-2, pts=1
        // A 1-1 C: C scores 1, concedes 1
        // B 3-1 C: C scores 1, concedes 3
        assertThat(rowC.played()).isEqualTo(2);
        assertThat(rowC.won()).isEqualTo(0);
        assertThat(rowC.drawn()).isEqualTo(1);
        assertThat(rowC.lost()).isEqualTo(1);
        assertThat(rowC.goalsFor()).isEqualTo(2);
        assertThat(rowC.goalsAgainst()).isEqualTo(4);
        assertThat(rowC.goalDifference()).isEqualTo(-2);
        assertThat(rowC.points()).isEqualTo(1);
    }

    @Test
    void tiebreakByGoalDifferenceThenGoalsForThenName() {
        // Given: D and E both win once, but D has a better GD
        Club clubD = makeClub("D");
        Club clubE = makeClub("E");
        Club clubF = makeClub("F");

        // D 3-0 F -> D 3pts, GD=+3, GF=3
        Fixture f1 = makePlayedFixture(clubD.getId(), clubF.getId(), 3, 0);
        // E 1-0 F -> E 3pts, GD=+1, GF=1
        Fixture f2 = makePlayedFixture(clubE.getId(), clubF.getId(), 1, 0);

        List<Club> clubs = List.of(clubD, clubE, clubF);
        List<Fixture> fixtures = List.of(f1, f2);

        // When
        List<StandingRow> standings = calculator.compute(clubs, fixtures);

        // Then: D before E (same pts but D has better GD)
        assertThat(standings.get(0).clubName()).isEqualTo("D");
        assertThat(standings.get(1).clubName()).isEqualTo("E");
        assertThat(standings.get(2).clubName()).isEqualTo("F");
    }

    @Test
    void unplayedFixturesAreIgnored() {
        // Given: an unplayed fixture (homeGoals/awayGoals null, played=false)
        Club clubA = makeClub("Alpha");
        Club clubB = makeClub("Beta");

        Fixture unplayed = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1,
                clubA.getId(), clubB.getId());
        // unplayed has played=false and null goals

        List<Club> clubs = List.of(clubA, clubB);
        List<Fixture> fixtures = List.of(unplayed);

        // When
        List<StandingRow> standings = calculator.compute(clubs, fixtures);

        // Then: both clubs have 0 played, 0 points
        assertThat(standings).hasSize(2);
        assertThat(standings).allMatch(r -> r.played() == 0 && r.points() == 0);
    }

    // --- Private helpers ---

    private Club makeClub(String name) {
        return Club.create(UUID.randomUUID(), name, name.substring(0, 1).toUpperCase());
    }

    private Fixture makePlayedFixture(UUID homeId, UUID awayId, int homeGoals, int awayGoals) {
        Fixture f = Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1, homeId, awayId);
        f.recordResult(homeGoals, awayGoals);
        return f;
    }
}
