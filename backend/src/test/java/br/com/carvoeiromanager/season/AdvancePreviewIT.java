package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.loan.LoanOffer;
import br.com.carvoeiromanager.loan.LoanOfferRepository;
import br.com.carvoeiromanager.match.LineupResponse;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.TransferDirection;
import br.com.carvoeiromanager.transfer.TransferOffer;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferWindow;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the advance preview, which drives the UI's decision to enable or
 * disable the "Avançar" button (ineligible starter before a match, transfer window about to
 * close with pending bids) and the Classificação default competition.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AdvancePreviewIT {

    @Autowired AdvancePreviewService advancePreviewService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired LoanOfferRepository loanOfferRepository;
    @Autowired TransferWindow transferWindow;
    @Autowired BackgroundCompetitionService backgroundCompetitionService;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("preview_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private void setBestEligibleLineup(Career c) {
        List<LineupStarter> starters = lineupService.bestXi(c.clubId(), null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters, null), c.userId());
    }

    /** Advances (processing one event each step) until the next event is a managed-club match. */
    private void advanceUntilManagedClubPlaysNext(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());
            if (p.managedClubPlaysNext()) {
                return;
            }
            assertThat(p.nextEventType())
                    .as("season ended before a managed-club match was reached")
                    .isNotEqualTo("FINISHED");
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        throw new IllegalStateException("No managed-club match reached within guard");
    }

    @Test
    void previewExposesNextEventDefaultCompetitionAndBoundedPendingTransfers() {
        // Given a fresh career with no saved lineup (createCareer's own day-one market pass may
        // already have drawn a small, bounded number of incoming bids on the managed squad)
        Career c = createCareer();

        // When previewing the next advance
        AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());

        // Then the next event type is real, a default competition exists, and pending transfers
        // are within the day-one market's bound (MAX_BIDS_PER_ROUND)
        assertThat(p.nextEventType()).isIn("MATCH", "TRANSFER");
        assertThat(p.defaultStandingsCompetitionId()).isNotNull();
        assertThat(p.pendingTransfers()).isBetween(0, 3);
        assertThat(p.ineligibleStarters()).isEmpty();
    }

    @Test
    void ineligibleSavedStarterIsReportedBeforeAManagedClubMatch() {
        // Given a career whose next event is a managed-club match, with a full eligible XI saved
        Career c = createCareer();
        setBestEligibleLineup(c);
        advanceUntilManagedClubPlaysNext(c);
        // Re-save a clean eligible XI so every saved starter is eligible right now
        setBestEligibleLineup(c);

        // When one saved starter is injured and another is suspended
        LineupResponse saved = lineupService.getLineup(c.id(), c.clubId(), c.userId()).orElseThrow();
        UUID injuredId = saved.starters().get(0).playerId();
        UUID suspendedId = saved.starters().get(1).playerId();
        Player injured = playerRepository.findById(injuredId).orElseThrow();
        injured.addInjury(3);
        playerRepository.save(injured);
        Player suspended = playerRepository.findById(suspendedId).orElseThrow();
        suspended.addSuspension(2);
        playerRepository.save(suspended);

        // Then the preview reports both, each with the correct reason
        AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());
        assertThat(p.managedClubPlaysNext()).isTrue();
        assertThat(p.ineligibleStarters())
                .anyMatch(s -> s.playerId().equals(injuredId.toString()) && s.reason().equals("INJURED"));
        assertThat(p.ineligibleStarters())
                .anyMatch(s -> s.playerId().equals(suspendedId.toString()) && s.reason().equals("SUSPENDED"));
    }

    /** The club the managed side plays in the next scheduled round. */
    private UUID nextOpponentOf(Career c) {
        SeasonEvent next = seasonEventRepository.findNextManagedEvent(c.id(), c.clubId());
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(c.id(), next.getCompetitionId(), next.getRound())
                .stream()
                .filter(f -> c.clubId().equals(f.getHomeClubId()) || c.clubId().equals(f.getAwayClubId()))
                .findFirst().orElseThrow();
        return c.clubId().equals(fixture.getHomeClubId()) ? fixture.getAwayClubId() : fixture.getHomeClubId();
    }

    /**
     * A borrowed starter barred from THIS fixture is dropped by {@code startingXi} exactly as a
     * suspended one is, so leaving him out of the warning meant the manager lost a starter with no
     * explanation anywhere. Unlike suspension and injury, this is not a property of the player: he
     * is perfectly fit and available, and only this particular opponent — the club that owns him —
     * makes him unselectable, which is why the reason is its own value rather than folded into
     * INJURED.
     */
    @Test
    void aLoanBarredStarterIsReportedWithItsOwnReason() {
        // Given a career whose next event is a managed-club match, with a full eligible XI saved
        Career c = createCareer();
        setBestEligibleLineup(c);
        advanceUntilManagedClubPlaysNext(c);
        setBestEligibleLineup(c);

        // When one saved starter turns out to be on loan FROM the club they are about to play,
        // with no fixture release negotiated. Routing him through his owner first is what makes
        // loanTo() record the right owner; nothing else about him changes.
        UUID opponentClubId = nextOpponentOf(c);
        LineupResponse saved = lineupService.getLineup(c.id(), c.clubId(), c.userId()).orElseThrow();
        UUID barredId = saved.starters().get(0).playerId();
        Player barred = playerRepository.findById(barredId).orElseThrow();
        barred.setClubId(opponentClubId);
        barred.loanTo(c.clubId(), 50, false);
        playerRepository.save(barred);
        assertThat(playerRepository.findById(barredId).orElseThrow().isEligible())
                .as("he must be fit and unsuspended, or the reason below would not be the loan")
                .isTrue();

        // Then the preview names him, and says why
        AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());
        assertThat(p.managedClubPlaysNext()).isTrue();
        assertThat(p.ineligibleStarters())
                .anyMatch(st -> st.playerId().equals(barredId.toString())
                        && st.reason().equals("LOAN_BARRED"));
    }

    @Test
    void defaultCompetitionFollowsNextThenLastPlayedMatch() {
        // Given a fresh career, the default competition is the next scheduled match's competition
        Career c = createCareer();
        UUID expectedNext = earliestUnprocessedMatchCompetition(c.id());
        AdvancePreviewResponse before = advancePreviewService.preview(c.id(), c.userId());
        assertThat(before.defaultStandingsCompetitionId()).isEqualTo(expectedNext.toString());

        // When a match has been played
        advanceOneMatch(c);

        // Then the default competition follows the most recently played match
        UUID expectedLast = lastPlayedMatchCompetition(c.id());
        AdvancePreviewResponse after = advancePreviewService.preview(c.id(), c.userId());
        assertThat(after.defaultStandingsCompetitionId()).isEqualTo(expectedLast.toString());
    }

    /**
     * The Classificação default must stay on a competition the player is actually in.
     *
     * <p>The default is a {@code max}/{@code min} over MATCH events by {@code (date, sequence)},
     * and the sequence tiebreak used to work against it: a background round carries
     * {@link SeasonCalendarGenerator#BACKGROUND_MATCH_SEQUENCE} (2) against the managed
     * {@link SeasonCalendarGenerator#MANAGED_MATCH_SEQUENCE} (0), so on any shared date the
     * background competition won and the player landed on a third-division table right after their
     * own match. Background rounds are dated proportionally, so those collisions are structural.
     *
     * <p>Both branches are pinned — the "next scheduled" one before any match is played and the
     * "last played" one after — because each picks the wrong competition for a different reason:
     * an earlier date on the way in, and a higher sequence on the way out.
     */
    @Test
    void aBackgroundCompetitionNeverBecomesTheDefaultStandingsCompetition() {
        // Given a career and a competition the managed club has no membership row in
        Career c = createCareer();
        UUID backgroundCompetitionId = createBackgroundCompetition(c);

        // --- Branch 1: no match played yet, so the default is the NEXT scheduled match.
        SeasonEvent earliestManaged = seasonEventRepository.findNextManagedMatchEvent(c.id(), c.clubId());
        assertThat(earliestManaged).isNotNull();
        // A background round dated a day earlier would win an unscoped `min` outright.
        SeasonEvent earlyBackground = SeasonEvent.createMatch(c.id(),
                earliestManaged.getEventDate().minusDays(1), backgroundCompetitionId, 0,
                SeasonCalendarGenerator.BACKGROUND_MATCH_SEQUENCE);
        seasonEventRepository.save(earlyBackground);

        AdvancePreviewResponse beforeAnyMatch = advancePreviewService.preview(c.id(), c.userId());
        assertThat(beforeAnyMatch.defaultStandingsCompetitionId())
                .isEqualTo(earliestManaged.getCompetitionId().toString());

        // --- Branch 2: after a match is played, the default is the LAST played match.
        // Drop the planted event first, so the advance's background sweep never sees it and this
        // branch is exercised in isolation.
        seasonEventRepository.delete(earlyBackground);
        advanceOneMatch(c);

        SeasonEvent lastManaged = lastPlayedManagedMatchEvent(c);
        // A background round on the SAME date wins the (date, sequence) tiebreak unscoped, because
        // its sequence is the higher of the two. Guard against a vacuous pass.
        assertThat(SeasonCalendarGenerator.BACKGROUND_MATCH_SEQUENCE)
                .isGreaterThan(lastManaged.getSequence());
        SeasonEvent sameDayBackground = SeasonEvent.createMatch(c.id(), lastManaged.getEventDate(),
                backgroundCompetitionId, 0, SeasonCalendarGenerator.BACKGROUND_MATCH_SEQUENCE);
        sameDayBackground.markProcessed();
        seasonEventRepository.save(sameDayBackground);

        AdvancePreviewResponse afterAMatch = advancePreviewService.preview(c.id(), c.userId());
        assertThat(afterAMatch.defaultStandingsCompetitionId())
                .isEqualTo(lastManaged.getCompetitionId().toString());
        assertThat(afterAMatch.defaultStandingsCompetitionId())
                .isNotEqualTo(backgroundCompetitionId.toString());
    }

    /**
     * A competition built from the save's own clubs with the managed club deliberately left out —
     * that absence is the entire definition of "background". No fixtures: the default-competition
     * logic reads season events only.
     */
    private UUID createBackgroundCompetition(Career c) {
        List<Club> members = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> !club.getId().equals(c.clubId()))
                .sorted(Comparator.comparing(Club::getShortName))
                .limit(4)
                .toList();
        assertThat(members).hasSize(4);
        Competition background = Competition.create(c.id(), "Preview Background", "LEAGUE",
                c.save().seasonYear(), 6);
        competitionRepository.save(background);
        members.forEach(club -> competitionClubRepository.save(
                CompetitionClub.create(c.id(), background.getId(), club.getId())));
        return background.getId();
    }

    private SeasonEvent lastPlayedManagedMatchEvent(Career c) {
        return seasonEventRepository.findBySaveGameId(c.id()).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH && e.isProcessed())
                .filter(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), c.clubId()))
                .max(Comparator.comparing(SeasonEvent::getEventDate)
                        .thenComparingInt(SeasonEvent::getSequence))
                .orElseThrow();
    }

    @Test
    void windowClosingFlagRespectsCurrentDateAndPendingCountReflectsOffers() {
        // Given a career and the date of the next event
        Career c = createCareer();
        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(c.id());
        LocalDate nextDate = next.getEventDate();
        int year = nextDate.getYear();

        // And two unresolved offers — one outgoing (we bid) and one incoming (AI bids for us);
        // both count toward pendingTransfers regardless of direction.
        transferOfferRepository.save(TransferOffer.create(
                c.id(), UUID.randomUUID(), c.clubId(), UUID.randomUUID(),
                TransferDirection.OUTGOING, new BigDecimal("1000000.00"), 0,
                new BigDecimal("50000.00"), 2));
        transferOfferRepository.save(TransferOffer.create(
                c.id(), UUID.randomUUID(), UUID.randomUUID(), c.clubId(),
                TransferDirection.INCOMING, new BigDecimal("800000.00"), 0,
                new BigDecimal("40000.00"), 2));

        // When the current date is forced outside every transfer window (window closed now)
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(year, 12, 1));
        saveGameRepository.save(save);
        AdvancePreviewResponse closed = advancePreviewService.preview(c.id(), c.userId());

        // Then the window is not "about to close" (it is already shut) and the offer is counted
        assertThat(closed.windowClosingAfterAdvance()).isFalse();
        assertThat(closed.pendingTransfers()).isGreaterThanOrEqualTo(1);

        // When the current date is inside the open pre-season window (Jan 20)
        save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(year, 1, 20));
        saveGameRepository.save(save);
        AdvancePreviewResponse open = advancePreviewService.preview(c.id(), c.userId());

        // Then the flag matches the real predicate for the next event's date
        assertThat(open.windowClosingAfterAdvance()).isEqualTo(!transferWindow.isOpen(nextDate));
    }

    /**
     * Task 3 made loan offers die at window close exactly like transfer offers, and Task 6 made a
     * countered loan offer durable and visible — but {@code pendingTransfers} only ever counted
     * {@code TransferOfferRepository} rows, so a manager with an unresolved loan counter got no
     * warning before losing the ability to accept it. LoanOffer rows must count too.
     */
    @Test
    void windowClosingPendingCountIncludesCounteredLoanOffers() {
        // Given a career and a countered (still unresolved) loan offer on the managed club
        Career c = createCareer();
        LoanOffer offer = LoanOffer.create(c.id(), UUID.randomUUID(), UUID.randomUUID(), c.clubId(),
                50, false, 1);
        offer.markCountered(60, true);
        loanOfferRepository.save(offer);

        // When the current date is forced outside every transfer window (window closed now)
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(save.getCurrentDate().getYear(), 12, 1));
        saveGameRepository.save(save);
        AdvancePreviewResponse closed = advancePreviewService.preview(c.id(), c.userId());

        // Then the countered loan offer is counted alongside any transfer offers
        assertThat(closed.pendingTransfers()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void lowFitnessStarterIsReportedAsNonBlockingWarningBeforeAManagedClubMatch() {
        // Given a career whose next event is a managed-club match, with a full eligible XI saved
        Career c = createCareer();
        setBestEligibleLineup(c);
        advanceUntilManagedClubPlaysNext(c);
        // Re-save a clean eligible XI so every saved starter is eligible
        setBestEligibleLineup(c);

        // When one saved starter has base fitness 30 recorded on the current date (no recovery yet)
        LineupResponse saved = lineupService.getLineup(c.id(), c.clubId(), c.userId()).orElseThrow();
        UUID lowFitnessId = saved.starters().get(0).playerId();
        Player lowFitnessPlayer = playerRepository.findById(lowFitnessId).orElseThrow();
        SaveGame saveGame = saveGameRepository.findById(c.id()).orElseThrow();
        lowFitnessPlayer.recordAppearance(30, saveGame.getCurrentDate());
        playerRepository.save(lowFitnessPlayer);

        // Then the preview reports that player in lowFitnessStarters with fitness 30
        AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());
        assertThat(p.managedClubPlaysNext()).isTrue();
        assertThat(p.lowFitnessStarters())
                .anyMatch(s -> s.playerId().equals(lowFitnessId.toString()) && s.fitness() == 30);

        // And the low-fitness starter does NOT appear in ineligibleStarters (fitness never blocks)
        assertThat(p.ineligibleStarters())
                .noneMatch(s -> s.playerId().equals(lowFitnessId.toString()));
    }

    private AdvancePreviewResponse advanceOneMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(r.type())) {
                return advancePreviewService.preview(c.id(), c.userId());
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    private UUID earliestUnprocessedMatchCompetition(UUID saveGameId) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH && !e.isProcessed())
                .min(Comparator.comparing(SeasonEvent::getEventDate).thenComparingInt(SeasonEvent::getSequence))
                .map(SeasonEvent::getCompetitionId)
                .orElseThrow();
    }

    private UUID lastPlayedMatchCompetition(UUID saveGameId) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH && e.isProcessed())
                .max(Comparator.comparing(SeasonEvent::getEventDate).thenComparingInt(SeasonEvent::getSequence))
                .map(SeasonEvent::getCompetitionId)
                .orElseThrow();
    }
}
