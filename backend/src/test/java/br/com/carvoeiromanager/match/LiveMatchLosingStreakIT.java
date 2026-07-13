package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchLosingStreakIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired FixtureRepository fixtureRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;
    private UUID competitionId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_streak_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();
        competitionId = save.competitionId();

        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    private UUID anOpponentClubId() {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(managedClubId))
                .findFirst().orElseThrow();
    }

    @Test
    void halfTimeShowsTheStreakLineAfterTwoConsecutiveLosses() {
        UUID opponentId = anOpponentClubId();
        Fixture olderLoss = Fixture.create(saveGameId, competitionId, 1, managedClubId, opponentId, null);
        olderLoss.recordResult(0, 2);
        fixtureRepository.save(olderLoss);
        Fixture recentLoss = Fixture.create(saveGameId, competitionId, 2, opponentId, managedClubId, null);
        recentLoss.recordResult(3, 1);
        fixtureRepository.save(recentLoss);

        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"HALF_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }

        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("HALF_TIME");
        LiveEventResponse halfTime = state.events().stream()
                .filter(e -> "HALF_TIME".equals(e.type())).findFirst().orElseThrow();
        assertThat(halfTime.text()).isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeKeepsTheGenericLineWithoutAStreak() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 200 && (state == null || !"HALF_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }

        assertThat(state).isNotNull();
        LiveEventResponse halfTime = state.events().stream()
                .filter(e -> "HALF_TIME".equals(e.type())).findFirst().orElseThrow();
        assertThat(halfTime.text()).isEqualTo("Intervalo — fim do primeiro tempo.");
    }
}
