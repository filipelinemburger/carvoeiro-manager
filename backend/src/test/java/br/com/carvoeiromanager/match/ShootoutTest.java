package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit-level coverage for {@link Shootout}. No Spring, no database — squads are built
 * directly via {@link Player#create} with only the attributes the engine reads (finishing,
 * goalkeeping) tuned per scenario.
 */
class ShootoutTest {

    private static final UUID SAVE_GAME_ID = UUID.randomUUID();
    private static final UUID HOME_CLUB_ID = UUID.randomUUID();
    private static final UUID AWAY_CLUB_ID = UUID.randomUUID();

    private static Player outfield(String name, int finishing) {
        return Player.create(SAVE_GAME_ID, HOME_CLUB_ID, name, Position.MID, 25, "BRA",
                60, finishing, 60, 60, 20, 70, 70);
    }

    private static Player keeper(String name, int goalkeeping) {
        return Player.create(SAVE_GAME_ID, AWAY_CLUB_ID, name, Position.GK, 25, "BRA",
                40, 10, 40, 40, goalkeeping, 60, 70);
    }

    private static List<Player> takers(int count, int finishing) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(outfield("Taker-" + finishing + "-" + i, finishing));
        }
        return players;
    }

    // Roughly balanced squads (a few points apart) for the "always terminates" / determinism checks.
    private final List<Player> homeTakers = takers(10, 70);
    private final List<Player> awayTakers = takers(10, 66);
    private final Player homeGk = keeper("Home GK", 68);
    private final Player awayGk = keeper("Away GK", 70);

    // Lopsided squads for the finishing-quality and early-resolution checks.
    private final List<Player> eliteTakers = takers(10, 99);
    private final List<Player> poorTakers = takers(10, 30);
    private final List<Player> hopelessTakers = takers(10, 1);
    private final Player avgGk = keeper("Avg GK", 50);
    private final Player worldClassGk = keeper("World Class GK", 99);

    @Test
    void aShootoutAlwaysProducesAWinner() {
        for (long seed = 0; seed < 200; seed++) {
            Shootout.Result result = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, seed);
            assertThat(result.homeScore()).isNotEqualTo(result.awayScore());
        }
    }

    @Test
    void theSameSeedProducesTheSameShootout() {
        Shootout.Result a = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, 42L);
        Shootout.Result b = Shootout.decide(homeTakers, awayTakers, homeGk, awayGk, 42L);

        assertThat(b.homeScore()).isEqualTo(a.homeScore());
        assertThat(b.awayScore()).isEqualTo(a.awayScore());
        assertThat(b.kicks()).isEqualTo(a.kicks());
    }

    @Test
    void betterFinishersConvertMoreOften() {
        int eliteGoals = 0;
        int poorGoals = 0;
        for (long seed = 0; seed < 500; seed++) {
            eliteGoals += Shootout.decide(eliteTakers, poorTakers, avgGk, avgGk, seed).homeScore();
            poorGoals  += Shootout.decide(poorTakers, eliteTakers, avgGk, avgGk, seed).homeScore();
        }
        assertThat(eliteGoals).isGreaterThan(poorGoals);
    }

    @Test
    void aShootoutRunsPastTenKicksOnlyIfTheBestOfFiveWasLevel() {
        // The real invariant, and one that survives the decided() fix: a shootout only goes
        // beyond the best-of-five (10 kicks) when the sides were level after five rounds each.
        // The previous version asserted <= 10 kicks for one seed, which only held because
        // decided() used to stop a kick early.
        for (long seed = 0; seed < 100; seed++) {
            Shootout.Result result = Shootout.decide(eliteTakers, hopelessTakers, avgGk, worldClassGk, seed);
            if (result.kicks().size() > 10) {
                int homeAfterFive = (int) result.kicks().stream()
                        .filter(k -> k.home() && k.round() <= 5 && k.scored()).count();
                int awayAfterFive = (int) result.kicks().stream()
                        .filter(k -> !k.home() && k.round() <= 5 && k.scored()).count();
                assertThat(homeAfterFive).as("seed %d went to sudden death", seed)
                        .isEqualTo(awayAfterFive);
            }
        }
    }

    @Test
    void suddenDeathTerminates() {
        // Identical squads, worst case for early resolution
        Shootout.Result result = Shootout.decide(homeTakers, homeTakers, homeGk, homeGk, 99L);

        assertThat(result.kicks().size()).isLessThan(60);
    }

    // --- best-of-five early termination -------------------------------------------------------
    //
    // Home kicks first, so at round r away has taken r-1 kicks while home has taken r-1 before
    // its own kick and r after it. An earlier version had these backwards, which ended a shootout
    // one kick early and could hand the leader an outright win in a position the trailing side
    // could still have levelled. These pin the boundary directly rather than steering the RNG.

    @Test
    void aThreeNilLeadAfterThreeHomeKicksIsNotYetDecided() {
        // Home 3/3, away 0/2, home has just taken its round-3 kick. Away still has kicks 3, 4
        // and 5 to come and can reach 3 — the shootout must continue.
        assertThat(Shootout.decided(3, 0, 3, false)).isFalse();
    }

    @Test
    void aLeadIsDecidedOnlyWhenTheTrailerCannotReachIt() {
        // After home's round-4 kick, away has taken 3 and has 2 left.
        // home 3, away 1: away can still reach 3 -> not decided.
        assertThat(Shootout.decided(3, 1, 4, false)).isFalse();
        // home 4, away 1: away tops out at 3 -> decided.
        assertThat(Shootout.decided(4, 1, 4, false)).isTrue();
    }

    @Test
    void anAwayLeadIsJudgedAgainstHomesRemainingKicks() {
        // Before home's round-3 kick, home has taken 2 and has 3 left: away 3 is reachable.
        assertThat(Shootout.decided(0, 3, 3, true)).isFalse();
        // Before home's round-4 kick, home has only 2 left: away 3 is out of reach.
        assertThat(Shootout.decided(0, 3, 4, true)).isTrue();
    }

    @Test
    void alevelShootoutIsNeverDecidedEarly() {
        for (int round = 1; round <= 5; round++) {
            assertThat(Shootout.decided(2, 2, round, true)).as("round %d before home", round).isFalse();
            assertThat(Shootout.decided(2, 2, round, false)).as("round %d after home", round).isFalse();
        }
    }
}
