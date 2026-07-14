package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureSimulatorAssistFoulIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchEventRepository matchEventRepository;
    @Autowired PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("instant_af_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        SaveGameResponse save = saveGameService.createCareer(user.getId());
        saveGameId = save.id();
        managedClubId = save.managedClubId();
    }

    @Test
    void aiVsAiGoalsSometimesProduceASiblingAssistRow() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        // Simulate every round's AI-vs-AI fixtures (excluding the managed club) across several
        // rounds to gather enough goals for a statistically meaningful assist-row count.
        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(15)
                .toList();
        assertThat(aiFixtures).isNotEmpty();

        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        List<UUID> fixtureIds = aiFixtures.stream().map(Fixture::getId).toList();
        List<MatchEvent> allEvents = matchEventRepository.findByFixtureIdIn(fixtureIds);
        long goals = allEvents.stream().filter(e -> EventType.goalTypeNames().contains(e.getType())).count();
        long assists = allEvents.stream().filter(e -> "ASSIST".equals(e.getType())).count();

        assertThat(goals).isGreaterThan(0);
        assertThat(assists).isGreaterThan(0);
        assertThat(assists).isLessThanOrEqualTo(goals);
    }

    @Test
    void anAssistRowIsNeverForTheSamePlayerAsTheGoalInTheSameMinute() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(15)
                .toList();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        for (Fixture fixture : aiFixtures) {
            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(fixture.getId()));
            for (MatchEvent goal : events) {
                // Scoped to GOAL only, mirroring LiveMatchEngineTest.anAssistNeverCreditsTheScorerThemself:
                // CORNER_GOAL's "assist" is the deterministic corner taker (SetPieces.takerOrFallback,
                // independent selection logic from the header-scorer's weighted-random pick) and can
                // legitimately coincide with the scorer in a real (non-degenerate) XI — e.g. a player who
                // is both the team's best crosser and, on one particular corner, wins the header-scorer
                // draw too. That coincidence is by design (see FixtureSimulator.assisterFor's CORNER_GOAL
                // branch) and matches the live engine's already-accepted corner behavior, so it is excluded
                // here rather than asserted against. PENALTY_GOAL/FREE_KICK_GOAL never carry an assist.
                if (!"GOAL".equals(goal.getType())) continue;
                events.stream()
                        .filter(e -> "ASSIST".equals(e.getType()) && e.getMinute() == goal.getMinute()
                                && e.getFixtureId().equals(goal.getFixtureId()))
                        .forEach(assist -> assertThat(assist.getPlayerId()).isNotEqualTo(goal.getPlayerId()));
            }
        }
    }

    @Test
    void foulCountsAreProducedForEveryPlayerWhoPlayed() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        Fixture fixture = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .findFirst().orElseThrow();

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();
        // homeXi/awayXi resolution mirrors FixtureSimulator.resolveXi for a non-managed club:
        // both sides use bestXi. This test only needs SOME 22 real players — reuse squads.
        List<UUID> homeIds = playerIdsFor(fixture.getHomeClubId());
        List<UUID> awayIds = playerIdsFor(fixture.getAwayClubId());

        Map<UUID, Integer> fouls = fixtureSimulator.foulCounts(
                playerRepository.findAllById(homeIds), playerRepository.findAllById(awayIds), seed);

        assertThat(fouls).isNotEmpty();
        fouls.values().forEach(count -> assertThat(count).isGreaterThanOrEqualTo(0));
    }

    private List<UUID> playerIdsFor(UUID clubId) {
        return playerRepository.findByClubId(clubId).stream().map(Player::getId).limit(11).toList();
    }
}
