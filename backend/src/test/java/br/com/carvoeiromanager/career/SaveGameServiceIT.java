package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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

        // All clubs from the pool are created (24 clubs: 10 SC + 14 national)
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).hasSize(24);

        // Every club has at least one player
        clubs.forEach(club ->
                assertThat(playerRepository.findByClubId(club.getId()))
                        .as("Club %s should have players", club.getName())
                        .isNotEmpty()
        );

        // Managed club is Criciúma (short name "CRI" maps to the club's full name from seed)
        assertThat(response.managedClubName()).contains("Criciúma");

        // Competition: 2 competitions now (Catarinense + Série B)
        var competitions = competitionRepository.findAll().stream()
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .toList();
        assertThat(competitions).hasSize(2);
        var catarinense = competitions.stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        int catarinenseClubCount = 10;
        assertThat(catarinense.getTotalRounds()).isEqualTo(2 * (catarinenseClubCount - 1));

        // Fixtures for Catarinense: 10 * 9 = 90 fixtures, all unplayed
        var allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId());
        int expectedFixtures = catarinenseClubCount * (catarinenseClubCount - 1); // 90 for 10 clubs
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
