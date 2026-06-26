package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.match.Lineup;
import br.com.elifootcarvoeiro.match.LineupPlayer;
import br.com.elifootcarvoeiro.match.LineupPlayerId;
import br.com.elifootcarvoeiro.match.LineupPlayerRepository;
import br.com.elifootcarvoeiro.match.LineupRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureRepositoryIT {

    @Autowired FixtureRepository fixtures;
    @Autowired CompetitionRepository competitions;
    @Autowired ClubRepository clubs;
    @Autowired SaveGameRepository saveGames;
    @Autowired UserRepository users;
    @Autowired PlayerRepository players;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    @Test
    void findsFixturesByRound() {
        // Given a persisted user, save game, two clubs, a competition, and a round-1 fixture
        User user = User.create("coach_test", "hash");
        users.save(user);

        SaveGame save = SaveGame.create(user.getId(), 2025);
        saveGames.save(save);

        Club homeClub = Club.create(save.getId(), "Avaí FC", "AVA");
        Club awayClub = Club.create(save.getId(), "Figueirense FC", "FIG");
        clubs.save(homeClub);
        clubs.save(awayClub);

        Competition competition = Competition.create(save.getId(), "Catarinense 2025", "LEAGUE", 2025, 10);
        competitions.save(competition);

        Fixture round1Fixture = Fixture.create(save.getId(), competition.getId(), 1, homeClub.getId(), awayClub.getId());
        Fixture round2Fixture = Fixture.create(save.getId(), competition.getId(), 2, awayClub.getId(), homeClub.getId());
        fixtures.save(round1Fixture);
        fixtures.save(round2Fixture);

        // When findBySaveGameIdAndCompetitionIdAndRound is called for round 1
        List<Fixture> result = fixtures.findBySaveGameIdAndCompetitionIdAndRound(
                save.getId(), competition.getId(), 1);

        // Then exactly that round-1 fixture is returned
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(round1Fixture.getId());
        assertThat(result.get(0).getHomeClubId()).isEqualTo(homeClub.getId());
        assertThat(result.get(0).getAwayClubId()).isEqualTo(awayClub.getId());
        assertThat(result.get(0).isPlayed()).isFalse();
        assertThat(result.get(0).getHomeGoals()).isNull();
        assertThat(result.get(0).getAwayGoals()).isNull();
    }

    @Test
    void findsAllFixturesForCompetition() {
        // Given two round fixtures for a competition
        User user = User.create("coach_all", "hash");
        users.save(user);

        SaveGame save = SaveGame.create(user.getId(), 2025);
        saveGames.save(save);

        Club clubA = Club.create(save.getId(), "Joinville EC", "JEC");
        Club clubB = Club.create(save.getId(), "Criciúma EC", "CRI");
        clubs.save(clubA);
        clubs.save(clubB);

        Competition comp = Competition.create(save.getId(), "Copa SC 2025", "LEAGUE", 2025, 4);
        competitions.save(comp);

        fixtures.save(Fixture.create(save.getId(), comp.getId(), 1, clubA.getId(), clubB.getId()));
        fixtures.save(Fixture.create(save.getId(), comp.getId(), 2, clubB.getId(), clubA.getId()));

        // When findBySaveGameIdAndCompetitionId is called
        List<Fixture> all = fixtures.findBySaveGameIdAndCompetitionId(save.getId(), comp.getId());

        // Then both fixtures are returned
        assertThat(all).hasSize(2);
    }

    @Test
    void lineupPlayerCompositeKeyRoundTrip() {
        // Given a user, save, club, player, and lineup
        User user = User.create("coach_lineup", "hash");
        users.save(user);

        SaveGame save = SaveGame.create(user.getId(), 2025);
        saveGames.save(save);

        Club club = Club.create(save.getId(), "Chapecoense", "CHA");
        clubs.save(club);

        Player player = Player.create(save.getId(), club.getId(), "Guga Oliveira",
                Position.GK, 25, "Brasileiro", 60, 30, 40, 50, 80, 70, 72);
        players.save(player);

        Lineup lineup = Lineup.create(save.getId(), club.getId(), "4-4-2");
        lineupRepository.save(lineup);

        // When a LineupPlayer is saved with composite key (lineup_id, player_id)
        LineupPlayer lineupPlayer = LineupPlayer.create(lineup.getId(), player.getId(), true);
        lineupPlayerRepository.save(lineupPlayer);

        // Then it can be found by its composite key
        LineupPlayerId compositeId = new LineupPlayerId(lineup.getId(), player.getId());
        Optional<LineupPlayer> found = lineupPlayerRepository.findById(compositeId);

        assertThat(found).isPresent();
        assertThat(found.get().getLineupId()).isEqualTo(lineup.getId());
        assertThat(found.get().getPlayerId()).isEqualTo(player.getId());
        assertThat(found.get().isStarting()).isTrue();
    }
}
