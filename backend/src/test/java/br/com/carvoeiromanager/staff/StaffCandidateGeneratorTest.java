package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffCandidateGeneratorTest {

    private final StaffCandidateGenerator generator = new StaffCandidateGenerator(new SeedLoader());

    @Test
    void generatesTheRequestedNumberOfCandidatesWithNonBlankNamesAndPositiveWages() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 3);

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

        List<StaffCandidate> first = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);
        List<StaffCandidate> second = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differsWhenTheDateAdvances() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        List<StaffCandidate> day1 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 1), 3);
        List<StaffCandidate> day2 = generator.generate(saveGameId, clubId, StaffRole.ASSISTENTE,
                LocalDate.of(2026, 3, 2), 3);

        assertThat(day2).isNotEqualTo(day1);
    }

    @Test
    void differsAcrossRolesForTheSameClubAndDate() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        LocalDate onDate = LocalDate.of(2026, 3, 1);

        List<StaffCandidate> coach = generator.generate(saveGameId, clubId, StaffRole.PREPARADOR_FISICO, onDate, 3);
        List<StaffCandidate> scout = generator.generate(saveGameId, clubId, StaffRole.OLHEIRO, onDate, 3);

        assertThat(scout).isNotEqualTo(coach);
    }

    @Test
    void wageMatchesTheFixedTableForEachQualityTier() {
        List<StaffCandidate> candidates = generator.generate(UUID.randomUUID(), UUID.randomUUID(),
                StaffRole.FISIOTERAPEUTA, LocalDate.of(2026, 3, 1), 20);

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
                StaffRole.PREPARADOR_FISICO, LocalDate.of(2026, 3, 1), 50);
        for (StaffCandidate c : candidates) {
            int lo = switch (c.quality()) { case REGULAR -> 8; case BOM -> 12; case EXCELENTE -> 15; };
            int hi = switch (c.quality()) { case REGULAR -> 12; case BOM -> 16; case EXCELENTE -> 19; };
            assertThat(c.primaryAttribute()).isBetween(lo, hi);
            assertThat(c.secondaryAttribute()).isBetween(lo, hi);
        }
    }
}
