package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
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
    br.com.elifootcarvoeiro.player.PlayerRepository playerRepository;

    @Autowired
    br.com.elifootcarvoeiro.club.ClubRepository clubRepository;

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

        // Pick exactly 1 GK + 10 outfield
        List<UUID> startingIds = buildValidXiIds(clubId);

        SetLineupRequest req = new SetLineupRequest("4-4-2", startingIds);

        // When - first call
        LineupResponse response = lineupService.setLineup(saveGameId, clubId, req, userId);

        // Then
        assertThat(response.lineupId()).isNotNull();
        assertThat(response.clubId()).isEqualTo(clubId);
        assertThat(response.formation()).isEqualTo("4-4-2");
        assertThat(response.startingPlayerIds()).containsExactlyInAnyOrderElementsOf(startingIds);

        // Persisted: exactly 1 lineup row for this (saveGame, club)
        Lineup persistedLineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new AssertionError("Expected lineup to exist"));
        UUID lineupId = persistedLineup.getId();

        // Exactly 11 lineup_players
        List<LineupPlayer> players = lineupPlayerRepository.findByLineupId(lineupId);
        assertThat(players).hasSize(11);
        assertThat(players).allMatch(LineupPlayer::isStarting);

        // When - second call (upsert: change formation)
        SetLineupRequest req2 = new SetLineupRequest("4-3-3", startingIds);
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

        List<UUID> noGkIds = buildXiWithNoGk(career);

        SetLineupRequest req = new SetLineupRequest("4-4-2", noGkIds);

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

        List<UUID> tenPlayers = buildValidXiIds(clubId).subList(0, 10);

        SetLineupRequest req = new SetLineupRequest("4-4-2", tenPlayers);

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
        List<br.com.elifootcarvoeiro.player.Player> xi = lineupService.bestXi(clubId);

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
        List<UUID> validXi1 = buildValidXiIds(clubId1);

        // Get a player from club 2
        List<br.com.elifootcarvoeiro.player.Player> club2Players = playerRepository.findByClubId(clubId2);
        assertThat(club2Players).isNotEmpty();
        UUID foreignPlayerId = club2Players.get(0).getId();

        // Replace the last player in the XI with a foreign player
        List<UUID> xiBWithForeigner = new java.util.ArrayList<>(validXi1);
        xiBWithForeigner.set(10, foreignPlayerId);

        SetLineupRequest req = new SetLineupRequest("4-4-2", xiBWithForeigner);

        // When / Then
        assertThatThrownBy(() -> lineupService.setLineup(career1.id(), clubId1, req, career1.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LINEUP");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    // --- Private helpers ---

    /**
     * Builds a valid list of 11 player IDs for the club: best XI via lineupService.bestXi.
     */
    private List<UUID> buildValidXiIds(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(br.com.elifootcarvoeiro.player.Player::getId)
                .collect(Collectors.toList());
    }

    /**
     * Builds 11 player IDs with NO GK (replaces the GK with the next best outfield player).
     */
    private List<UUID> buildXiWithNoGk(Career career) {
        SquadResponse squad = lineupService.getSquad(career.id(), career.managedClubId(), career.userId());

        // Get all non-GK player IDs
        List<UUID> outfieldIds = squad.players().stream()
                .filter(p -> !p.position().equals("GK"))
                .map(PlayerResponse::id)
                .toList();

        // Return first 11 outfield players (no GK)
        return outfieldIds.stream().limit(11).collect(Collectors.toList());
    }

}
