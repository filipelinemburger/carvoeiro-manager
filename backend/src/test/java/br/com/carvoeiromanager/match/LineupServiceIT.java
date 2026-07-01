package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupServiceIT {

    @Autowired
    LineupService lineupService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupRepository lineupRepository;

    @Autowired
    LineupPlayerRepository lineupPlayerRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    @Autowired
    br.com.carvoeiromanager.club.ClubRepository clubRepository;

    // --- Helpers ---

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID managedClubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("lineup_test_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save);
    }

    // --- (a) Setting a valid XI persists and reads back; upsert doesn't duplicate ---

    @Test
    void settingValidXiPersistsAndSubsequentCallUpdatesRatherThanDuplicating() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        SetLineupRequest req = new SetLineupRequest("4-4-2", starters);

        // When - first call
        LineupResponse response = lineupService.setLineup(saveGameId, clubId, req, userId);

        // Then
        assertThat(response.lineupId()).isNotNull();
        assertThat(response.clubId()).isEqualTo(clubId);
        assertThat(response.formation()).isEqualTo("4-4-2");
        assertThat(idsOf(response.starters())).containsExactlyInAnyOrderElementsOf(idsOf(starters));

        // Persisted: exactly 1 lineup row for this (saveGame, club)
        Lineup persistedLineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new AssertionError("Expected lineup to exist"));
        UUID lineupId = persistedLineup.getId();

        // Exactly 11 lineup_players
        List<LineupPlayer> players = lineupPlayerRepository.findByLineupId(lineupId);
        assertThat(players).hasSize(11);
        assertThat(players).allMatch(LineupPlayer::isStarting);

        // When - second call (upsert: change formation)
        SetLineupRequest req2 = new SetLineupRequest("4-3-3", starters);
        LineupResponse response2 = lineupService.setLineup(saveGameId, clubId, req2, userId);

        // Then - still only 1 lineup row, formation updated, still 11 players (no duplicates)
        assertThat(lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)).isPresent();
        assertThat(response2.formation()).isEqualTo("4-3-3");
        assertThat(lineupPlayerRepository.findByLineupId(lineupId)).hasSize(11);
    }

    // --- (b) 0 GKs → INVALID_LINEUP ---

    @Test
    void lineupWithNoGkIsRejected() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        // Use position-appropriate roles so this test isolates the no-GK rule
        // regardless of validator ordering (a hardcoded outfield role on a striker
        // would otherwise trip INVALID_ROLE and mask the INVALID_LINEUP we assert).
        List<LineupStarter> noGk = lineupService.getSquad(career.id(), clubId, userId).players().stream()
                .filter(p -> !p.position().equals("GK"))
                .limit(11)
                .map(p -> new LineupStarter(p.id(),
                        br.com.carvoeiromanager.player.PlayerRole.defaultFor(
                                br.com.carvoeiromanager.player.Position.valueOf(p.position())).name()))
                .collect(Collectors.toList());
        SetLineupRequest req = new SetLineupRequest("4-4-2", noGk);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- (c) != 11 players → INVALID_LINEUP ---

    @Test
    void lineupWithWrongCountIsRejected() {
        // Given
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> ten = buildValidStarters(clubId).subList(0, 10);
        SetLineupRequest req = new SetLineupRequest("4-4-2", ten);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(saveGameId, clubId, req, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- (d) bestXi returns 11 with exactly 1 GK ---

    @Test
    void bestXiReturnsElevenPlayersWithExactlyOneGk() {
        // Given
        Career career = createCareer();
        UUID clubId = career.managedClubId();

        // When
        List<br.com.carvoeiromanager.player.Player> xi = lineupService.bestXi(clubId);

        // Then
        assertThat(xi).hasSize(11);
        long gkCount = xi.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .count();
        assertThat(gkCount).isEqualTo(1);
    }

    // --- (e) Foreign player (from different club) → INVALID_LINEUP ---

    @Test
    void lineupWithForeignPlayerIsRejected() {
        // Given: two different careers (with two different managed clubs)
        Career career1 = createCareer();
        Career career2 = createCareer();

        UUID clubId1 = career1.managedClubId();
        UUID clubId2 = career2.managedClubId();

        // Build a valid XI for club 1
        List<LineupStarter> validXi1 = buildValidStarters(clubId1);

        // Get a player from club 2
        List<br.com.carvoeiromanager.player.Player> club2Players = playerRepository.findByClubId(clubId2);
        assertThat(club2Players).isNotEmpty();
        List<LineupStarter> withForeigner = new java.util.ArrayList<>(validXi1);
        withForeigner.set(10, new LineupStarter(club2Players.get(0).getId(), "POACHER"));
        SetLineupRequest req = new SetLineupRequest("4-4-2", withForeigner);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(career1.id(), clubId1, req, career1.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- (f) Suspended player cannot be selected ---

    @Test
    void suspendedPlayerCannotBeSelected() {
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        // suspend the 6th starter
        br.com.carvoeiromanager.player.Player victim =
                playerRepository.findById(starters.get(5).playerId()).orElseThrow();
        victim.addSuspension(1);
        playerRepository.save(victim);

        assertThatThrownBy(() ->
                lineupService.setLineup(saveGameId, clubId, new SetLineupRequest("4-4-2", starters), career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("SUSPENDED_PLAYER");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- (g) Role invalid for position is rejected ---

    @Test
    void roleInvalidForPositionIsRejected() {
        Career career = createCareer();
        UUID clubId = career.managedClubId();
        List<LineupStarter> starters = new java.util.ArrayList<>(buildValidStarters(clubId));
        // Force the GK starter to carry an outfield role -> invalid
        int gkIndex = -1;
        List<PlayerResponse> squad = lineupService.getSquad(career.id(), clubId, career.userId()).players();
        for (int i = 0; i < starters.size(); i++) {
            UUID pid = starters.get(i).playerId();
            if (squad.stream().anyMatch(s -> s.id().equals(pid) && s.position().equals("GK"))) {
                gkIndex = i;
                break;
            }
        }
        starters.set(gkIndex, new LineupStarter(starters.get(gkIndex).playerId(), "POACHER"));

        assertThatThrownBy(() ->
                lineupService.setLineup(career.id(), clubId, new SetLineupRequest("4-4-2", starters), career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_ROLE");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- (h) getLineup returns the saved formation + starters with roles ---

    @Test
    void getLineupReturnsTheSavedFormationAndStarters() {
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();
        UUID userId = career.userId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        lineupService.setLineup(saveGameId, clubId, new SetLineupRequest("4-3-3", starters), userId);

        Optional<LineupResponse> saved = lineupService.getLineup(saveGameId, clubId, userId);

        assertThat(saved).isPresent();
        assertThat(saved.get().formation()).isEqualTo("4-3-3");
        assertThat(saved.get().clubId()).isEqualTo(clubId);
        assertThat(idsOf(saved.get().starters())).containsExactlyInAnyOrderElementsOf(idsOf(starters));
        assertThat(saved.get().starters()).allSatisfy(s -> assertThat(s.role()).isNotBlank());
    }

    // --- (i) getLineup is empty when the club has never saved a lineup ---

    @Test
    void getLineupIsEmptyWhenNoneSaved() {
        Career career = createCareer();
        Optional<LineupResponse> saved =
                lineupService.getLineup(career.id(), career.managedClubId(), career.userId());
        assertThat(saved).isEmpty();
    }

    // --- (j) bestXi(clubId, date) picks the fresh player over the equally-rated but drained one ---

    @Test
    void bestXiWithDatePrefersFreshPlayerOverDrainedPlayerWithEqualOverall() {
        // GIVEN a career club whose player pool we reshape to create a deterministic boundary:
        //   - 1 GK at overall 90 (unambiguous GK slot)
        //   - 9 outfield players at overall 75 (guaranteed top-9 outfield)
        //   - 1 fresh outfield player at overall 60, fitness 100, no lastMatchDate
        //   - 1 drained outfield player at overall 60, fitness 10, lastMatchDate = matchDate (no recovery)
        //   - remaining outfield players set to overall 30 (clearly out)
        Career career = createCareer();
        UUID clubId = career.managedClubId();
        LocalDate matchDate = LocalDate.of(2025, 8, 10);

        List<Player> allPlayers = playerRepository.findByClubId(clubId);

        // Partition by position
        List<Player> gks = allPlayers.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .collect(Collectors.toList());
        List<Player> outfield = allPlayers.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .collect(Collectors.toList());

        assertThat(gks).isNotEmpty();
        assertThat(outfield.size()).isGreaterThanOrEqualTo(11);

        // Ensure exactly one GK dominates
        Player bestGk = gks.get(0);
        bestGk.setOverall(90);
        playerRepository.save(bestGk);
        for (int i = 1; i < gks.size(); i++) {
            gks.get(i).setOverall(30);
            playerRepository.save(gks.get(i));
        }

        // Top 9 outfield players — clear slots 1-9
        for (int i = 0; i < 9; i++) {
            outfield.get(i).setOverall(75);
            playerRepository.save(outfield.get(i));
        }

        // Boundary fresh player (slot 10 when ranked by fitness-adjusted rating)
        Player freshPlayer = outfield.get(9);
        freshPlayer.setOverall(60);
        // fitness=100 (default) and no lastMatchDate → effectiveFitness=100, rating=60×1.0=60.0
        playerRepository.save(freshPlayer);

        // Boundary drained player (loses slot 10 on fitness-adjusted ranking)
        Player drainedPlayer = outfield.get(10);
        drainedPlayer.setOverall(60);
        // lastMatchDate=matchDate, base fitness=10 → effectiveFitness=10, rating=60×0.82=49.2
        drainedPlayer.recordAppearance(10, matchDate);
        playerRepository.save(drainedPlayer);

        // Set remaining outfield players to overall 30 so they are clearly excluded
        for (int i = 11; i < outfield.size(); i++) {
            outfield.get(i).setOverall(30);
            playerRepository.save(outfield.get(i));
        }

        UUID freshId = freshPlayer.getId();
        UUID drainedId = drainedPlayer.getId();

        // WHEN bestXi with matchDate is computed
        List<Player> xi = lineupService.bestXi(clubId, matchDate);

        // THEN exactly 11 players, the fresh one is in, the drained one is benched
        assertThat(xi).hasSize(11);
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(freshId))).isTrue();
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(drainedId))).isFalse();

        // AND the null-date overload (raw overall) preserves size/GK invariants —
        // both boundary players share overall=60, so fitness does not influence the no-date path.
        List<Player> xiNoDate = lineupService.bestXi(clubId);
        assertThat(xiNoDate).hasSize(11);
        assertThat(xiNoDate.stream().filter(p -> p.getPosition() == Position.GK).count()).isEqualTo(1);
    }

    // --- Private helpers ---

    private List<LineupStarter> buildValidStarters(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(
                        p.getId(),
                        br.com.carvoeiromanager.player.PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());
    }

    private List<UUID> idsOf(List<LineupStarter> starters) {
        return starters.stream().map(LineupStarter::playerId).collect(Collectors.toList());
    }

}
