package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
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

    public ContractService(SaveGameService saveGameService,
                           PlayerRepository playerRepository,
                           ContractValuation contractValuation,
                           TransferWindow transferWindow,
                           StaffEffectsService staffEffectsService,
                           PreContractAgreementRepository preContractAgreementRepository) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.contractValuation = contractValuation;
        this.transferWindow = transferWindow;
        this.staffEffectsService = staffEffectsService;
        this.preContractAgreementRepository = preContractAgreementRepository;
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

        applyTerms(player, wage, contractYears, save.getManagedClubId());   // validates length/wage + acceptance
        player.setClubId(save.getManagedClubId()); // joins the squad; no fee, no ledger
        playerRepository.save(player);
        return toResponse(player);
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
