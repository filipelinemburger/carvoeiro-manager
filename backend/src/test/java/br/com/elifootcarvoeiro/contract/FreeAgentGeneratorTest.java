package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.player.OverallCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FreeAgentGeneratorTest {

    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final FreeAgentGenerator generator = new FreeAgentGenerator(new SeedLoader(), overallCalculator);

    @Test
    void generatesAClublessPlayerWithNoContract() {
        UUID saveGameId = UUID.randomUUID();
        Player p = generator.generate(saveGameId, new Random(7));

        assertThat(p.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(p.getClubId()).isNull();
        assertThat(p.isFreeAgent()).isTrue();
        assertThat(p.getContractYears()).isZero();
        assertThat(p.getWage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.getAge()).isBetween(22, 34);
        assertThat(p.getName()).contains(" ");
        assertThat(p.getOverall()).isEqualTo(overallCalculator.compute(p.getPosition(),
                p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina()));
    }

    @Test
    void isDeterministicForTheSameSeed() {
        UUID id = UUID.randomUUID();
        Player a = generator.generate(id, new Random(42));
        Player b = generator.generate(id, new Random(42));
        assertThat(a.getName()).isEqualTo(b.getName());
        assertThat(a.getPosition()).isEqualTo(b.getPosition());
        assertThat(a.getAge()).isEqualTo(b.getAge());
        assertThat(a.getOverall()).isEqualTo(b.getOverall());
    }

    @Test
    void attributesStayInRange() {
        Player p = generator.generate(UUID.randomUUID(), new Random(1));
        assertThat(p.getPace()).isBetween(1, 99);
        assertThat(p.getFinishing()).isBetween(1, 99);
        assertThat(p.getGoalkeeping()).isBetween(1, 99);
    }
}
