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

    public ContractService(SaveGameService saveGameService,
                           PlayerRepository playerRepository,
                           ContractValuation contractValuation,
                           TransferWindow transferWindow,
                           StaffEffectsService staffEffectsService) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.contractValuation = contractValuation;
        this.transferWindow = transferWindow;
        this.staffEffectsService = staffEffectsService;
    }

    @Transactional
    public PlayerContractResponse renewContract(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
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
