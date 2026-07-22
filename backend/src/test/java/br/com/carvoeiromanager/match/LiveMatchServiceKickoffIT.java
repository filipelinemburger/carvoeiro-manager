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
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchServiceKickoffIT {

    @Autowired
    LiveMatchService liveMatchService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    br.com.carvoeiromanager.career.SaveGameRepository saveGameRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupService lineupService;

    @Autowired
    LiveMatchRepository liveMatchRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    @Autowired
    FixtureRepository fixtureRepository;

    @Autowired
    ClubRepository clubRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_kickoff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        // Save a valid 11-player XI + 3 bench players for the managed club
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

    @Test
    void getLiveReturnsEmptyWhenNoSession() {
        assertThat(liveMatchService.getLive(saveGameId, userId)).isEmpty();
    }

    @Test
    void kickoffCreatesASessionAndIsIdempotent() {
        LiveMatchState first = liveMatchService.kickoff(saveGameId, userId);
        assertThat(first.minute()).isZero();
        assertThat(first.status()).isEqualTo("IN_PROGRESS");
        assertThat(first.onPitch()).hasSize(11);
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isPresent();

        // Idempotent: a second kickoff returns the same in-progress session (does not error/duplicate).
        LiveMatchState again = liveMatchService.kickoff(saveGameId, userId);
        assertThat(again.fixtureId()).isEqualTo(first.fixtureId());
        // Save-scoped: the unique constraint on save_game_id means presence after two kickoffs
        // proves no duplicate was created for this save game (avoids cross-IT flake from shared container).
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isPresent();
    }

    @Test
    void kickoffBumpsLastPlayedAt() {
        // Given the career from @BeforeEach (already has a valid lineup)
        Instant beforeKickoff = Instant.now();

        // When kicking off
        liveMatchService.kickoff(saveGameId, userId);

        // Then lastPlayedAt moved to at/after this call
        br.com.carvoeiromanager.career.SaveGame after = saveGameRepository.findById(saveGameId).orElseThrow();
        assertThat(after.getLastPlayedAt()).isAfterOrEqualTo(beforeKickoff);
    }

    @Test
    void kickoffGivesTheAiSideABench() {
        // After kickoff: the NON-managed side's persisted bench ids are non-empty (<= 7),
        // resolve to players of the AI club, and include a GK when the AI squad has a spare.
        // The managed side's bench behavior is unchanged.
        liveMatchService.kickoff(saveGameId, userId);

        LiveMatch lm = liveMatchService.requireLive(saveGameId);
        LiveMatchConfig cfg = liveMatchService.configFor(lm);
        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        UUID aiClubId = lm.isManagedIsHome() ? fixture.getAwayClubId() : fixture.getHomeClubId();

        List<Player> aiBench = lm.isManagedIsHome() ? cfg.awayBench() : cfg.homeBench();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();

        // The AI side now gets a real bench.
        assertThat(aiBench).isNotEmpty();
        assertThat(aiBench.size()).isLessThanOrEqualTo(7);
        assertThat(aiBench).allMatch(p -> p.getClubId().equals(aiClubId));
        // Every seeded club has at least one spare GK beyond its starting XI.
        assertThat(aiBench.stream().anyMatch(p -> p.getPosition() == Position.GK)).isTrue();

        // The managed side's bench behavior is unchanged: exactly the 3 saved bench players.
        assertThat(managedBench).hasSize(3);
        assertThat(managedBench).allMatch(p -> p.getClubId().equals(managedClubId));
    }

    @Test
    void kickoffPopulatesShortClubNames() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        assertThat(state.homeClubShortName()).isNotBlank();
        assertThat(state.awayClubShortName()).isNotBlank();
    }

    @Test
    void kickoffPopulatesTheHomeClubsStadiumName() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Fixture fixture = fixtureRepository.findById(UUID.fromString(state.fixtureId())).orElseThrow();
        Club homeClub = clubRepository.findById(fixture.getHomeClubId()).orElseThrow();

        assertThat(state.stadiumName()).isNotBlank();
        assertThat(state.stadiumName()).isEqualTo(homeClub.resolvedStadiumName());
    }
}
