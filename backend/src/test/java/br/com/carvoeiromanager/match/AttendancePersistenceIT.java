package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Task 9: attendance is computed at match resolution (FixtureSimulator /
 * LiveMatchService, via AttendanceCalculator + MatchContextResolver) and persisted on the
 * fixture row, rather than being recomputed later by FinanceService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AttendancePersistenceIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;

    private UUID userId;

    private UUID createSave() {
        User user = User.create("attendance_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    /**
     * Advances until the next MATCH event is processed (skipping TRANSFER stops) — the same
     * pattern as {@code FinanceAdvanceIT#advanceToNextMatch}, reused here rather than inventing
     * a new advance path.
     */
    private void advanceOneRound(UUID saveGameId) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(saveGameId, userId);
            if ("MATCH".equals(r.type())) {
                return;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void everyPlayedFixtureRecordsAPositiveAttendance() {
        UUID saveGameId = createSave();
        advanceOneRound(saveGameId);

        List<Fixture> played = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .toList();

        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(fixture ->
                assertThat(fixture.getAttendance())
                        .as("attendance of fixture %s", fixture.getId())
                        .isNotNull()
                        .isPositive());
    }

    @Test
    void unplayedFixturesHaveNoAttendance() {
        UUID saveGameId = createSave();

        List<Fixture> unplayed = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(f -> !f.isPlayed())
                .toList();

        assertThat(unplayed).isNotEmpty();
        assertThat(unplayed).allMatch(f -> f.getAttendance() == null);
    }
}
