package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.staff.StaffService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link MatchPreviewService}: no-op without an ASSISTENTE, persists a MATCH_PREVIEW
 * news item dated the eve of the managed club's next fixture, is idempotent per fixture, gates
 * analysis depth by the assistant's two attributes, and is reachable from both the hire and
 * advance call sites.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MatchPreviewServiceIT {

    @Autowired MatchPreviewService matchPreviewService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired StaffService staffService;
    @Autowired StaffRepository staffRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("preview_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private void hireAssistant(Career c, int primaryAttribute, int secondaryAttribute) {
        // The managed club now starts with a researched ASSISTENTE seeded at career creation
        // (Task 4), so replace rather than blindly insert — otherwise this collides with the
        // staff_club_id_role_key unique constraint. (Base CrudRepository#delete auto-wraps in
        // its own transaction outside a @Transactional test; the derived deleteByClubIdAndRole
        // does not, so it can't be used bare here.)
        staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.ASSISTENTE).ifPresent(staffRepository::delete);
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.ASSISTENTE, StaffQuality.EXCELENTE,
                "Auxiliar Teste", new BigDecimal("80000.00"), primaryAttribute, secondaryAttribute));
    }

    /**
     * The opponent for the managed club's next fixture, resolved the same way
     * {@link MatchPreviewService#buildPreview} does — needed before the first
     * {@code maybePersistPreview} call so a loan can be planted ahead of it (previews are written
     * once per fixture, so mutating the squad afterwards would be too late).
     */
    private UUID opponentClubIdFor(Career c) {
        SeasonEvent nextMatch = seasonEventRepository.findNextManagedMatchEvent(c.id(), c.clubId());
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(c.id(), nextMatch.getCompetitionId(), nextMatch.getRound())
                .stream()
                .filter(f -> c.clubId().equals(f.getHomeClubId()) || c.clubId().equals(f.getAwayClubId()))
                .findFirst().orElseThrow();
        return c.clubId().equals(fixture.getHomeClubId()) ? fixture.getAwayClubId() : fixture.getHomeClubId();
    }

    /**
     * Lends the managed squad's strongest non-youth player to the opponent of the next fixture,
     * boosted to overall 99 so he is the unambiguous top pick in {@code findByClubIdAndYouthProspectFalse}
     * whenever the loan bar does not exclude him — mirroring {@code LoanFixtureEligibilityIT}'s pattern.
     */
    private Player lendManagedPlayerToNextOpponent(Career c, UUID opponentId, boolean mayFaceOwner) {
        Player lentPlayer = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseThrow();
        lentPlayer.setOverall(99);
        lentPlayer.loanTo(opponentId, 50, mayFaceOwner);
        return playerRepository.save(lentPlayer);
    }

    private AdvanceResponse deserialize(NewsItem item) {
        try {
            return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
        } catch (Exception e) {
            throw new AssertionError(
                    "Stored MATCH_PREVIEW payload is not valid AdvanceResponse JSON: " + item.getPayload(), e);
        }
    }

    private MatchPreviewData latestPreview(UUID saveGameId) {
        NewsItem item = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, "MATCH_PREVIEW");
        assertThat(item).isNotNull();
        return deserialize(item).matchPreview();
    }

    @Test
    void noAssistantMeansNoPreview() {
        // Fresh career; explicitly remove the researched ASSISTENTE career creation now seeds
        // for the managed club so this test still exercises the true no-assistant case.
        Career c = createCareer();
        staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.ASSISTENTE).ifPresent(staffRepository::delete);

        matchPreviewService.maybePersistPreview(c.id());

        assertThat(newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(c.id(), "MATCH_PREVIEW"))
                .isNull();
    }

    @Test
    void previewIsPersistedDatedTheEveOfTheMatchWithByline() {
        Career c = createCareer();
        hireAssistant(c, 18, 18);

        matchPreviewService.maybePersistPreview(c.id());

        NewsItem item = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(c.id(), "MATCH_PREVIEW");
        assertThat(item).isNotNull();
        AdvanceResponse payload = deserialize(item);
        assertThat(payload.type()).isEqualTo("MATCH_PREVIEW");
        MatchPreviewData preview = payload.matchPreview();
        assertThat(preview).isNotNull();
        assertThat(preview.assistantName()).isEqualTo("Auxiliar Teste");
        assertThat(preview.opponentName()).isNotBlank();
        assertThat(preview.opponentStanding()).isNotNull();
        assertThat(preview.managedStanding()).isNotNull();

        // eventDate is the eve of the fixture.
        LocalDate matchDate = LocalDate.parse(preview.matchDate());
        assertThat(item.getEventDate()).isEqualTo(matchDate.minusDays(1));
        assertThat(payload.date()).isEqualTo(matchDate.minusDays(1).toString());

        // Full depth at 18/18: top-3 players, tactical read and 3 insights present.
        assertThat(preview.bestPlayers()).hasSize(3);
        assertThat(preview.tacticalRead()).isNotNull();
        assertThat(preview.insights()).hasSize(3);
    }

    @Test
    void secondCallForTheSameFixtureIsIdempotent() {
        Career c = createCareer();
        hireAssistant(c, 18, 18);

        matchPreviewService.maybePersistPreview(c.id());
        matchPreviewService.maybePersistPreview(c.id());

        long previews = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id()).stream()
                .filter(n -> n.getType().equals("MATCH_PREVIEW"))
                .count();
        assertThat(previews).isEqualTo(1);
    }

    @Test
    void weakAssistantGetsOnlyTheBasics() {
        // CT 10 (<12): no tactical read/insights; AJ 10 (<12): single player.
        Career c = createCareer();
        hireAssistant(c, 10, 10);

        matchPreviewService.maybePersistPreview(c.id());

        MatchPreviewData preview = latestPreview(c.id());
        assertThat(preview.bestPlayers()).hasSize(1);
        assertThat(preview.topScorer()).isNull();
        assertThat(preview.tacticalRead()).isNull();
        assertThat(preview.insights()).isNull();
    }

    @Test
    void midAssistantGetsTacticalReadButNoInsights() {
        // CT 13 (12-15): read only; AJ 13: top-3, no topScorer.
        Career c = createCareer();
        hireAssistant(c, 13, 13);

        matchPreviewService.maybePersistPreview(c.id());

        MatchPreviewData preview = latestPreview(c.id());
        assertThat(preview.bestPlayers()).hasSize(3);
        assertThat(preview.topScorer()).isNull();
        assertThat(preview.tacticalRead()).isNotNull();
        assertThat(preview.insights()).isNull();
    }

    @Test
    void hiringTheAssistantThroughTheServiceDeliversThePendingPreview() {
        // Hiring the ASSISTENTE via StaffService.hire(...) with a real candidate index should
        // fire the same hook and immediately deliver the pending pre-match analysis.
        Career c = createCareer();
        // hire takes the name the shortlist showed as well as the slot, so read the offer first.
        String offered = staffService.getCandidates(c.id(), c.clubId(), StaffRole.ASSISTENTE, c.userId())
                .get(0).name();

        staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 0, offered, c.userId());

        assertThat(newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(c.id(), "MATCH_PREVIEW"))
                .isNotNull();
    }

    @Test
    void advancePersistsThePreviewForTheFollowingRound() {
        Career c = createCareer();
        hireAssistant(c, 18, 18);

        seasonAdvanceService.advance(c.id(), c.userId()); // processes the first event

        assertThat(newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(c.id(), "MATCH_PREVIEW"))
                .isNotNull(); // preview exists for the next managed fixture
    }

    // --- The opponent's "best players" panel must respect the loan bar (LoanEligibility) ---

    @Test
    void aBarredLoanedPlayerDoesNotHeadlineTheOpponentsBestPlayersPanel() {
        // Lend the managed club's own star to the club it plays next, with no negotiated release —
        // he now plays for the opponent (best-player panel candidate material at overall 99) but is
        // barred from facing his owner, the managed club, in this very fixture.
        Career c = createCareer();
        UUID opponentId = opponentClubIdFor(c);
        Player lentPlayer = lendManagedPlayerToNextOpponent(c, opponentId, false);
        hireAssistant(c, 18, 18); // full depth: top-3 best players

        matchPreviewService.maybePersistPreview(c.id());

        MatchPreviewData preview = latestPreview(c.id());
        assertThat(preview.bestPlayers())
                .as("a lent player barred from this fixture must not headline the opponent's danger men")
                .extracting(MatchPreviewData.PreviewPlayer::name)
                .doesNotContain(lentPlayer.getName());
    }

    @Test
    void aLoanedPlayerWithTheNegotiatedReleaseStillHeadlinesTheOpponentsBestPlayersPanel() {
        // Same setup, but the loan negotiated the release to face the owner — he may play, so at
        // overall 99 he is the unambiguous top pick and must still headline the panel.
        Career c = createCareer();
        UUID opponentId = opponentClubIdFor(c);
        Player lentPlayer = lendManagedPlayerToNextOpponent(c, opponentId, true);
        hireAssistant(c, 18, 18);

        matchPreviewService.maybePersistPreview(c.id());

        MatchPreviewData preview = latestPreview(c.id());
        assertThat(preview.bestPlayers())
                .as("the negotiated release lets him appear as the opponent's top danger man")
                .extracting(MatchPreviewData.PreviewPlayer::name)
                .contains(lentPlayer.getName());
    }
}
