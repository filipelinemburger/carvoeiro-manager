package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.match.LineupResponse;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
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
    @Autowired TransferWindow transferWindow;

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
        List<LineupStarter> starters = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
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
    void previewExposesNextEventDefaultCompetitionAndNoPendingTransfers() {
        // Given a fresh career with no saved lineup and no offers
        Career c = createCareer();

        // When previewing the next advance
        AdvancePreviewResponse p = advancePreviewService.preview(c.id(), c.userId());

        // Then the next event type is real, a default competition exists, and nothing is pending
        assertThat(p.nextEventType()).isIn("MATCH", "TRANSFER");
        assertThat(p.defaultStandingsCompetitionId()).isNotNull();
        assertThat(p.pendingTransfers()).isZero();
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
