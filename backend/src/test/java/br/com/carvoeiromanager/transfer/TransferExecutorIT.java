package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionType;
import br.com.carvoeiromanager.match.Lineup;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.match.SetTacticsRequest;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferExecutorIT {

    @Autowired TransferExecutor executor;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupService lineupService;
    @Autowired LineupRepository lineupRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("exec_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anyOtherClub(UUID saveGameId, UUID managedClubId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .map(Club::getId).filter(id -> !id.equals(managedClubId)).findFirst().orElseThrow();
    }

    @Test
    void buyingMovesThePlayerAdjustsBalancesAndRecordsTransferIn() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        UUID seller = anyOtherClub(c.id(), c.clubId());
        Player target = playerRepository.findByClubId(seller).get(0);
        BigDecimal fee = new BigDecimal("1000000.00");
        BigDecimal buyerBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        BigDecimal sellerBefore = clubRepository.findById(seller).orElseThrow().getBalance();

        TransferOffer offer = TransferOffer.create(c.id(), target.getId(), c.clubId(), seller,
                TransferDirection.OUTGOING, fee, save.getCurrentRound(), null, null);

        executor.execute(save, offer, fee);

        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(buyerBefore.subtract(fee));
        assertThat(clubRepository.findById(seller).orElseThrow().getBalance())
                .isEqualByComparingTo(sellerBefore.add(fee));
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.TRANSFER_IN
                        && t.getAmount().signum() < 0);
    }

    @Test
    void sellingAManagedStarterRemovesThemFromTheLineupAndClearsTakerRefs() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        // Set a managed lineup and make the first starter a set-piece taker.
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());
        UUID soldId = xi.get(0).playerId();
        lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", null, null, "MEDIUM", "ZONAL", soldId, soldId, soldId), c.userId());
        UUID buyer = anyOtherClub(c.id(), c.clubId());
        BigDecimal fee = new BigDecimal("750000.00");

        TransferOffer offer = TransferOffer.create(c.id(), soldId, buyer, c.clubId(),
                TransferDirection.INCOMING, fee, save.getCurrentRound(), null, null);

        executor.execute(save, offer, fee);

        // Player moved to the buyer; managed TRANSFER_OUT recorded; lineup + taker refs cleared.
        assertThat(playerRepository.findById(soldId).orElseThrow().getClubId()).isEqualTo(buyer);
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .anyMatch(t -> t.getType() == FinanceTransactionType.TRANSFER_OUT
                        && t.getAmount().signum() > 0);
        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId()).orElseThrow();
        assertThat(lineup.getPenaltyTakerId()).isNull();
        assertThat(lineup.getFreekickTakerId()).isNull();
        assertThat(lineup.getCornerTakerId()).isNull();
    }
}
