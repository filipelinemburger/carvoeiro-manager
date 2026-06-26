package br.com.elifootcarvoeiro.player;

import br.com.elifootcarvoeiro.seed.SeedLoader;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NewgenGeneratorTest {

    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final NewgenGenerator generator = new NewgenGenerator(new SeedLoader(), overallCalculator);

    @Test
    void generatesAYouthPlayerInTheRequestedClubAndPosition() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        Player p = generator.generate(saveGameId, clubId, Position.ATT, new Random(5));

        assertThat(p.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(p.getClubId()).isEqualTo(clubId);
        assertThat(p.getPosition()).isEqualTo(Position.ATT);
        assertThat(p.getAge()).isBetween(16, 19);
        assertThat(p.getNationality()).isEqualTo("Brasil");
        assertThat(p.getName()).contains(" ");
    }

    @Test
    void attributesStayInRangeAndOverallMatchesTheCalculator() {
        Player p = generator.generate(UUID.randomUUID(), UUID.randomUUID(), Position.GK, new Random(9));

        assertThat(p.getPace()).isBetween(1, 99);
        assertThat(p.getFinishing()).isBetween(1, 99);
        assertThat(p.getGoalkeeping()).isGreaterThanOrEqualTo(45); // GK band starts at 45
        assertThat(p.getOverall()).isEqualTo(overallCalculator.compute(Position.GK,
                p.getPace(), p.getFinishing(), p.getPassing(), p.getTackling(),
                p.getGoalkeeping(), p.getStamina()));
    }

    @Test
    void attackersGetAHighFinishingFloor() {
        Player p = generator.generate(UUID.randomUUID(), UUID.randomUUID(), Position.ATT, new Random(2));
        assertThat(p.getFinishing()).isGreaterThanOrEqualTo(45); // ATT finishing band starts at 45
    }

    @Test
    void generationIsDeterministicForAGivenSeed() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        Player a = generator.generate(saveGameId, clubId, Position.MID, new Random(42));
        Player b = generator.generate(saveGameId, clubId, Position.MID, new Random(42));

        assertThat(b.getName()).isEqualTo(a.getName());
        assertThat(b.getAge()).isEqualTo(a.getAge());
        assertThat(b.getPassing()).isEqualTo(a.getPassing());
        assertThat(b.getOverall()).isEqualTo(a.getOverall());
    }
}
