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
        SetLineupRequest req = new SetLineupRequest("4-4-2", starters, null);

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
        SetLineupRequest req2 = new SetLineupRequest("4-3-3", starters, null);
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
        SetLineupRequest req = new SetLineupRequest("4-4-2", noGk, null);

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
        SetLineupRequest req = new SetLineupRequest("4-4-2", ten, null);

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
        SetLineupRequest req = new SetLineupRequest("4-4-2", withForeigner, null);

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
                lineupService.setLineup(saveGameId, clubId, new SetLineupRequest("4-4-2", starters, null), career.userId()))
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
                lineupService.setLineup(career.id(), clubId, new SetLineupRequest("4-4-2", starters, null), career.userId()))
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
        lineupService.setLineup(saveGameId, clubId, new SetLineupRequest("4-3-3", starters, null), userId);

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

    // --- (j) bestXi ranks GK by highest rating, and the date path benches the drained boundary player ---

    @Test
    void bestXiPicksHighestRatedGkAndDatePathBenchesDrainedPlayer() {
        // GIVEN a career club whose player pool we reshape into a deterministic boundary.
        //
        // Goalkeepers (guards the .max direction — must pick the HIGHER overall):
        //   - GK A at overall 70 (both paths must select this one)
        //   - GK B at overall 55 (must never be selected)
        //   - remaining GKs at overall 30
        //
        // Outfield — 11 fresh/adjusted candidates for 10 slots:
        //   - 8 players at overall 75 (fresh)                     → rating 75.0 (both paths)
        //   - fresh boundary at overall 60, fitness 100           → rating 60.0 (both paths)
        //   - drained boundary at overall 60, fitness 10 on date  → rating 60.0 (no-date) / 49.2 (date)
        //   - filler at overall 55 (fresh)                        → rating 55.0 (both paths)
        //   - remaining outfield at overall 30 (clearly out)
        //
        // No-date path (raw overall): top 10 outfield = the 8×75 + both 60s; the 55 filler is excluded.
        // Date path: the 8×75 + fresh-60 stay; filler-55 (55.0) beats drained-60 (49.2), so drained is benched.
        Career career = createCareer();
        UUID clubId = career.managedClubId();
        LocalDate matchDate = LocalDate.of(2025, 8, 10);

        List<Player> allPlayers = playerRepository.findByClubId(clubId);

        List<Player> gks = allPlayers.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .collect(Collectors.toList());
        List<Player> outfield = allPlayers.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .collect(Collectors.toList());

        assertThat(gks.size()).isGreaterThanOrEqualTo(2);
        assertThat(outfield.size()).isGreaterThanOrEqualTo(11);

        // Two eligible GKs at different overalls; the rest clearly out.
        Player topGk = gks.get(0);
        topGk.setOverall(70);
        playerRepository.save(topGk);
        Player lowGk = gks.get(1);
        lowGk.setOverall(55);
        playerRepository.save(lowGk);
        for (int i = 2; i < gks.size(); i++) {
            gks.get(i).setOverall(30);
            playerRepository.save(gks.get(i));
        }

        // 8 clear top outfield players at overall 75 (fresh).
        for (int i = 0; i < 8; i++) {
            outfield.get(i).setOverall(75);
            playerRepository.save(outfield.get(i));
        }

        // Fresh boundary player: overall 60, default fitness 100, no lastMatchDate → rating 60.0.
        Player freshPlayer = outfield.get(8);
        freshPlayer.setOverall(60);
        playerRepository.save(freshPlayer);

        // Drained boundary player: overall 60, base fitness 10, lastMatchDate = matchDate (no recovery)
        // → effectiveFitness 10, rating 60 × 0.82 = 49.2 on the date path (60.0 on the no-date path).
        Player drainedPlayer = outfield.get(9);
        drainedPlayer.setOverall(60);
        drainedPlayer.recordAppearance(10, matchDate);
        playerRepository.save(drainedPlayer);

        // Fresh filler: overall 55 → rating 55.0. Below both 60s on overall (no-date excludes it),
        // but above the drained-60's 49.2 on the date path (date includes it, benching the drained one).
        Player fillerPlayer = outfield.get(10);
        fillerPlayer.setOverall(55);
        playerRepository.save(fillerPlayer);

        // Everyone else clearly out.
        for (int i = 11; i < outfield.size(); i++) {
            outfield.get(i).setOverall(30);
            playerRepository.save(outfield.get(i));
        }

        UUID topGkId = topGk.getId();
        UUID lowGkId = lowGk.getId();
        UUID freshId = freshPlayer.getId();
        UUID drainedId = drainedPlayer.getId();
        UUID fillerId = fillerPlayer.getId();

        // WHEN — no-date path (raw overall)
        List<Player> xiNoDate = lineupService.bestXi(clubId);

        // THEN — full XI, the higher-rated GK is chosen, both equal-overall boundary players are in,
        //        and the lower-overall filler is excluded.
        assertThat(xiNoDate).hasSize(11);
        assertThat(xiNoDate.stream().filter(p -> p.getPosition() == Position.GK).count()).isEqualTo(1);
        assertThat(xiNoDate.stream().anyMatch(p -> p.getId().equals(topGkId))).isTrue();
        assertThat(xiNoDate.stream().anyMatch(p -> p.getId().equals(lowGkId))).isFalse();
        assertThat(xiNoDate.stream().anyMatch(p -> p.getId().equals(freshId))).isTrue();
        assertThat(xiNoDate.stream().anyMatch(p -> p.getId().equals(drainedId))).isTrue();
        assertThat(xiNoDate.stream().anyMatch(p -> p.getId().equals(fillerId))).isFalse();

        // WHEN — date path (fitness-adjusted rating)
        List<Player> xi = lineupService.bestXi(clubId, matchDate);

        // THEN — full XI, still the higher-rated GK, the fresh boundary and the filler start,
        //        and the drained boundary player is benched.
        assertThat(xi).hasSize(11);
        assertThat(xi.stream().filter(p -> p.getPosition() == Position.GK).count()).isEqualTo(1);
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(topGkId))).isTrue();
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(lowGkId))).isFalse();
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(freshId))).isTrue();
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(fillerId))).isTrue();
        assertThat(xi.stream().anyMatch(p -> p.getId().equals(drainedId))).isFalse();
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
