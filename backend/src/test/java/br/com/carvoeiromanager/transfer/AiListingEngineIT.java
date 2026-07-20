package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic — the engine is pure predicates over morale, appearances, club finances, and
 * squad shape (no RNG), so every scenario here asserts an exact outcome.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AiListingEngineIT {

    @Autowired AiListingEngine aiListingEngine;
    @Autowired TransferValuation transferValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID managedClubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("aile_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** GOI: a 22-player Série B club with 3 goalkeepers — comfortably clear of every floor. */
    private Club aDeepAiClub(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> "GOI".equals(club.getShortName())).findFirst().orElseThrow();
    }

    /** BRU with its squad replaced by exactly MIN_SQUAD_SIZE synthetic players (sits exactly on the floor). */
    private Club aFloorSizedAiClub(Career c) {
        Club club = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> "BRU".equals(cl.getShortName())).findFirst().orElseThrow();
        List<Player> squad = new ArrayList<>();
        for (int i = 0; i < TransferService.MIN_SQUAD_SIZE; i++) {
            squad.add(Player.create(c.id(), club.getId(), "P-" + UUID.randomUUID(), Position.ATT,
                    25, "Brasil", 60, 60, 60, 60, 60, 60, 60));
        }
        playerRepository.deleteAll(playerRepository.findByClubId(club.getId()));
        playerRepository.saveAll(squad);
        return club;
    }

    private List<Player> squad(Club club) {
        return playerRepository.findByClubIdAndYouthProspectFalse(club.getId());
    }

    private void makeHealthy(Club club) {
        club.setBalance(new BigDecimal("999999999.00"));
        clubRepository.save(club);
    }

    private void makeDesperate(Club club) {
        club.setBalance(BigDecimal.ZERO);
        clubRepository.save(club);
    }

    /**
     * createCareer already runs one day-one AiListingEngine pass (via processMarketDay), so a
     * freshly created club may already carry a listing or two. Scenarios that assert an exact
     * listed set start from a clean slate by clearing whatever that day-one pass produced.
     */
    private void clearListings(Club club) {
        List<Player> listed = squad(club).stream().filter(Player::isTransferListed).toList();
        listed.forEach(Player::unlist);
        playerRepository.saveAll(listed);
    }

    @Test
    void squadsAtOrBelowTheFloorAreNeverTouched() {
        Career c = createCareer();
        Club club = aFloorSizedAiClub(c);
        assertThat(squad(club)).hasSize(14); // exactly MIN_SQUAD_SIZE

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        assertThat(squad(club)).noneMatch(Player::isTransferListed);
    }

    @Test
    void playerRequestFiresWhenMoraleBottomsOut() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> squad = squad(club);
        Player best = squad.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        Player target = squad.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(best.getId()))
                .findFirst().orElseThrow();
        target.applyMorale(-10); // clamps to -5
        playerRepository.save(target);

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.isTransferListed()).isTrue();
        assertThat(reloaded.getListedReason()).isEqualTo(ListedReason.PLAYER_REQUEST);
    }

    @Test
    void unusedFiresOnlyFromRoundSixOnwardsForABarelyUsedPlayer() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> squad = squad(club);
        Player best = squad.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        Player target = squad.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(best.getId()))
                .findFirst().orElseThrow();
        assertThat(target.getSeasonAppearances()).isZero(); // never played

        // Before round 6: the guard blocks the trigger even though appearances are zero.
        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 5, LocalDate.of(2026, 1, 1));
        assertThat(playerRepository.findById(target.getId()).orElseThrow().isTransferListed()).isFalse();

        // At round 6: 0 * 3 < 6 -> fires.
        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 6, LocalDate.of(2026, 1, 1));
        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.isTransferListed()).isTrue();
        assertThat(reloaded.getListedReason()).isEqualTo(ListedReason.UNUSED);
    }

    @Test
    void unusedDoesNotFireForAPlayerWithEnoughAppearances() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> squad = squad(club);
        Player best = squad.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        Player target = squad.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(best.getId()))
                .findFirst().orElseThrow();
        target.recordAppearance(90, LocalDate.of(2026, 1, 20));
        target.recordAppearance(90, LocalDate.of(2026, 1, 27)); // 2 appearances: 2*3=6, not < 6
        playerRepository.save(target);

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 6, LocalDate.of(2026, 1, 1));

        assertThat(playerRepository.findById(target.getId()).orElseThrow().isTransferListed()).isFalse();
    }

    @Test
    void financialListsUpToTwoHighestValuePlayersWhenDesperate() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeDesperate(club);
        clearListings(club);
        List<Player> squadBefore = squad(club);
        long gkCount = squadBefore.stream().filter(p -> p.getPosition() == Position.GK).count();

        List<UUID> expectedTop2 = squadBefore.stream()
                .filter(p -> !(p.getPosition() == Position.GK && gkCount <= 2)) // GK protection still applies
                .sorted(Comparator.comparing((Player p) -> transferValuation.marketValue(p.getOverall(), p.getAge()))
                        .reversed()
                        .thenComparing(Player::getId))
                .limit(2)
                .map(Player::getId)
                .toList();

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        List<Player> listed = squad(club).stream().filter(Player::isTransferListed).toList();
        assertThat(listed).hasSize(2);
        assertThat(listed).allMatch(p -> p.getListedReason() == ListedReason.FINANCIAL);
        assertThat(listed.stream().map(Player::getId).toList())
                .containsExactlyInAnyOrderElementsOf(expectedTop2);
    }

    @Test
    void surplusFloorListsTheWeakestNonGkWhenNothingElseFiredAndSquadIsDeep() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club); // rules out FINANCIAL
        clearListings(club);
        List<Player> squadBefore = squad(club);
        assertThat(squadBefore.size()).isGreaterThanOrEqualTo(17);
        Player best = squadBefore.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        Player expectedWeakest = squadBefore.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(best.getId()))
                .min(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                .orElseThrow();

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1)); // round 0: no PLAYER_REQUEST/UNUSED

        List<Player> listed = squad(club).stream().filter(Player::isTransferListed).toList();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getId()).isEqualTo(expectedWeakest.getId());
        assertThat(listed.get(0).getListedReason()).isEqualTo(ListedReason.SURPLUS);
    }

    @Test
    void activeListingsAreCappedAtFourPerClub() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> squad = squad(club);
        Player best = squad.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        List<Player> candidates = squad.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(best.getId()))
                .sorted(Comparator.comparing(Player::getId))
                .limit(6)
                .toList();
        assertThat(candidates).hasSize(6);
        candidates.forEach(p -> p.applyMorale(-10));
        playerRepository.saveAll(candidates);

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        List<Player> listed = squad(club).stream().filter(Player::isTransferListed).toList();
        assertThat(listed).hasSize(4); // capped, even though 6 players qualified
        List<UUID> expectedListed = candidates.stream().map(Player::getId).sorted().limit(4).toList();
        assertThat(listed.stream().map(Player::getId).sorted().toList()).isEqualTo(expectedListed);
    }

    @Test
    void aGoalkeeperIsNeverListedWhenTheClubHasOnlyTwoGoalkeepers() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> gks = squad(club).stream().filter(p -> p.getPosition() == Position.GK).toList();
        assertThat(gks.size()).isGreaterThanOrEqualTo(3);
        // Trim to exactly two goalkeepers.
        playerRepository.deleteAll(gks.subList(2, gks.size()));
        Player remainingGk = playerRepository.findByClubIdAndYouthProspectFalse(club.getId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        remainingGk.applyMorale(-10); // would otherwise trigger PLAYER_REQUEST
        playerRepository.save(remainingGk);

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        assertThat(playerRepository.findById(remainingGk.getId()).orElseThrow().isTransferListed()).isFalse();
    }

    @Test
    void theClubsBestPlayerIsNeverListedUnlessTheClubIsDesperate() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        makeHealthy(club);
        clearListings(club);
        List<Player> squad = squad(club);
        Player best = squad.stream().max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        best.applyMorale(-10); // would otherwise trigger PLAYER_REQUEST
        playerRepository.save(best);

        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));
        assertThat(playerRepository.findById(best.getId()).orElseThrow().isTransferListed()).isFalse();

        makeDesperate(club);
        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));
        assertThat(playerRepository.findById(best.getId()).orElseThrow().isTransferListed()).isTrue();
    }

    @Test
    void reRunningWithNoStateChangeProducesNoFurtherChanges() {
        Career c = createCareer();
        Club club = aDeepAiClub(c);
        // createCareer's day-one market pass is seed-dependent (it can even sell off the club's
        // only listing), so it isn't a valid "no state change" baseline. Establish a controlled
        // baseline instead: force the club healthy, clear every listing, then run the engine once
        // and capture that result as `before`.
        makeHealthy(club);
        clearListings(club);
        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));
        List<UUID> before = squad(club).stream().filter(Player::isTransferListed)
                .map(Player::getId).sorted().toList();

        // Re-run with nothing mutated in between: the true idempotency claim.
        aiListingEngine.refreshListings(c.id(), c.managedClubId(), 0, LocalDate.of(2026, 1, 1));

        List<UUID> after = squad(club).stream().filter(Player::isTransferListed)
                .map(Player::getId).sorted().toList();
        assertThat(after).isEqualTo(before);
    }
}
