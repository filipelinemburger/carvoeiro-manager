package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadDeparture;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.finance.FinanceTransaction;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionType;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.transfer.TransferWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Contract operations the manager controls: renewing an owned player and signing
 * free agents. Acceptance is a pure wage threshold (ContractValuation); no RNG.
 */
@Service
public class ContractService {

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ContractValuation contractValuation;
    private final TransferWindow transferWindow;
    private final StaffEffectsService staffEffectsService;
    private final PreContractAgreementRepository preContractAgreementRepository;
    private final ClubRepository clubRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final SquadDeparture squadDeparture;

    public ContractService(SaveGameService saveGameService,
                           PlayerRepository playerRepository,
                           ContractValuation contractValuation,
                           TransferWindow transferWindow,
                           StaffEffectsService staffEffectsService,
                           PreContractAgreementRepository preContractAgreementRepository,
                           ClubRepository clubRepository,
                           FinanceTransactionRepository financeTransactionRepository,
                           SquadDeparture squadDeparture) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.contractValuation = contractValuation;
        this.transferWindow = transferWindow;
        this.staffEffectsService = staffEffectsService;
        this.preContractAgreementRepository = preContractAgreementRepository;
        this.clubRepository = clubRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.squadDeparture = squadDeparture;
    }

    @Transactional
    public PlayerContractResponse renewContract(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
        interceptPreContractDefense(saveGameId, save.getManagedClubId(), player, wage);
        applyTerms(player, wage, contractYears, save.getManagedClubId());
        playerRepository.save(player);
        return toResponse(player);
    }

    public List<FreeAgentResponse> getFreeAgents(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        BigDecimal scoutMult = BigDecimal.valueOf(
                staffEffectsService.wageDemandMultiplierFor(save.getManagedClubId(), true));
        return playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).stream()
                .map(p -> new FreeAgentResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())
                                .multiply(scoutMult).setScale(2, java.math.RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(FreeAgentResponse::overall).reversed())
                .toList();
    }

    @Transactional
    public PlayerContractResponse signFreeAgent(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireWindowOpen(save);
        requireSquadRoom(save.getManagedClubId());

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(Player::isFreeAgent)
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Free agent not found"));

        if (save.getManagedClubId().equals(player.getReleasedByClubId())) {
            throw new ApiException(ErrorCode.PLAYER_RECENTLY_RELEASED,
                    "You released this player; he cannot return until next season");
        }

        applyTerms(player, wage, contractYears, save.getManagedClubId());   // validates length/wage + acceptance
        player.setClubId(save.getManagedClubId()); // joins the squad; no fee, no ledger
        playerRepository.save(player);
        return toResponse(player);
    }

    /**
     * Tears up a player's contract: the club buys out half of what it still owes, the player
     * leaves as a free agent, and the dressing room takes it badly. Unlike signing, this is
     * allowed with the transfer window shut — a termination is not a transfer, and a bloated
     * squad in mid-season is exactly when it is needed.
     */
    @Transactional
    public TerminationResponse terminateContract(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .filter(p -> !p.isYouthProspect())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player not found in your professional squad"));

        squadDeparture.requireNoLiveMatch(saveGameId);
        List<Player> squad = squadDeparture.seniorSquad(managedClubId);
        squadDeparture.requireDepartureAllowed(squad, player);

        BigDecimal fee = contractValuation.terminationFee(
                player.getOverall(), player.getAge(), player.getContractYears());
        Club club = clubRepository.findById(managedClubId).orElseThrow();
        if (club.getBalance().compareTo(fee) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "The club cannot afford the termination fee of " + fee);
        }

        club.adjustBalance(fee.negate());
        clubRepository.save(club);
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGameId, managedClubId, save.getSeasonYear(), save.getCurrentRound(),
                FinanceTransactionType.TERMINATION, fee.negate(), "Rescisão: " + player.getName()));

        // The dressing room reads a release as a threat, so everyone still there takes the hit.
        List<Player> remaining = squad.stream()
                .filter(p -> !p.getId().equals(player.getId()))
                .toList();
        remaining.forEach(p -> p.applyMorale(-1));
        playerRepository.saveAll(remaining);

        player.releaseFrom(managedClubId);
        playerRepository.save(player);
        squadDeparture.cleanManagedLineup(saveGameId, managedClubId, player.getId());
        squadDeparture.rejectOpenOffersFor(saveGameId, player.getId());

        return new TerminationResponse(player.getId(), player.getName(), fee, club.getBalance());
    }

    private void requireWindowOpen(SaveGame save) {
        if (!transferWindow.isOpen(save.getCurrentDate())) {
            throw new ApiException(ErrorCode.TRANSFER_WINDOW_CLOSED, "The transfer window is closed");
        }
    }

    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }

    // ---- shared helpers (signFreeAgent in Task 8 reuses applyTerms + validation) ----

    void applyTerms(Player player, BigDecimal wage, int contractYears, UUID managedClubId) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
        BigDecimal scoutMult = BigDecimal.valueOf(
                staffEffectsService.wageDemandMultiplierFor(managedClubId, true));
        BigDecimal demand = contractValuation.wageDemand(player.getOverall(), player.getAge())
                .multiply(scoutMult).setScale(2, java.math.RoundingMode.HALF_UP);
        if (wage.compareTo(demand) < 0) {
            throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects these terms; demands at least " + demand);
        }
        player.setWage(wage);
        player.setContractYears(contractYears);
    }

    /**
     * A renewal doubles as the defense against a poacher's pre-contract: while the deal
     * is AGREED the player stays only for at least DEFENSE_FACTOR x the agreed wage
     * (cancelling the deal); once LOCKED the departure is binding and renewal is refused.
     */
    private void interceptPreContractDefense(UUID saveGameId, UUID managedClubId,
                                             Player player, BigDecimal wage) {
        List<PreContractAgreement> threats = preContractAgreementRepository
                .findBySaveGameIdAndPlayerIdAndStatusIn(saveGameId, player.getId(),
                        PreContractService.ACTIVE).stream()
                .filter(a -> a.getFromClubId().equals(managedClubId))
                .toList();
        for (PreContractAgreement threat : threats) {
            if (threat.getStatus() == PreContractStatus.LOCKED) {
                throw new ApiException(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT,
                        "The pre-contract is locked; the player will leave when his contract ends");
            }
            BigDecimal required = threat.getWage()
                    .multiply(PreContractNegotiation.DEFENSE_FACTOR)
                    .setScale(2, RoundingMode.CEILING);
            if (wage == null || wage.compareTo(required) < 0) {
                throw new ApiException(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT,
                        "Player has agreed a pre-contract elsewhere; staying costs at least " + required);
            }
            threat.cancel();
            preContractAgreementRepository.save(threat);
        }
    }

    Player requireManagedPlayer(UUID saveGameId, UUID managedClubId, UUID playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found at your club"));
    }

    PlayerContractResponse toResponse(Player p) {
        return new PlayerContractResponse(p.getId(), p.getName(), p.getWage(), p.getContractYears());
    }
}
