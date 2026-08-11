package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.seed.SeedClubStaff;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedStaff;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the generated V50 backfill against a career rewound to its pre-V50 state (all staff
 * deleted), which is exactly what an in-progress save looked like before this slice. Flyway has
 * already run by the time these tests execute and cannot be re-run, so the migration file is read
 * off the classpath and pushed through JdbcTemplate — PgJDBC runs the whole semicolon-separated
 * script in a single call. Asserting on the resulting rows (rather than on the SQL text) is the
 * point: a shape-only check would pass on SQL that is syntactically fine and semantically wrong.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ClubStaffBackfillIT {

    private static final String MIGRATION_RESOURCE = "db/migration/V50__club_staff.sql";

    @Autowired SaveGameService saveGameService;
    @Autowired StaffRepository staffRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired UserRepository userRepository;
    @Autowired SeedLoader seedLoader;
    @Autowired JdbcTemplate jdbcTemplate;

    private SaveGameResponse newCareer() {
        User user = User.create("backfill_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    private SaveGameResponse legacyCareer() {
        SaveGameResponse save = newCareer();
        // Rewind to the pre-V50 world: a career that never had a commission.
        jdbcTemplate.update("DELETE FROM staff WHERE save_game_id = ?", save.id());
        return save;
    }

    private void runBackfill() {
        try {
            jdbcTemplate.execute(
                    new ClassPathResource(MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to replay " + MIGRATION_RESOURCE, e);
        }
    }

    private String shortNameOf(UUID clubId) {
        return clubRepository.findById(clubId).orElseThrow().getShortName();
    }

    /** Every seeded (club, role, name) the save should end up with — the managed club's head coach
     *  excluded, because that job is the player's (SaveGameService.seedClubStaff). */
    private long expectedRowCount(String managedClubShortName) {
        return seedLoader.loadClubStaff().stream()
                .flatMap(entry -> entry.staff().stream()
                        .filter(member -> !(member.role() == StaffRole.TECNICO
                                && entry.clubShortName().equals(managedClubShortName))))
                .count();
    }

    /**
     * Everything about a save's commission that must not depend on how it got there: which club
     * (by short name, since ids differ per save), role, person, tier, wage and attributes.
     */
    private Set<String> commissionFingerprint(UUID saveGameId) {
        Map<UUID, String> shortNameByClubId = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getShortName));
        return staffRepository.findBySaveGameId(saveGameId).stream()
                .map(s -> String.join("|",
                        shortNameByClubId.get(s.getClubId()),
                        s.getRole().name(),
                        s.getName(),
                        s.getQuality().name(),
                        s.getWage().stripTrailingZeros().toPlainString(),
                        String.valueOf(s.getPrimaryAttribute()),
                        String.valueOf(s.getSecondaryAttribute())))
                .collect(Collectors.toSet());
    }

    @Test
    void itFillsTheCommissionOfACareerThatNeverHadOne() {
        SaveGameResponse save = legacyCareer();

        runBackfill();

        List<Staff> staff = staffRepository.findBySaveGameId(save.id());
        assertThat(staff).hasSize((int) expectedRowCount(shortNameOf(save.managedClubId())));
        assertThat(staff).allSatisfy(s -> {
            assertThat(s.getSaveGameId()).isEqualTo(save.id());
            assertThat(s.getQuality()).isEqualTo(StaffQuality.forAverage(s.attributeAverage()));
            assertThat(s.getWage()).isEqualByComparingTo(s.getQuality().monthlyWage());
        });
    }

    @Test
    void aBackfilledCareerEndsUpWithTheSameCommissionAFreshCareerIsSeededWith() {
        SaveGameResponse legacy = legacyCareer();
        SaveGameResponse fresh = newCareer();

        runBackfill();

        assertThat(commissionFingerprint(legacy.id())).isNotEmpty();
        assertThat(commissionFingerprint(legacy.id())).isEqualTo(commissionFingerprint(fresh.id()));
    }

    @Test
    void everyBackfilledStaffMemberLandsAtTheClubTheResearchPlacesThemAt() {
        SaveGameResponse save = legacyCareer();

        runBackfill();

        Map<String, String> expectedClubByName = seedLoader.loadClubStaff().stream()
                .flatMap(entry -> entry.staff().stream()
                        .map(member -> Map.entry(member.name(), entry.clubShortName())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<UUID, String> shortNameByClubId = clubRepository.findBySaveGameId(save.id()).stream()
                .collect(Collectors.toMap(Club::getId, Club::getShortName));

        assertThat(staffRepository.findBySaveGameId(save.id())).isNotEmpty().allSatisfy(s ->
                assertThat(shortNameByClubId.get(s.getClubId()))
                        .isEqualTo(expectedClubByName.get(s.getName())));
    }

    @Test
    void theManagedClubNeverGainsAHeadCoach() {
        SaveGameResponse save = legacyCareer();

        runBackfill();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.TECNICO))
                .isEmpty();
    }

    @Test
    void aSaveManagingAClubWithAResearchedHeadCoachStillGetsNoneWhileKeepingTheRestOfThatDugout() {
        // The default managed club (CRI) has no researched head coach, so guard (c) would never be
        // exercised by it. managed_club_id is per-save runtime state: point this save at a club the
        // research does give a head coach, which is what any career managing that club looks like.
        SeedClubStaff coached = seedLoader.loadClubStaff().stream()
                .filter(entry -> entry.staff().stream().anyMatch(m -> m.role() == StaffRole.TECNICO)
                        && entry.staff().stream().anyMatch(m -> m.role() != StaffRole.TECNICO))
                .findFirst()
                .orElseThrow();
        SaveGameResponse save = legacyCareer();
        UUID coachedClubId = clubRepository.findBySaveGameId(save.id()).stream()
                .filter(c -> c.getShortName().equals(coached.clubShortName()))
                .findFirst().orElseThrow().getId();
        jdbcTemplate.update("UPDATE save_games SET managed_club_id = ? WHERE id = ?",
                coachedClubId, save.id());

        runBackfill();

        assertThat(staffRepository.findByClubIdAndRole(coachedClubId, StaffRole.TECNICO)).isEmpty();
        // The skip is surgical: the rest of that club's researched staff still arrives.
        List<StaffRole> otherResearchedRoles = coached.staff().stream()
                .map(SeedStaff::role)
                .filter(role -> role != StaffRole.TECNICO)
                .toList();
        assertThat(staffRepository.findByClubId(coachedClubId))
                .extracting(Staff::getRole)
                .containsExactlyInAnyOrderElementsOf(otherResearchedRoles);
    }

    @Test
    void aStaffMemberTheUserPaidForIsNeverOverwritten() {
        SaveGameResponse save = legacyCareer();
        staffRepository.save(Staff.create(save.id(), save.managedClubId(),
                StaffRole.PREPARADOR_FISICO, StaffQuality.EXCELENTE, "Contratado Do Usuário",
                new BigDecimal("80000.00"), 18, 17));

        runBackfill();

        assertThat(staffRepository.findByClubIdAndRole(save.managedClubId(), StaffRole.PREPARADOR_FISICO))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo("Contratado Do Usuário"));
    }

    @Test
    void nobodyEndsUpEmployedAtTwoClubsAtOnce() {
        SaveGameResponse save = legacyCareer();
        // Someone the research places at a rival, but whom this save's user already hired.
        String alreadyHired = firstSeededRivalName(shortNameOf(save.managedClubId()));
        staffRepository.save(Staff.create(save.id(), save.managedClubId(), StaffRole.OLHEIRO,
                StaffQuality.BOM, alreadyHired, new BigDecimal("45000.00"), 13, 12));

        runBackfill();

        List<Staff> sameName = staffRepository.findBySaveGameId(save.id()).stream()
                .filter(s -> s.getName().equals(alreadyHired))
                .toList();
        assertThat(sameName).hasSize(1);
        assertThat(sameName.get(0).getClubId()).isEqualTo(save.managedClubId());
    }

    @Test
    void applyingTheMigrationATimeTooManyChangesNothing() {
        SaveGameResponse save = legacyCareer();
        runBackfill();
        Set<String> afterFirstRun = commissionFingerprint(save.id());
        int rowsAfterFirstRun = staffRepository.findBySaveGameId(save.id()).size();

        runBackfill();

        assertThat(rowsAfterFirstRun).isGreaterThan(0);
        assertThat(staffRepository.findBySaveGameId(save.id())).hasSize(rowsAfterFirstRun);
        assertThat(commissionFingerprint(save.id())).isEqualTo(afterFirstRun);
    }

    /** Any name the research assigns to a club other than the managed one — the collision guard is
     *  name-based, role-agnostic. */
    private String firstSeededRivalName(String managedClubShortName) {
        return seedLoader.loadClubStaff().stream()
                .filter(entry -> !entry.clubShortName().equals(managedClubShortName))
                .flatMap(entry -> entry.staff().stream())
                .map(SeedStaff::name)
                .findFirst()
                .orElseThrow();
    }
}
