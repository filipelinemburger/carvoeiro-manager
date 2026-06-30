package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.season.SeasonEventRepository;
import br.com.elifootcarvoeiro.season.SeasonEventType;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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

        // Then competition-club memberships are persisted (10 clubs in Catarinense)
        var catarinense = competitionRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        var memberships = competitionClubRepository.findByCompetitionId(catarinense.getId());
        assertThat(memberships).hasSize(10);

        // All fixtures have a match_date set (routed through SeasonCalendarGenerator)
        var fixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId());
        assertThat(fixtures).hasSize(90); // 10 clubs * 9 opponents * 2 legs
        assertThat(fixtures).allMatch(f -> f.getMatchDate() != null,
                "Every fixture must have a match_date assigned by SeasonCalendarGenerator");

        // The calendar starts at season start (Jan 15): the first dated match-unit falls on
        // that day. Which competition owns match-unit 0 depends on competition-id ordering,
        // so assert over the whole save rather than a single competition.
        LocalDate seasonStart = LocalDate.of(response.seasonYear(), 1, 15);
        assertThat(fixtureRepository.findBySaveGameIdAndMatchDate(saveGameId, seasonStart)).isNotEmpty();

        // MATCH season events exist (one per round of the Catarinense = 18 rounds for 10 clubs)
        var events = seasonEventRepository.findBySaveGameId(saveGameId);
        var matchEvents = events.stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> catarinense.getId().equals(e.getCompetitionId()))
                .toList();
        assertThat(matchEvents).hasSize(18); // 2*(10-1) = 18 rounds

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
        assertThat(response.competitions()).hasSize(2);
        assertThat(response.competitions()).anyMatch(c -> c.name().equals("Campeonato Catarinense") && c.type().equals("LEAGUE"));
    }
}
