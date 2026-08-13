package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that createCareer routes fixture + event creation through SeasonCalendarGenerator,
 * so every fixture carries a match_date and season_event rows exist for the new career.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
// Runs on the FULL seed, Série A included: it asserts what career creation really produces, so it must see the production seed.
// The test profile otherwise trims the seed to the player's own competitions to keep
// the suite fast — see carvoeiro.seed.competitions in application-test.yml.
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class CareerCreationCalendarIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    @Test
    void careerCreationGeneratesFixturesWithMatchDatesAndSeasonEvents() {
        // Given a persisted user
        User user = User.create("cal_career_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        // When createCareer is called
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then competition-club memberships are persisted (12 clubs in Catarinense)
        var catarinense = competitionRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        var memberships = competitionClubRepository.findByCompetitionId(catarinense.getId());
        assertThat(memberships).hasSize(12);

        // All fixtures have a match_date set (routed through SeasonCalendarGenerator)
        var fixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId());
        // 12 clubs, single round-robin first phase only: knockout phases are reserved but not
        // materialized until PhaseResolver runs (Task 6).
        assertThat(fixtures).hasSize(66); // 12 clubs * 11 opponents, single leg
        assertThat(fixtures).allMatch(f -> f.getMatchDate() != null,
                "Every fixture must have a match_date assigned by SeasonCalendarGenerator");

        // The calendar starts at season start (Jan 15): the first dated match-unit falls on
        // that day. Which competition owns match-unit 0 depends on competition-id ordering,
        // so assert over the whole save rather than a single competition.
        LocalDate seasonStart = LocalDate.of(response.seasonYear(), 1, 15);
        assertThat(fixtureRepository.findBySaveGameIdAndMatchDate(saveGameId, seasonStart)).isNotEmpty();

        // MATCH season events exist (one per match-unit of the Catarinense: 11 round-robin
        // rounds + 6 reserved knockout rounds = 17 match-units)
        var events = seasonEventRepository.findBySaveGameId(saveGameId);
        var matchEvents = events.stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> catarinense.getId().equals(e.getCompetitionId()))
                .toList();
        assertThat(matchEvents).hasSize(17); // 11 + 2 + 2 + 2 = 17 match-units

        // TRANSFER season events exist
        var transferEvents = events.stream()
                .filter(e -> e.getType() == SeasonEventType.TRANSFER)
                .toList();
        assertThat(transferEvents).isNotEmpty();

        // SaveGame currentDate is set to season start
        var savedGame = saveGameRepository.findById(saveGameId).orElseThrow();
        assertThat(savedGame.getCurrentDate()).isEqualTo(seasonStart);

        // SaveGameResponse exposes currentDate and competitions
        assertThat(response.currentDate()).isEqualTo(seasonStart.toString());
        // Catarinense + Série B (both managed) + Série A and Série C, which run in the background.
        assertThat(response.competitions()).hasSize(4);
        assertThat(response.competitions()).anyMatch(c -> c.name().equals("Campeonato Catarinense") && c.type().equals("LEAGUE"));
    }
}
