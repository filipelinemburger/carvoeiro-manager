package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What poaching does to the club that was robbed — against Postgres, the real repositories and the
 * real seeded research, because the bug this pins lived exactly in the gap between them.
 *
 * <p>{@code StaffEffectsService} floors an AI club's empty slot at an attribute average of 14.0, so
 * that a role the seed research never filled does not read as "this club employs nobody". Poaching
 * deletes the source club's row, which produced precisely that empty state — and 46 of the 53
 * poachable rows in the seed sit below 14.0. Stealing a rival's staff therefore UPGRADED that rival,
 * permanently, since nothing ever refills the slot. Over a career the player could walk every rival
 * up to the baseline: a league-wide buff to the AI, handed out as the reward for robbing them.
 *
 * <p>Kept apart from {@link StaffServiceIT}, which is about hire/browse mechanics — these tests are
 * about the numbers the rest of the engine then reads.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class StaffPoachingEffectsIT {

    /** Mirrors {@code StaffEffectsService.AI_BASELINE_AVERAGE}, which is private for good reason. */
    private static final double AI_BASELINE_AVERAGE = 14.0;

    /**
     * The injury multiplier the baseline produces: the exact double the production lerp has always
     * returned for average 14.0 (see {@code StaffEffectsServiceTest}), not a re-derivation.
     */
    private static final double BASELINE_INJURY_MULTIPLIER = 0.8285714285714285;

    /** Days of browsing before giving up; the shortlist is a fresh draw each day. */
    private static final int BROWSING_DAYS = 60;

    @Autowired StaffService staffService;
    @Autowired StaffEffectsService staffEffectsService;
    @Autowired StaffRepository staffRepository;
    @Autowired StaffVacancyRepository staffVacancyRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    /** A shortlist slot to hire, together with the row the victim currently has on its books. */
    private record PoachTarget(int candidateIndex, Staff sourceRow) {
        UUID robbedClubId() { return sourceRow.getClubId(); }
    }

    private Career createCareer() {
        User user = User.create("poach" + UUID.randomUUID().toString().replace("-", ""), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /**
     * Browses {@code role} day by day, advancing the save's own date, until the shortlist offers a
     * rival's employee whose attribute average is BELOW the baseline — and leaves the save parked
     * on that date, so a follow-up {@code hire} sees the same three candidates.
     *
     * <p>Below-baseline is what makes the case interesting: those are the poaches the old code
     * rewarded. Walking the calendar rather than pinning a date keeps it deterministic without
     * betting on a single draw — the candidate seed folds in the date, and free professionals and
     * rivals' employees compete for the same three slots, so any given day may offer none.
     */
    private PoachTarget browseUntilABelowBaselineRivalIsOffered(Career c, StaffRole role) {
        final SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        final LocalDate browsingFrom = save.getCurrentDate();
        final Map<String, Staff> employedElsewhere = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == role)
                .filter(s -> !s.getClubId().equals(c.clubId()))
                .collect(Collectors.toMap(Staff::getName, s -> s, (first, ignored) -> first));

        for (int day = 0; day < BROWSING_DAYS; day++) {
            save.setCurrentDate(browsingFrom.plusDays(day));
            saveGameRepository.saveAndFlush(save);
            for (StaffCandidateResponse candidate
                    : staffService.getCandidates(c.id(), c.clubId(), role, c.userId())) {
                if (candidate.currentClubName() == null) {
                    continue;
                }
                Staff onTheBooks = employedElsewhere.get(candidate.name());
                if (onTheBooks != null && onTheBooks.attributeAverage() < AI_BASELINE_AVERAGE) {
                    return new PoachTarget(candidate.index(), onTheBooks);
                }
            }
        }
        throw new AssertionError("No below-baseline " + role + " employed at a rival club was offered in "
                + BROWSING_DAYS + " days of browsing. The seed research may have changed.");
    }

    @Test
    void poachingABelowBaselineRivalNeverMakesThatRivalBetter() {
        // Given a rival employing a physio whose attributes sit below the research baseline
        Career c = createCareer();
        PoachTarget target = browseUntilABelowBaselineRivalIsOffered(c, StaffRole.FISIOTERAPEUTA);
        double before = staffEffectsService.injuryRiskMultiplierFor(target.robbedClubId(), false);

        // A lower multiplier means fewer injuries, so "better for the club" is DOWNWARDS. Employing
        // someone below the baseline has to leave them above what the baseline alone would give —
        // otherwise this poach is not the case that used to backfire and the test proves nothing.
        assertThat(target.sourceRow().attributeAverage()).isLessThan(AI_BASELINE_AVERAGE);
        assertThat(before).isGreaterThan(BASELINE_INJURY_MULTIPLIER);

        // When the manager poaches them
        staffService.hire(c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA,
                target.candidateIndex(), c.userId());

        // Then the robbed club is worse off, or at best no better — never better. Before the
        // vacancy existed this landed on 0.8286: the theft bought the victim fewer injuries.
        double after = staffEffectsService.injuryRiskMultiplierFor(target.robbedClubId(), false);
        assertThat(after)
                .as("robbing a club must never improve it")
                .isGreaterThanOrEqualTo(before)
                .isEqualTo(1.0);
        assertThat(staffRepository.findByClubIdAndRole(target.robbedClubId(), StaffRole.FISIOTERAPEUTA))
                .isEmpty();
        assertThat(staffVacancyRepository
                .existsByClubIdAndRole(target.robbedClubId(), StaffRole.FISIOTERAPEUTA)).isTrue();
    }

    @Test
    void poachingLeavesTheRobbedClubsOtherRolesExactlyAsTheyWere() {
        // A vacancy is recorded for one (club, role) pair, so it must not leak into the club's
        // other slots — an unresearched role there is still a research gap and still gets the floor.
        Career c = createCareer();
        PoachTarget target = browseUntilABelowBaselineRivalIsOffered(c, StaffRole.FISIOTERAPEUTA);
        UUID robbed = target.robbedClubId();
        int trainingBefore = staffEffectsService.trainingBonusFor(robbed, false);
        double wageBefore = staffEffectsService.wageDemandMultiplierFor(robbed, false);
        double sponsorshipBefore = staffEffectsService.sponsorshipMultiplierFor(robbed, false);
        double coachBefore = staffEffectsService.coachStrengthMultiplierFor(robbed);

        staffService.hire(c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA,
                target.candidateIndex(), c.userId());

        assertThat(staffEffectsService.trainingBonusFor(robbed, false)).isEqualTo(trainingBefore);
        assertThat(staffEffectsService.wageDemandMultiplierFor(robbed, false)).isEqualTo(wageBefore);
        assertThat(staffEffectsService.sponsorshipMultiplierFor(robbed, false))
                .isEqualTo(sponsorshipBefore);
        assertThat(staffEffectsService.coachStrengthMultiplierFor(robbed)).isEqualTo(coachBefore);
    }

    @Test
    void theManagedClubGetsTheBenefitOfThePoach() {
        // The other half of the trade: the player's own club goes from an empty slot (neutral) to
        // employing a real physio, so its injury multiplier drops below neutral.
        Career c = createCareer();
        PoachTarget target = browseUntilABelowBaselineRivalIsOffered(c, StaffRole.FISIOTERAPEUTA);
        assertThat(staffEffectsService.injuryRiskMultiplierFor(c.clubId(), true)).isEqualTo(1.0);

        staffService.hire(c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA,
                target.candidateIndex(), c.userId());

        assertThat(staffEffectsService.injuryRiskMultiplierFor(c.clubId(), true)).isLessThan(1.0);
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.FISIOTERAPEUTA))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo(target.sourceRow().getName()));
    }

    @Test
    void aRivalSlotNobodyEverResearchedStillGetsTheBaseline() {
        // The guard against over-correcting: the fix must narrow the baseline to slots the player
        // emptied, not delete it. Only four clubs' physios were ever researched, so most rivals
        // reach the effects service with an empty FISIOTERAPEUTA slot and no vacancy to explain it.
        Career c = createCareer();
        UUID neverResearched = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()))
                .filter(id -> staffRepository.findByClubIdAndRole(id, StaffRole.FISIOTERAPEUTA).isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected at least one rival club with no seeded FISIOTERAPEUTA — none found."));

        assertThat(staffVacancyRepository
                .existsByClubIdAndRole(neverResearched, StaffRole.FISIOTERAPEUTA)).isFalse();
        assertThat(staffEffectsService.injuryRiskMultiplierFor(neverResearched, false))
                .isEqualTo(BASELINE_INJURY_MULTIPLIER);
        assertThat(staffEffectsService.trainingBonusFor(neverResearched, false)).isEqualTo(1);
    }
}
