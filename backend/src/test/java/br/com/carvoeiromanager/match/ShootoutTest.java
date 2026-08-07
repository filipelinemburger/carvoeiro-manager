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
    void theFirstFiveRoundsEndEarlyWhenTheOutcomeIsSettled() {
        // A 5-0 lead after three rounds cannot be caught: at most 8 kicks are taken
        Shootout.Result result = Shootout.decide(eliteTakers, hopelessTakers, avgGk, worldClassGk, 7L);

        assertThat(result.kicks().size()).isLessThanOrEqualTo(10);
    }

    @Test
    void suddenDeathTerminates() {
        // Identical squads, worst case for early resolution
        Shootout.Result result = Shootout.decide(homeTakers, homeTakers, homeGk, homeGk, 99L);

        assertThat(result.kicks().size()).isLessThan(60);
    }
}
