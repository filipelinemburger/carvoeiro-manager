package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffCandidateGeneratorTest {

    private final StaffCandidateGenerator generator = new StaffCandidateGenerator(new SeedLoader());

    @Test
    void generatesTheRequestedNumberOfCandidatesWithNonBlankNamesAndPositiveWages() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 3, Set.of());

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

        List<StaffCandidate> first = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of());
        List<StaffCandidate> second = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differsWhenTheDateAdvances() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        List<StaffCandidate> day1 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 1), 3, Set.of());
        List<StaffCandidate> day2 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 2), 3, Set.of());

        assertThat(day2).isNotEqualTo(day1);
    }

    @Test
    void differsAcrossRolesForTheSameClubAndDate() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 3, 1);

        List<StaffCandidate> coach = generator.generate(saveGameId, clubId, StaffRole.PREPARADOR_FISICO, onDate, 3, Set.of());
        List<StaffCandidate> scout = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3, Set.of());

        assertThat(scout).isNotEqualTo(coach);
    }

    @Test
    void wageMatchesTheFixedTableForEachQualityTier() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.FISIOTERAPEUTA, LocalDate.of(2026, 3, 1), 20, Set.of());

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
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 50, Set.of());
        for (StaffCandidate c : candidates) {
            int lo = switch (c.quality()) { case REGULAR -> 8; case BOM -> 12; case EXCELENTE -> 15; };
            int hi = switch (c.quality()) { case REGULAR -> 12; case BOM -> 16; case EXCELENTE -> 19; };
            assertThat(c.primaryAttribute()).isBetween(lo, hi);
            assertThat(c.secondaryAttribute()).isBetween(lo, hi);
        }
    }

    @Test
    void poolMembersAreOfferedFirstWithDerivedTierAndTierWage() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 2, Set.of());
        // The starter pool has exactly 2 ASSISTENTE entries, so both slots come from the pool.
        assertThat(candidates).extracting(StaffCandidate::name)
                .containsExactlyInAnyOrder("Cléber Xavier", "Matheus Bachi");
        StaffCandidate cleber = candidates.stream()
                .filter(c -> c.name().equals("Cléber Xavier")).findFirst().orElseThrow();
        assertThat(cleber.primaryAttribute()).isEqualTo(17);
        assertThat(cleber.secondaryAttribute()).isEqualTo(16);
        assertThat(cleber.quality()).isEqualTo(StaffQuality.EXCELENTE); // avg 16.5 >= 15
        assertThat(cleber.wage()).isEqualByComparingTo("80000.00");
    }

    @Test
    void excludedNamesAreNeverOfferedAndGeneratedNamesFillTheGap() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> candidates = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.ASSISTENTE, LocalDate.of(2026, 3, 1), 3,
                Set.of("Cléber Xavier", "Matheus Bachi"));
        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(StaffCandidate::name)
                .doesNotContain("Cléber Xavier", "Matheus Bachi");
    }

    @Test
    void generationIsDeterministicForTheSameInputs() {
        UUID SAVE_ID = UUID.randomUUID();
        UUID CLUB_ID = UUID.randomUUID();
        List<StaffCandidate> a = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of());
        List<StaffCandidate> b = generator.generate(SAVE_ID, CLUB_ID,
                StaffRole.OLHEIRO, LocalDate.of(2026, 3, 1), 3, Set.of());
        assertThat(a).isEqualTo(b);
    }
}
