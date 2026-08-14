package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.PhaseResolver;
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
     *
     * <p>For a phased competition this ordering carries a second guarantee. Round numbers run
     * consecutively across phases ({@code SeasonCalendarGenerator.phasedUnits} offsets each phase
     * by the rounds before it), so every round of a phase sorts ahead of every reserved round of
     * the phase after it — even when the proportional dating puts them on the same day. That is
     * what lets {@link br.com.carvoeiromanager.competition.PhaseResolver} materialize the next
     * phase's fixtures in time for the loop below to find them.
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
    private final PhaseResolver phaseResolver;

    public BackgroundCompetitionService(SeasonEventRepository seasonEventRepository,
                                        CompetitionClubRepository competitionClubRepository,
                                        FixtureRepository fixtureRepository,
                                        FixtureSimulator fixtureSimulator,
                                        FinanceService financeService,
                                        PhaseResolver phaseResolver) {
        this.seasonEventRepository = seasonEventRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureSimulator = fixtureSimulator;
        this.financeService = financeService;
        this.phaseResolver = phaseResolver;
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
            // simulateBackgroundRound, not simulateRound: the latter also runs the save-wide
            // didn't-play sweep, which would tick the managed club's injuries and suspensions
            // once more for every background round resolved on the same advance.
            fixtureSimulator.simulateBackgroundRound(save, event.getCompetitionId(), fixtures);
            // Background clubs take matchday finance like anyone else; without it their balances
            // would drift from the divisions the player can see.
            financeService.processMatchday(saveGameId, event.getCompetitionId(), fixtures,
                    event.getEventDate());
            event.markProcessed();
            seasonEventRepository.save(event);
            // A background competition can be phased too (Série C: a round-robin first phase and
            // three knockout phases the player is never in). Its knockout rounds are reserved —
            // dated, fixture-less — at generation, and only PhaseResolver ever fills them in.
            // Without this call the reserved rounds are marked processed with zero fixtures and
            // the bracket never happens: no semifinalists, no champion, nothing for promotion to
            // read. The resolver keys off the event's own competition and phase and does not care
            // whether the competition is managed, so this is the same call
            // SeasonAdvanceService.recordMatchRound makes for the player's own rounds.
            //
            // Safe to call after markProcessed(): PhaseResolver.phaseComplete accepts an event
            // that is EITHER processed OR the one just handled, so both orders agree.
            //
            // The newly materialized fixtures are visible to the next iteration's
            // findBySaveGameIdAndCompetitionIdAndRound because that is a derived (JPQL) query and
            // Hibernate auto-flushes the pending inserts before running it — and `due` is ordered
            // (date, competition, round), which puts every round of a phase ahead of the reserved
            // rounds of the phase after it, so materialization always precedes the round that
            // needs it.
            phaseResolver.resolveIfPhaseComplete(save, event);
        }
        if (!due.isEmpty()) {
            log.info("Resolved {} background round(s) through {}", due.size(), throughDate);
        }
        return due.size();
    }
}
