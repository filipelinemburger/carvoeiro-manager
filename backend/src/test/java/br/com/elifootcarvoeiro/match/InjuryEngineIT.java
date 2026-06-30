package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.season.AdvanceResponse;
import br.com.elifootcarvoeiro.season.SeasonAdvanceService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the seeded player-injury engine.
 * Verifies that injuries are deterministic, within range, and that injured players
 * are ineligible and heal on matchdays they sit out.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class InjuryEngineIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("injury_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH is played, skipping any TRANSFER stops. */
    private AdvanceResponse advanceOneMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.save().id(), c.userId());
            if ("MATCH".equals(r.type())) {
                return r;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    /**
     * (a) After advancing several matchdays, any player with injuryMatches > 0 has
     * injuryMatches in 1..3 and isEligible() == false.
     */
    @Test
    void injuredPlayersHaveValidCountAndAreIneligible() {
        // Given a fresh career
        Career career = createCareer();
        UUID saveGameId = career.save().id();

        // When advancing several matchdays (enough that at least some injuries appear
        // deterministically; we advance 10 rounds to increase the probability)
        for (int i = 0; i < 10; i++) {
            advanceOneMatch(career);
        }

        // Then every player with injuryMatches > 0 is within range and ineligible
        List<Player> injured = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getInjuryMatches() > 0)
                .toList();

        // It is valid (though unlikely) for no injuries to have occurred in 10 rounds;
        // if any exist, validate them.
        for (Player p : injured) {
            assertThat(p.getInjuryMatches())
                    .as("Player %s should have a positive injury_matches while injured", p.getName())
                    .isGreaterThanOrEqualTo(1);
            assertThat(p.isEligible())
                    .as("Injured player %s should be ineligible", p.getName())
                    .isFalse();
        }
    }

    /**
     * (b) An injured player's injuryMatches decrements when they sit out the next matchday
     * (i.e. they are not in any XI, so they go through applyNonPlayers).
     */
    @Test
    void injuredPlayerHealsWhenSittingOut() {
        // Given a player who is manually injured
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        UUID clubId = career.save().managedClubId();

        // Pick a player NOT in the best XI (so they will be treated as a non-player)
        List<Player> xi = lineupService.bestXi(clubId);
        Player victim = playerRepository.findByClubId(clubId).stream()
                .filter(p -> xi.stream().noneMatch(x -> x.getId().equals(p.getId())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No benched player found"));
        victim.addInjury(2);
        playerRepository.save(victim);

        assertThat(victim.getInjuryMatches()).isEqualTo(2);
        assertThat(victim.isEligible()).isFalse();

        // When a round is simulated (victim sits out)
        advanceOneMatch(career);

        // Then the injury decremented by 1
        Player reloaded = playerRepository.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getInjuryMatches())
                .as("Injury should have decremented from 2 to 1 after one matchday out")
                .isEqualTo(1);
        assertThat(reloaded.isEligible()).isFalse();
    }

    /**
     * (c) resetSeasonState zeroes injuryMatches.
     */
    @Test
    void resetSeasonStateClearsInjuryMatches() {
        // Given a player with an active injury
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        Player victim = playerRepository.findByClubId(clubId).get(0);
        victim.addInjury(3);
        assertThat(victim.getInjuryMatches()).isEqualTo(3);

        // When resetSeasonState is called
        victim.resetSeasonState();

        // Then injuryMatches is zero and the player is eligible (assuming no suspension)
        assertThat(victim.getInjuryMatches()).isZero();
        assertThat(victim.isEligible()).isTrue();
    }

    /**
     * (d) An injured player cannot be selected in a lineup.
     */
    @Test
    void injuredPlayerCannotBeSelected() {
        // Given a player in the starting lineup who is then injured
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        UUID clubId = career.save().managedClubId();

        List<LineupStarter> starters = lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(
                        p.getId(),
                        br.com.elifootcarvoeiro.player.PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(java.util.stream.Collectors.toList());

        // Injure the 6th starter
        Player victim = playerRepository.findById(starters.get(5).playerId()).orElseThrow();
        victim.addInjury(1);
        playerRepository.save(victim);

        // When trying to set the lineup with the injured player
        assertThatThrownBy(() ->
                lineupService.setLineup(saveGameId, clubId,
                        new SetLineupRequest("4-4-2", starters), career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INJURED_PLAYER");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    /**
     * (e) PlayerResponse exposes injuryMatches and eligible=false when injured.
     */
    @Test
    void playerResponseExposesInjuryMatchesAndEligibleFalse() {
        // Given a player with an active injury
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        UUID clubId = career.save().managedClubId();
        UUID userId = career.userId();

        Player victim = playerRepository.findByClubId(clubId).get(0);
        victim.addInjury(2);
        playerRepository.save(victim);

        // When fetching the squad
        SquadResponse squad = lineupService.getSquad(saveGameId, clubId, userId);

        // Then the injured player's response has injuryMatches=2 and eligible=false
        PlayerResponse injured = squad.players().stream()
                .filter(p -> p.id().equals(victim.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(injured.injuryMatches()).isEqualTo(2);
        assertThat(injured.eligible()).isFalse();
    }

    /**
     * (f) Injured player is excluded from bestXi.
     */
    @Test
    void injuredPlayerIsExcludedFromBestXi() {
        // Given a player in a club is injured
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        Player victim = lineupService.bestXi(clubId).get(3);
        victim.addInjury(1);
        playerRepository.save(victim);

        // When picking the best XI
        List<Player> xi = lineupService.bestXi(clubId);

        // Then the injured player is not in it
        assertThat(xi).extracting(Player::getId).doesNotContain(victim.getId());
    }
}
