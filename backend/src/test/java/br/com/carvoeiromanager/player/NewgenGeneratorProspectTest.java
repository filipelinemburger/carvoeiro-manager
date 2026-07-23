package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NewgenGeneratorProspectTest {

    @Autowired NewgenGenerator generator;

    @Test
    void generatesAYoungProspectWithAHiddenPotentialAboveCurrentAbility() {
        Player p = generator.generateProspect(UUID.randomUUID(), UUID.randomUUID(), Position.MID, new Random(1L));
        assertThat(p.isYouthProspect()).isTrue();
        assertThat(p.getAge()).isBetween(15, 17);
        assertThat(p.getPotential()).isGreaterThanOrEqualTo(p.getOverall());
        assertThat(p.getPotential()).isLessThanOrEqualTo(92);
        assertThat(p.getContractYears()).isZero();
    }

    @Test
    void generatedProspectGetsACoarseConsistentDetailedPosition() {
        for (Position position : Position.values()) {
            Player p = generator.generateProspect(UUID.randomUUID(), UUID.randomUUID(), position, new Random(3L));
            assertThat(p.getDetailedPosition()).isNotNull();
            assertThat(p.getDetailedPosition().coarse()).isEqualTo(position);
        }
    }

    @Test
    void isDeterministicForAGivenSeed() {
        UUID sg = UUID.randomUUID(), club = UUID.randomUUID();
        Player a = generator.generateProspect(sg, club, Position.ATT, new Random(42L));
        Player b = generator.generateProspect(sg, club, Position.ATT, new Random(42L));
        assertThat(a.getOverall()).isEqualTo(b.getOverall());
        assertThat(a.getPotential()).isEqualTo(b.getPotential());
    }
}
