package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.loan.LoanOfferRepository;
import br.com.carvoeiromanager.loan.LoanOfferStatus;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.match.AdvancePreviewLowFitness;
import br.com.carvoeiromanager.season.AdvancePreviewResponse.IneligibleStarterResponse;
import br.com.carvoeiromanager.season.AdvancePreviewResponse.LowFitnessStarterResponse;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Computes a non-mutating look-ahead at the next {@link #preview} advance so the UI can decide
 * whether to disable the "Avançar" button. See {@link AdvancePreviewResponse} for field semantics.
 */
@Service
public class AdvancePreviewService {

    private final SaveGameService saveGameService;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final LineupService lineupService;
    private final TransferWindow transferWindow;
    private final TransferOfferRepository transferOfferRepository;
    private final LoanOfferRepository loanOfferRepository;
    private final BackgroundCompetitionService backgroundCompetitionService;

    public AdvancePreviewService(SaveGameService saveGameService,
                                 SeasonEventRepository seasonEventRepository,
                                 FixtureRepository fixtureRepository,
                                 LineupService lineupService,
                                 TransferWindow transferWindow,
                                 TransferOfferRepository transferOfferRepository,
                                 LoanOfferRepository loanOfferRepository,
                                 BackgroundCompetitionService backgroundCompetitionService) {
        this.saveGameService = saveGameService;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.lineupService = lineupService;
        this.transferWindow = transferWindow;
        this.transferOfferRepository = transferOfferRepository;
        this.loanOfferRepository = loanOfferRepository;
        this.backgroundCompetitionService = backgroundCompetitionService;
    }

    @Transactional(readOnly = true)
    public AdvancePreviewResponse preview(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        LocalDate currentDate = save.getCurrentDate();

        // Scoped to the managed club: the preview announces what the NEXT advance will stop on,
        // and advance() itself only stops on events the player is present for.
        SeasonEvent next = seasonEventRepository.findNextManagedEvent(saveGameId, managedClubId);

        String nextEventType = (next == null) ? "FINISHED" : next.getType().name();

        boolean managedClubPlaysNext = false;
        List<IneligibleStarterResponse> ineligibleStarters = List.of();
        List<LowFitnessStarterResponse> lowFitnessStarters = List.of();
        if (next != null && next.getType() == SeasonEventType.MATCH) {
            List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                    saveGameId, next.getCompetitionId(), next.getRound());
            Fixture managedFixture = fixtures.stream()
                    .filter(f -> managedClubId.equals(f.getHomeClubId())
                            || managedClubId.equals(f.getAwayClubId()))
                    .findFirst()
                    .orElse(null);
            managedClubPlaysNext = managedFixture != null;
            if (managedClubPlaysNext) {
                // The opponent is what makes the loan fixture-release rule answerable: a borrowed
                // starter is barred only against the club that owns him.
                UUID opponentClubId = managedClubId.equals(managedFixture.getHomeClubId())
                        ? managedFixture.getAwayClubId()
                        : managedFixture.getHomeClubId();
                ineligibleStarters = lineupService
                        .savedIneligibleStarters(saveGameId, managedClubId, opponentClubId).stream()
                        .map(p -> toIneligibleStarter(p, lineupService.isLoanBarred(p, opponentClubId)))
                        .toList();
                lowFitnessStarters = lineupService.lowFitnessStarters(saveGameId, managedClubId, currentDate, 50).stream()
                        .map(r -> new LowFitnessStarterResponse(r.playerId(), r.playerName(), r.fitness()))
                        .toList();
            }
        }

        // The window "closes on this advance" when it is open now but the next event lands outside it.
        // currentDate is normally always set, but guard defensively (it is nullable on the entity).
        boolean windowClosingAfterAdvance = next != null
                && currentDate != null
                && transferWindow.isOpen(currentDate)
                && !transferWindow.isOpen(next.getEventDate());

        // Loan offers die the same way transfer offers do when the window closes (Task 3), and a
        // countered loan is just as durable/visible as a countered transfer (Task 6) — so both
        // must count toward the "resolve your pending proposals" gate, not just transfers.
        int pendingTransfers = transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size()
                + loanOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                List.of(LoanOfferStatus.PENDING, LoanOfferStatus.COUNTERED)).size();

        UUID defaultStandingsCompetitionId = defaultStandingsCompetition(saveGameId, managedClubId);

        return new AdvancePreviewResponse(
                nextEventType,
                managedClubPlaysNext,
                ineligibleStarters,
                windowClosingAfterAdvance,
                pendingTransfers,
                defaultStandingsCompetitionId == null ? null : defaultStandingsCompetitionId.toString(),
                lowFitnessStarters);
    }

    /**
     * {@code loanBarred} is decided by the caller, which holds the opponent: a player can be
     * perfectly fit and unsuspended and still unable to play this particular fixture.
     */
    private static IneligibleStarterResponse toIneligibleStarter(Player p, boolean loanBarred) {
        String reason;
        if (p.getSuspensionRemaining() > 0) {
            reason = "SUSPENDED";
        } else if (loanBarred) {
            reason = "LOAN_BARRED";
        } else {
            reason = "INJURED";
        }
        return new IneligibleStarterResponse(p.getId().toString(), p.getName(), reason);
    }

    /**
     * The competition the Classificação screen should default to: the most recently <em>played</em>
     * match's competition, or — if no match has been played yet — the next scheduled match's
     * competition. Null only when the save has no MATCH events at all.
     *
     * <p>Scoped to competitions the managed club is actually in. The screen answers "how did MY
     * last match leave the table", and the player is never present for a background round: the
     * whole background world is drained silently inside the same advance. Unscoped, the
     * {@code (date, sequence)} tiebreak actively worked against that — a background event carries
     * {@code BACKGROUND_MATCH_SEQUENCE = 2} against the managed {@code 0}, so on every shared date
     * the background competition <em>won</em> and the player was sent to a division they do not
     * play in. Proportional dating makes such collisions structural, not incidental.
     *
     * <p>Membership via {@link BackgroundCompetitionService#isManaged} rather than a second
     * membership query here — the same predicate the calendar, the advance loop and rollover all
     * use, so "managed" cannot come to mean two different things.
     */
    private UUID defaultStandingsCompetition(UUID saveGameId, UUID managedClubId) {
        List<SeasonEvent> allMatchEvents = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .toList();
        // isManaged is a query, and a season has hundreds of MATCH events over a handful of
        // competitions — resolve membership once per competition, not once per event.
        Set<UUID> managedCompetitionIds = allMatchEvents.stream()
                .map(SeasonEvent::getCompetitionId)
                .distinct()
                .filter(id -> backgroundCompetitionService.isManaged(id, managedClubId))
                .collect(Collectors.toSet());
        List<SeasonEvent> matchEvents = allMatchEvents.stream()
                .filter(e -> managedCompetitionIds.contains(e.getCompetitionId()))
                .toList();

        Comparator<SeasonEvent> byDateThenSeq =
                Comparator.comparing(SeasonEvent::getEventDate).thenComparingInt(SeasonEvent::getSequence);

        Optional<SeasonEvent> lastPlayed = matchEvents.stream()
                .filter(SeasonEvent::isProcessed)
                .max(byDateThenSeq);
        if (lastPlayed.isPresent()) {
            return lastPlayed.get().getCompetitionId();
        }

        return matchEvents.stream()
                .filter(e -> !e.isProcessed())
                .min(byDateThenSeq)
                .map(SeasonEvent::getCompetitionId)
                .orElse(null);
    }
}
