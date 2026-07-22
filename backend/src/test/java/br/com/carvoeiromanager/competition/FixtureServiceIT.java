package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureServiceIT {

    @Autowired FixtureService fixtureService;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("fixtures_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void returnsManagedClubSeasonFixturesOrderedByRoundWithOrientedGoalsAndStadium() {
        // Given a career with at least a few rounds played, so both played and unplayed
        // fixtures exist for the managed club
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        UUID managedClubId = c.save().managedClubId();
        assertThat(managedClubId).isNotNull();

        int guard = 0;
        int playedManagedFixtures = 0;
        while (playedManagedFixtures < 2
                && saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
            playedManagedFixtures = (int) fixtureRepository
                    .findBySaveGameIdAndCompetitionId(c.id(), competitionId).stream()
                    .filter(f -> f.isPlayed()
                            && (f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId)))
                    .count();
        }
        assertThat(playedManagedFixtures).isGreaterThanOrEqualTo(2);

        // When fetching the managed club's season fixtures for the competition
        List<FixtureEntryResponse> entries = fixtureService.getFixtures(c.id(), competitionId, c.userId());

        // Then every fixture involving the managed club is present, ordered by round
        List<Fixture> rawManagedFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(c.id(), competitionId).stream()
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .toList();
        assertThat(entries).hasSize(rawManagedFixtures.size());
        for (int i = 1; i < entries.size(); i++) {
            assertThat(entries.get(i - 1).round()).isLessThanOrEqualTo(entries.get(i).round());
        }

        Map<UUID, Fixture> rawById = rawManagedFixtures.stream()
                .collect(Collectors.toMap(Fixture::getId, Function.identity()));
        Map<UUID, Club> clubById = clubRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));

        boolean sawPlayed = false;
        boolean sawUnplayed = false;
        boolean sawHome = false;
        boolean sawAway = false;
        for (FixtureEntryResponse entry : entries) {
            Fixture raw = rawById.get(entry.fixtureId());
            assertThat(raw).isNotNull();

            boolean expectedHome = raw.getHomeClubId().equals(managedClubId);
            assertThat(entry.home()).isEqualTo(expectedHome);
            if (expectedHome) {
                sawHome = true;
            } else {
                sawAway = true;
            }

            UUID expectedOpponentId = expectedHome ? raw.getAwayClubId() : raw.getHomeClubId();
            assertThat(entry.opponentClubId()).isEqualTo(expectedOpponentId);
            Club opponent = clubById.get(expectedOpponentId);
            assertThat(entry.opponentName()).isEqualTo(opponent.getName());
            assertThat(entry.opponentShortName()).isEqualTo(opponent.getShortName());

            Club homeClub = clubById.get(raw.getHomeClubId());
            assertThat(entry.stadiumName()).isEqualTo(homeClub.resolvedStadiumName());

            assertThat(entry.played()).isEqualTo(raw.isPlayed());
            if (raw.isPlayed()) {
                sawPlayed = true;
                Integer expectedGoalsFor = expectedHome ? raw.getHomeGoals() : raw.getAwayGoals();
                Integer expectedGoalsAgainst = expectedHome ? raw.getAwayGoals() : raw.getHomeGoals();
                assertThat(entry.goalsFor()).isEqualTo(expectedGoalsFor);
                assertThat(entry.goalsAgainst()).isEqualTo(expectedGoalsAgainst);
            } else {
                sawUnplayed = true;
                assertThat(entry.goalsFor()).isNull();
                assertThat(entry.goalsAgainst()).isNull();
            }
        }
        assertThat(sawPlayed).isTrue();
        assertThat(sawUnplayed).isTrue();
        assertThat(sawHome).isTrue();
        assertThat(sawAway).isTrue();
    }

    @Test
    void foreignUserCannotReadFixtures() {
        Career owner = createCareer();
        Career intruder = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(owner.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> fixtureService.getFixtures(owner.id(), competitionId, intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
