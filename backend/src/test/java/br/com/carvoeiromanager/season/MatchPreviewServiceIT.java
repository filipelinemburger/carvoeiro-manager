package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
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
}
