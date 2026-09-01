package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.finance.FinanceTransaction;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionType;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.match.LineupResponse;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ContractServiceTerminateIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired ContractService contractService;
    @Autowired ContractValuation contractValuation;
    @Autowired FinanceTransactionRepository financeTransactionRepository;
    @Autowired br.com.carvoeiromanager.match.LineupService lineupService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("terminate_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<Player> squad(Career c) {
        return playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
    }

    /** An outfielder, chosen by lowest id so the pick is stable across seed changes. */
    private Player anOutfielder(Career c) {
        return squad(c).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId))
                .orElseThrow();
    }

    /** Gives the club far more money than any fee, so solvency never confounds a test. */
    private void makeRich(Career c) {
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        club.adjustBalance(new BigDecimal("1000000000").subtract(club.getBalance()));
        clubRepository.save(club);
    }

    @Test
    void terminatingReleasesThePlayerAndChargesTheFee() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        BigDecimal expectedFee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        TerminationResponse r = contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(r.playerName()).isEqualTo(p.getName());
        assertThat(r.fee()).isEqualByComparingTo(expectedFee);
        assertThat(r.newBalance()).isEqualByComparingTo(balanceBefore.subtract(expectedFee));

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(reloaded.getContractYears()).isZero();
        assertThat(reloaded.getWage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reloaded.getReleasedByClubId()).isEqualTo(c.clubId());

        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore.subtract(expectedFee));
    }

    @Test
    void theFeeIsRecordedAsATerminationInTheLedger() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        BigDecimal expectedFee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .filteredOn(t -> t.getType() == FinanceTransactionType.TERMINATION)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getAmount()).isEqualByComparingTo(expectedFee.negate());
                    assertThat(t.getDescription()).isEqualTo("Rescisão: " + p.getName());
                });
    }

    @Test
    void theRestOfTheSquadLosesMoraleButNotTheReleasedPlayer() {
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        List<UUID> othersAtZero = squad(c).stream()
                .filter(o -> !o.getId().equals(p.getId()))
                .filter(o -> o.getMorale() == 0)
                .map(Player::getId).toList();
        assertThat(othersAtZero).isNotEmpty();

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        for (UUID id : othersAtZero) {
            assertThat(playerRepository.findById(id).orElseThrow().getMorale())
                    .as("morale of squad member %s", id)
                    .isEqualTo(-1);
        }
    }

    @Test
    void aSquadAtTheFloorCannotShrinkFurther() {
        Career c = createCareer();
        makeRich(c);
        List<Player> squad = squad(c);
        Player p = anOutfielder(c);
        // Trim to exactly the floor, keeping the probe and every goalkeeper.
        List<Player> expendable = squad.stream()
                .filter(o -> !o.getId().equals(p.getId()))
                .filter(o -> o.getPosition() != Position.GK)
                .toList();
        int mustDelete = squad.size() - SquadRules.MIN_SQUAD_SIZE;
        playerRepository.deleteAll(expendable.subList(0, mustDelete));
        playerRepository.flush();
        assertThat(squad(c)).hasSize(SquadRules.MIN_SQUAD_SIZE);

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), p.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isFalse();
    }

    @Test
    void theLastGoalkeeperCannotBeReleased() {
        Career c = createCareer();
        makeRich(c);
        List<Player> keepers = squad(c).stream()
                .filter(o -> o.getPosition() == Position.GK)
                .sorted(Comparator.comparing(Player::getId))
                .toList();
        Player lastKeeper = keepers.get(0);
        playerRepository.deleteAll(keepers.subList(1, keepers.size()));
        playerRepository.flush();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), lastKeeper.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.SQUAD_TOO_SMALL));

        assertThat(playerRepository.findById(lastKeeper.getId()).orElseThrow().isFreeAgent()).isFalse();
    }

    @Test
    void aClubThatCannotAffordTheFeeCannotTerminate() {
        Career c = createCareer();
        Player p = anOutfielder(c);
        BigDecimal fee =
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears());
        Club club = clubRepository.findById(c.clubId()).orElseThrow();
        club.adjustBalance(fee.subtract(new BigDecimal("0.01")).subtract(club.getBalance()));
        clubRepository.save(club);
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), p.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isFalse();
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
    }

    @Test
    void anotherClubsPlayerCannotBeTerminated() {
        Career c = createCareer();
        makeRich(c);
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(o -> o.getClubId() != null && !o.getClubId().equals(c.clubId()))
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), rival.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void aYouthProspectCannotBeTerminated() {
        // The seed squad has no youth prospects (they only appear via the newgen generator at
        // season rollover), so this test creates one directly, the same way YouthExclusionIT does.
        Career c = createCareer();
        makeRich(c);
        Player prospect = Player.create(c.id(), c.clubId(), "Jóia da Base", Position.ATT, 16,
                "Brasil", 60, 60, 55, 40, 10, 60, 58);
        prospect.markAsProspect(88);
        playerRepository.save(prospect);

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), prospect.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    }

    @Test
    void aReleasedStarterLeavesTheLineup() {
        Career c = createCareer();
        makeRich(c);
        List<Player> bestXi = lineupService.bestXi(c.clubId());
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(),
                        RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(c.clubId()).stream()
                .filter(o -> !xiIds.contains(o.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(o -> new LineupStarter(o.getId(),
                        RoleArchetype.defaultFor(o.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(),
                new SetLineupRequest("4-4-2", starters, bench), c.userId());

        Player starter = bestXi.stream()
                .filter(o -> o.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        contractService.terminateContract(c.id(), c.userId(), starter.getId());

        LineupResponse lineup = lineupService.getLineup(c.id(), c.clubId(), c.userId()).orElseThrow();
        assertThat(lineup.starters()).extracting(LineupStarter::playerId)
                .doesNotContain(starter.getId());
        assertThat(lineup.bench()).extracting(LineupStarter::playerId)
                .doesNotContain(starter.getId());
    }

    @Test
    void terminatingWorksWhileTheTransferWindowIsShut() {
        // Deliberate design rule: termination is not a transfer. A career starts inside the
        // pre-season window (currentDate == seasonStart), so that window is open at creation
        // time and would not exercise the rule. Walk the save's date past the pre-season window
        // and before the mid-season one (May 1 is used the same way in
        // ContractServiceSignIT#signingIsBlockedWhenTheWindowIsClosed) and confirm the operation
        // still succeeds — the guard that signFreeAgent applies must NOT be copied here.
        Career c = createCareer();
        makeRich(c);
        Player p = anOutfielder(c);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(save.getSeasonYear(), 5, 1));
        saveGameRepository.save(save);

        contractService.terminateContract(c.id(), c.userId(), p.getId());

        assertThat(playerRepository.findById(p.getId()).orElseThrow().isFreeAgent()).isTrue();
    }
}
