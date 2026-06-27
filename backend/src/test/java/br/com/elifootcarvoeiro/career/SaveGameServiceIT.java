package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SaveGameServiceIT {

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SaveGameRepository saveGameRepository;

    @Autowired
    ClubRepository clubRepository;

    @Autowired
    PlayerRepository playerRepository;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    FixtureRepository fixtureRepository;

    @Test
    void creatingCareerSeedsClubsPlayersAndFullFixtureList() {
        // Given a persisted user
        User user = User.create("tigre_career", "hash");
        userRepository.save(user);
        UUID userId = user.getId();

        // When createCareer is called
        SaveGameResponse response = saveGameService.createCareer(userId);

        // Then a SaveGame was created with correct fields
        assertThat(response.id()).isNotNull();
        assertThat(response.seasonYear()).isGreaterThan(0);
        assertThat(response.competitionId()).isNotNull();
        assertThat(response.competitionName()).isNotBlank();
        assertThat(response.managedClubId()).isNotNull();
        assertThat(response.managedClubName()).isNotBlank();
        assertThat(response.currentRound()).isEqualTo(0);
        assertThat(response.totalRounds()).isGreaterThan(0);

        UUID saveGameId = response.id();

        // SaveGame persisted in DB
        assertThat(saveGameRepository.findById(saveGameId)).isPresent();

        // Exactly 10 clubs exist (Catarinense has 10 clubs)
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).hasSize(10);

        // Every club has at least one player
        clubs.forEach(club ->
                assertThat(playerRepository.findByClubId(club.getId()))
                        .as("Club %s should have players", club.getName())
                        .isNotEmpty()
        );

        // Managed club is Criciúma (short name "CRI" maps to the club's full name from seed)
        assertThat(response.managedClubName()).contains("Criciúma");

        // Competition: 1 competition, totalRounds == 2*(clubCount-1)
        int clubCount = clubs.size(); // 10
        var competitions = competitionRepository.findAll().stream()
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .toList();
        assertThat(competitions).hasSize(1);
        assertThat(competitions.get(0).getTotalRounds()).isEqualTo(2 * (clubCount - 1));

        // Fixtures: clubCount * (clubCount - 1) fixtures, all unplayed
        var allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(
                saveGameId, competitions.get(0).getId());
        int expectedFixtures = clubCount * (clubCount - 1); // 90 for 10 clubs
        assertThat(allFixtures).hasSize(expectedFixtures);
        assertThat(allFixtures).allMatch(f -> !f.isPlayed());
        assertThat(allFixtures).allMatch(f -> f.getHomeGoals() == null);
        assertThat(allFixtures).allMatch(f -> f.getAwayGoals() == null);
    }

    @Test
    void creatingCareerSeedsClubFinancialProfilesAndPlayerWages() {
        // Given a persisted user
        User user = User.create("tigre_finance", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then every club has a populated financial profile
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).isNotEmpty();
        clubs.forEach(club -> {
            assertThat(club.getReputation()).as("reputation").isGreaterThan(0);
            assertThat(club.getStadiumCapacity()).as("capacity").isGreaterThan(0);
            assertThat(club.getBalance()).as("balance").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getSponsorshipPerRound()).as("sponsorship").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getStadiumUpkeepPerRound()).as("upkeep").isGreaterThan(java.math.BigDecimal.ZERO);
        });

        // And every club player has a positive wage (free agents have wage=0 by design)
        var clubPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null).toList();
        assertThat(clubPlayers)
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getWage()).isGreaterThan(java.math.BigDecimal.ZERO));
    }
}
