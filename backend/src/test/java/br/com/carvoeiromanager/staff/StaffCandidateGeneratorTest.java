package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedStaff;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StaffCandidateGeneratorTest {

    private final StaffCandidateGenerator generator = new StaffCandidateGenerator(new SeedLoader());

    @Test
    void generatesTheRequestedNumberOfCandidatesWithNonBlankNamesAndPositiveWages() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 3, Set.of(), List.of());

        assertThat(candidates).hasSize(3);
        assertThat(candidates).allSatisfy(c -> {
            assertThat(c.name()).isNotBlank();
            assertThat(c.quality()).isNotNull();
            assertThat(c.wage().signum()).isPositive();
        });
    }

    @Test
    void isDeterministicForTheSameInputs() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 5, 10);

        List<StaffCandidate> first = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of(), List.of());
        List<StaffCandidate> second = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of(), List.of());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differsWhenTheDateAdvances() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        List<StaffCandidate> day1 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 1), 3, Set.of(), List.of());
        List<StaffCandidate> day2 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 2), 3, Set.of(), List.of());

        assertThat(day2).isNotEqualTo(day1);
    }

    @Test
    void differsAcrossRolesForTheSameClubAndDate() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 3, 1);

        List<StaffCandidate> coach = generator.generate(saveGameId, clubId, StaffRole.PREPARADOR_FISICO, onDate, 3, Set.of(), List.of());
        List<StaffCandidate> scout = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of(), List.of());

        assertThat(scout).isNotEqualTo(coach);
    }

    @Test
    void wageMatchesTheFixedTableForEachQualityTier() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.FISIOTERAPEUTA, LocalDate.of(2026, 3, 1), 20, Set.of(), List.of());

        assertThat(candidates).allSatisfy(c -> {
            switch (c.quality()) {
                case REGULAR -> assertThat(c.wage()).isEqualByComparingTo("20000.00");
                case BOM -> assertThat(c.wage()).isEqualByComparingTo("45000.00");
                case EXCELENTE -> assertThat(c.wage()).isEqualByComparingTo("80000.00");
            }
        });
    }

    @Test
    void candidateAttributesFallInsideTheTierBand() {
        UUID saveId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(saveId, clubId,
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 50, Set.of(), List.of());
        for (StaffCandidate c : candidates) {
            int lo = switch (c.quality()) { case REGULAR -> 8; case BOM -> 12; case EXCELENTE -> 15; };
            int hi = switch (c.quality()) { case REGULAR -> 12; case BOM -> 16; case EXCELENTE -> 19; };
            assertThat(c.primaryAttribute()).isBetween(lo, hi);
            assertThat(c.secondaryAttribute()).isBetween(lo, hi);
        }
    }

    /**
     * The researched ASSISTENTE pool (staff-pool.json) — name to authored
     * [primaryAttribute, secondaryAttribute]. A superset: three of these names have since moved
     * into club-staff.json as employed staff. Used to verify tier/wage derivation, and to tell a
     * real free professional apart from a generated newgen, without pinning a specific pair picked
     * by the seeded shuffle.
     */
    private static final Map<String, int[]> ASSISTENTE_POOL = Map.ofEntries(
            Map.entry("Anderson Valiñas", new int[] {13, 12}),
            Map.entry("Leonardo Cherede", new int[] {13, 12}),
            Map.entry("Rodrigo Vicenzi Casarin", new int[] {14, 13}),
            Map.entry("Celso Martins", new int[] {14, 12}),
            Map.entry("Henrique Lima", new int[] {11, 11}),
            Map.entry("Denis Iwamura", new int[] {15, 13}),
            Map.entry("Leandro Campos", new int[] {12, 12}),
            Map.entry("Willians Alves", new int[] {12, 12}),
            Map.entry("Gabriel Remedio", new int[] {12, 12}),
            Map.entry("Edson Boaro", new int[] {13, 11}),
            Map.entry("Warley", new int[] {10, 12}),
            Map.entry("Rafael Santiago", new int[] {11, 10}),
            Map.entry("Rafael Prerniska", new int[] {11, 11}),
            Map.entry("Bruno Lazaroni", new int[] {18, 16}),
            Map.entry("Márcio Goiano", new int[] {13, 12}));

    @Test
    void aPoolProfessionalKeepsTheirAuthoredAttributesWithADerivedTierAndTierWage() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 2, Set.of(), List.of());

        // Nobody is employed elsewhere here, so the market is the free pool alone — and it holds
        // 12 ASSISTENTE against the two slots asked for, so neither can be a generated newgen.
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(StaffCandidate::name)
                .doesNotHaveDuplicates()
                .allMatch(ASSISTENTE_POOL::containsKey);

        for (StaffCandidate c : candidates) {
            int[] authored = ASSISTENTE_POOL.get(c.name());
            assertThat(c.primaryAttribute()).isEqualTo(authored[0]);
            assertThat(c.secondaryAttribute()).isEqualTo(authored[1]);

            double average = (authored[0] + authored[1]) / 2.0;
            StaffQuality expectedQuality = average >= 15.0 ? StaffQuality.EXCELENTE
                    : average >= 12.0 ? StaffQuality.BOM : StaffQuality.REGULAR;
            assertThat(c.quality()).isEqualTo(expectedQuality);

            BigDecimal expectedWage = switch (expectedQuality) {
                case REGULAR -> new BigDecimal("20000.00");
                case BOM -> new BigDecimal("45000.00");
                case EXCELENTE -> new BigDecimal("80000.00");
            };
            assertThat(c.wage()).isEqualByComparingTo(expectedWage);
        }
    }

    @Test
    void excludedNamesAreNeverOfferedAndGeneratedNamesFillTheGap() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 20, Set.of(ASSISTENTE_POOL.keySet().toArray(new String[0])), List.of());
        assertThat(candidates).hasSize(20);
        assertThat(candidates).extracting(StaffCandidate::name)
                .doesNotContainAnyElementsOf(ASSISTENTE_POOL.keySet());
    }

    /** Every free professional the seed pool holds for a role. */
    private static Set<String> freePoolNamesFor(StaffRole role) {
        return new SeedLoader().loadStaffPool().stream()
                .filter(s -> s.role() == role)
                .map(SeedStaff::name)
                .collect(Collectors.toSet());
    }

    /**
     * Browses {@code role} day by day and returns the first offer that names {@code wanted}. A
     * candidate's odds are their share of the market, so one given day is a coin flip — this walks
     * the calendar instead, which is deterministic for fixed ids because the seed folds in the
     * date.
     */
    private List<StaffCandidate> firstOfferNaming(String wanted, UUID saveGameId, UUID clubId,
                                                  StaffRole role, List<EmployedStaff> employedElsewhere) {
        for (int day = 0; day < 365; day++) {
            List<StaffCandidate> candidates = generator.generate(saveGameId, clubId, role,
                    LocalDate.of(2026, 1, 1).plusDays(day), 3, Set.of(), employedElsewhere);
            if (candidates.stream().anyMatch(c -> c.name().equals(wanted))) {
                return candidates;
            }
        }
        throw new AssertionError(wanted + " was never offered in a year of browsing " + role);
    }

    @Test
    void aPoachableRivalIsOfferedWithTheirCurrentClub() {
        // Given a rival's scout in the same market as the 14 free OLHEIRO — no exclusions needed,
        // because there is no pool-first tier left to empty before poaching becomes reachable
        UUID SAVE_ID = UUID.fromString("7c0dbd8e-0000-4000-8000-000000000001");
        UUID CLUB_ID = UUID.fromString("7c0dbd8e-0000-4000-8000-000000000002");
        UUID staffId = UUID.fromString("7c0dbd8e-0000-4000-8000-000000000003");

        // When the manager browses for a scout
        List<StaffCandidate> candidates = firstOfferNaming("Rival Contratado", SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO,
                List.of(new EmployedStaff(staffId, "Rival Contratado", "Avaí Futebol Clube", 16, 15)));

        // Then the rival comes with the club they would have to be taken from
        assertThat(candidates).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("Rival Contratado");
            assertThat(c.sourceStaffId()).isEqualTo(staffId);
            assertThat(c.currentClubName()).isEqualTo("Avaí Futebol Clube");
        });
    }

    @Test
    void aPoachedRivalIsPricedAndTieredFromTheirRealAttributes() {
        // The free pool is excluded so the market holds exactly one person and the single offered
        // candidate is certain to be them — not to reach any tier, which no longer exists.
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.OLHEIRO, LocalDate.of(2026, 1, 10), 1,
                freePoolNamesFor(StaffRole.OLHEIRO),
                List.of(new EmployedStaff(UUID.randomUUID(), "Rival Contratado", "Avaí Futebol Clube", 11, 10)));

        // 10.5 average, so REGULAR and the REGULAR wage — the source club's own quality column is
        // never consulted, exactly as the authored pool derives its tier from the attributes.
        assertThat(candidates).singleElement().satisfies(c -> {
            assertThat(c.quality()).isEqualTo(StaffQuality.REGULAR);
            assertThat(c.wage()).isEqualByComparingTo("20000.00");
            assertThat(c.primaryAttribute()).isEqualTo(11);
            assertThat(c.secondaryAttribute()).isEqualTo(10);
        });
    }

    @Test
    void freeProfessionalsAndPoachingTargetsCompeteInOneMarket() {
        // Given a role whose free pool (12 ASSISTENTE) and employed rivals are of similar depth
        UUID SAVE_ID = UUID.fromString("3a51e7b2-0000-4000-8000-000000000001");
        UUID CLUB_ID = UUID.fromString("3a51e7b2-0000-4000-8000-000000000002");
        List<EmployedStaff> rivals = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new EmployedStaff(
                        UUID.fromString("3a51e7b2-0000-4000-8000-0000000001" + String.format("%02d", i)),
                        "Empregado " + i, "Avaí Futebol Clube", 13, 12))
                .toList();

        // When the manager browses the role every day for two months
        boolean sawFreeProfessional = false;
        boolean sawPoachingTarget = false;
        boolean sawBothInOneOffer = false;
        for (int day = 0; day < 60; day++) {
            List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                    StaffRole.ASSISTENTE, LocalDate.of(2026, 1, 10).plusDays(day), 3, Set.of(), rivals);
            boolean free = candidates.stream()
                    .anyMatch(c -> c.sourceStaffId() == null && ASSISTENTE_POOL.containsKey(c.name()));
            boolean poached = candidates.stream().anyMatch(c -> c.sourceStaffId() != null);
            sawFreeProfessional |= free;
            sawPoachingTarget |= poached;
            sawBothInOneOffer |= free && poached;
        }

        // Then both kinds turn up, and turn up side by side — there is no tier that has to run dry
        // before a rival's employee can be offered.
        assertThat(sawFreeProfessional).isTrue();
        assertThat(sawPoachingTarget).isTrue();
        assertThat(sawBothInOneOffer).isTrue();
    }

    @Test
    void aPoachingTargetAlreadyHiredAtTheClubIsNotOfferedAgain() {
        Set<String> excluded = new java.util.HashSet<>(freePoolNamesFor(StaffRole.OLHEIRO));
        excluded.add("Rival Contratado");

        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.OLHEIRO, LocalDate.of(2026, 1, 10), 3, excluded,
                List.of(new EmployedStaff(UUID.randomUUID(), "Rival Contratado", "Avaí Futebol Clube", 16, 15)));

        assertThat(candidates).extracting(StaffCandidate::name).doesNotContain("Rival Contratado");
        assertThat(candidates).allSatisfy(c -> assertThat(c.sourceStaffId()).isNull());
    }

    @Test
    void generationIsDeterministicForTheSameInputs() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> a = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of(), List.of());
        List<StaffCandidate> b = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of(), List.of());
        assertThat(a).isEqualTo(b);
    }
}
