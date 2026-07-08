package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.YouthDevelopmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Manages the managed club's youth academy: listing prospects, promoting them, and releasing them. */
@Service
public class YouthAcademyService {

    private static final int PROMOTION_CONTRACT_YEARS = 3;

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final YouthDevelopmentService youthDevelopmentService;
    private final FinanceCalculator financeCalculator;

    public YouthAcademyService(SaveGameService saveGameService, PlayerRepository playerRepository,
                               YouthDevelopmentService youthDevelopmentService,
                               FinanceCalculator financeCalculator) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.youthDevelopmentService = youthDevelopmentService;
        this.financeCalculator = financeCalculator;
    }

    @Transactional(readOnly = true)
    public List<YouthProspectResponse> listProspects(UUID saveGameId, UUID userId) {
        var save = saveGameService.requireOwned(saveGameId, userId);
        return playerRepository.findByClubIdAndYouthProspectTrue(save.getManagedClubId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public YouthProspectResponse promote(UUID saveGameId, UUID prospectId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Player p = requireProspect(saveGameId, prospectId);
        p.promoteFromYouth();
        p.setContractYears(PROMOTION_CONTRACT_YEARS);
        p.setWage(financeCalculator.weeklyWage(p.getOverall(), p.getAge()));
        playerRepository.save(p);
        return toResponse(p);
    }

    @Transactional
    public void release(UUID saveGameId, UUID prospectId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Player p = requireProspect(saveGameId, prospectId);
        playerRepository.delete(p);
    }

    private Player requireProspect(UUID saveGameId, UUID prospectId) {
        Player p = playerRepository.findById(prospectId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROSPECT_NOT_FOUND, "Youth prospect not found"));
        if (!p.getSaveGameId().equals(saveGameId) || !p.isYouthProspect()) {
            throw new ApiException(ErrorCode.PROSPECT_NOT_FOUND, "Youth prospect not found");
        }
        return p;
    }

    private YouthProspectResponse toResponse(Player p) {
        return new YouthProspectResponse(p.getId(), p.getName(), p.getPosition().name(), p.getAge(),
                p.getOverall(), p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina(), youthDevelopmentService.potentialStars(p.getPotential()));
    }
}
