package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanServiceInIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired LoanService loanService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanin_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /** A spare player at an AI club: outside its top 11, with cover in his position. */
    private Player aSparePlayerAt(UUID clubId) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        Player spare = squad.stream().skip(11)
                .filter(p -> p.getPosition() != Position.GK)
                .filter(p -> squad.stream().anyMatch(o -> !o.getId().equals(p.getId())
                        && o.getPosition() == p.getPosition()
                        && o.getOverall() >= p.getOverall() - 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no spare outfielder with cover at " + clubId));
        spare.setContractYears(3);
        playerRepository.save(spare);
        return playerRepository.findById(spare.getId()).orElseThrow();
    }

    /** Drops the managed club's reputation so the borrowed player clears its level test. */
    private void makeManagedClubModest(Career c, Player target) {
        Club managed = clubRepository.findById(c.clubId()).orElseThrow();
        managed.setReputation(Math.max(1, target.getOverall() - 10));
        clubRepository.save(managed);
    }

    /** A club distinct from the managed club and from {@code excludeClubId} — a second AI club. */
    private UUID aThirdClubId(Career c, UUID excludeClubId) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()) && !id.equals(excludeClubId))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    @Test
    void borrowingASparePlayerMovesHimToTheManagedClubUnderHisOwner() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        makeManagedClubModest(c, target);

        // Asking the owner to pay nothing is the offer an owner most readily accepts.
        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.ACCEPTED);
        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getOwnerClubId()).isEqualTo(ownerClubId);
        assertThat(reloaded.getLoanWageShare()).isZero();
    }

    @Test
    void anOwnerAskedToPayTooMuchCountersDownAndWithholdsTheFixture() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        makeManagedClubModest(c, target);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 100, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.COUNTERED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.TERMS_REJECTED);
        assertThat(r.counterOwnerWageShare()).isLessThan(100);
        assertThat(r.counterMayFaceOwner()).isFalse();
        assertThat(playerRepository.findById(target.getId()).orElseThrow().isOnLoan()).isFalse();
    }

    @Test
    void aClubWillNotLendItsKeyPlayerToYou() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player best = playerRepository.findByClubIdAndYouthProspectFalse(ownerClubId).stream()
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        best.setContractYears(3);
        playerRepository.save(best);
        makeManagedClubModest(c, best);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), best.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.KEY_PLAYER);
    }

    @Test
    void aPlayerBeneathYourLevelIsNotWorthBorrowing() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        Club managed = clubRepository.findById(c.clubId()).orElseThrow();
        managed.setReputation(target.getOverall() + 20);
        clubRepository.save(managed);

        LoanOfferResponse r = loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true);

        assertThat(r.status()).isEqualTo(LoanOfferStatus.REJECTED);
        assertThat(r.refusal()).isEqualTo(LoanRefusal.BELOW_LEVEL);
    }

    @Test
    void aPlayerWithOneYearLeftCannotBeBorrowedEither() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        target.setContractYears(1);
        playerRepository.save(target);
        makeManagedClubModest(c, target);

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.LOAN_CONTRACT_TOO_SHORT));
    }

    @Test
    void yourOwnPlayerCannotBeBorrowedFromYourself() {
        Career c = createCareer();
        Player mine = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), mine.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void aYouthProspectCannotBeBorrowed() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player prospect = Player.create(c.id(), ownerClubId, "Joia da Base", Position.ATT, 16,
                "Brasil", 60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        prospect.setContractYears(3);
        playerRepository.save(prospect);
        makeManagedClubModest(c, prospect);   // otherwise borrowable — the youth flag is what refuses him

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), prospect.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

        Player reloaded = playerRepository.findById(prospect.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(ownerClubId);
        assertThat(reloaded.isOnLoan()).isFalse();
    }

    @Test
    void aFreeAgentCannotBeBorrowed() {
        Career c = createCareer();
        Player free = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), free.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

        assertThat(playerRepository.findById(free.getId()).orElseThrow().getClubId()).isNull();
    }

    @Test
    void aPlayerAlreadyOutOnLoanCannotBeBorrowedAgain() {
        Career c = createCareer();
        UUID ownerClubId = aRivalClubId(c);
        Player target = aSparePlayerAt(ownerClubId);
        makeManagedClubModest(c, target);   // otherwise borrowable — the loan flag is what refuses him
        UUID thirdClubId = aThirdClubId(c, ownerClubId);
        target.loanTo(thirdClubId, 0, false);
        playerRepository.save(target);

        assertThatThrownBy(() -> loanService.offerLoanIn(c.id(), c.userId(), target.getId(), 0, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(thirdClubId);
        assertThat(reloaded.getOwnerClubId()).isEqualTo(ownerClubId);
    }
}
