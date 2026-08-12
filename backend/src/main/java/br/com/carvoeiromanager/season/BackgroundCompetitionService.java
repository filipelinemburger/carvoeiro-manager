package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.finance.FinanceService;
import br.com.carvoeiromanager.match.FixtureSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Simulates competitions the managed club does not play in, catching them up to the player's
 * in-game date after every advance. Their results are never surfaced through the advance response
 * — the player sees them only in that competition's table.
 */
@Service
public class BackgroundCompetitionService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundCompetitionService.class);

    /**
     * Resolution order for due rounds. Date alone is not enough: background rounds are dated
     * proportionally across the managed season's window, so a competition with more rounds than the
     * window has days legitimately puts two of its rounds on one date. Grouping by competition and
     * then by round is what stops round N+1 being simulated before round N — which would seed the
     * later round from a table that has not yet been updated by the earlier one. Sequence is
     * useless as a tiebreak here: every background MATCH event carries the same constant
     * ({@link SeasonCalendarGenerator#BACKGROUND_MATCH_SEQUENCE}).
     */
    private static final Comparator<SeasonEvent> BY_DATE_COMPETITION_ROUND =
            Comparator.comparing(SeasonEvent::getEventDate)
                    .thenComparing(SeasonEvent::getCompetitionId)
                    .thenComparing(SeasonEvent::getRound);

    private final SeasonEventRepository seasonEventRepository;
    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureSimulator fixtureSimulator;
    private final FinanceService financeService;

    public BackgroundCompetitionService(SeasonEventRepository seasonEventRepository,
                                        CompetitionClubRepository competitionClubRepository,
                                        FixtureRepository fixtureRepository,
                                        FixtureSimulator fixtureSimulator,
                                        FinanceService financeService) {
        this.seasonEventRepository = seasonEventRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
    }

    /**
     * True when the managed club has a membership row in this competition. This — never a stored
     * flag — is what makes a competition "managed", so the split stays correct when a club moves
     * between divisions.
     */
    public boolean isManaged(UUID competitionId, UUID managedClubId) {
        if (competitionId == null || managedClubId == null) {
            return false;
        }
        return competitionClubRepository.findByCompetitionId(competitionId).stream()
                .map(CompetitionClub::getClubId)
                .anyMatch(managedClubId::equals);
    }

    /**
     * Simulates every unprocessed background round dated on or before {@code throughDate}, in
     * (date, competition, round) order. Returns how many rounds were resolved; zero on a repeat
     * call, which is what makes it safe to call after every advance.
     */
    @Transactional
    public int resolveDue(SaveGame save, LocalDate throughDate) {
        final UUID saveGameId = save.getId();
        final UUID managedClubId = save.getManagedClubId();

        List<SeasonEvent> due = seasonEventRepository.findBySaveGameIdAndProcessedFalse(saveGameId)
                .stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> !e.getEventDate().isAfter(throughDate))
                .filter(e -> !isManaged(e.getCompetitionId(), managedClubId))
                .sorted(BY_DATE_COMPETITION_ROUND)
                .toList();

        for (SeasonEvent event : due) {
            List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                    saveGameId, event.getCompetitionId(), event.getRound());
            fixtureSimulator.simulateRound(save, event.getCompetitionId(), fixtures);
            // Background clubs take matchday finance like anyone else; without it their balances
            // would drift from the divisions the player can see.
            financeService.processMatchday(saveGameId, event.getCompetitionId(), fixtures,
                    event.getEventDate());
            event.markProcessed();
            seasonEventRepository.save(event);
        }
        if (!due.isEmpty()) {
            log.info("Resolved {} background round(s) through {}", due.size(), throughDate);
        }
        return due.size();
    }
}
