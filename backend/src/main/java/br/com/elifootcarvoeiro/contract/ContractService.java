package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.transfer.TransferWindow;
import org.springframework.http.HttpStatus;
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

    private static final int MAX_SQUAD_SIZE = 30;

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ContractValuation contractValuation;
    private final TransferWindow transferWindow;
    private final CompetitionRepository competitionRepository;

    public ContractService(SaveGameService saveGameService,
                           PlayerRepository playerRepository,
                           ContractValuation contractValuation,
                           TransferWindow transferWindow,
                           CompetitionRepository competitionRepository) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.contractValuation = contractValuation;
        this.transferWindow = transferWindow;
        this.competitionRepository = competitionRepository;
    }

    @Transactional
    public PlayerContractResponse renewContract(UUID saveGameId, UUID userId, UUID playerId,
                                                BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedPlayer(saveGameId, save.getManagedClubId(), playerId);
        applyTerms(player, wage, contractYears);
        playerRepository.save(player);
        return toResponse(player);
    }

    public List<FreeAgentResponse> getFreeAgents(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return playerRepository.findBySaveGameIdAndClubIdIsNull(saveGameId).stream()
                .map(p -> new FreeAgentResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge())))
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
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Free agent not found", HttpStatus.NOT_FOUND));

        applyTerms(player, wage, contractYears);   // validates length/wage + acceptance
        player.setClubId(save.getManagedClubId()); // joins the squad; no fee, no ledger
        playerRepository.save(player);
        return toResponse(player);
    }

    private void requireWindowOpen(SaveGame save) {
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).get(0);
        if (!transferWindow.isOpen(save.getCurrentRound(), competition.getTotalRounds())) {
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
            throw new ApiException("SQUAD_FULL", "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    // ---- shared helpers (signFreeAgent in Task 8 reuses applyTerms + validation) ----

    void applyTerms(Player player, BigDecimal wage, int contractYears) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException("INVALID_CONTRACT",
                    "Contract length must be 1-5 years and wage must be positive",
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        if (!contractValuation.acceptsWage(player.getOverall(), player.getAge(), wage)) {
            BigDecimal demand = contractValuation.wageDemand(player.getOverall(), player.getAge());
            throw new ApiException("PLAYER_REJECTS_TERMS",
                    "Player rejects these terms; demands at least " + demand,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        player.setWage(wage);
        player.setContractYears(contractYears);
    }

    Player requireManagedPlayer(UUID saveGameId, UUID managedClubId, UUID playerId) {
        return playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .orElseThrow(() -> new ApiException("PLAYER_NOT_FOUND",
                        "Player not found at your club", HttpStatus.NOT_FOUND));
    }

    PlayerContractResponse toResponse(Player p) {
        return new PlayerContractResponse(p.getId(), p.getName(), p.getWage(), p.getContractYears());
    }
}
