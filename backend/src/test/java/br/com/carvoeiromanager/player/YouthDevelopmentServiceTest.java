package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class YouthDevelopmentServiceTest {

    private final OverallCalculator overall = new OverallCalculator();
    private final YouthDevelopmentService dev = new YouthDevelopmentService(overall);

    private Player prospect(int potential) {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "P", Position.MID, 16, "Brasil",
                50, 50, 50, 50, 10, 50, ov);
        p.markAsProspect(potential);
        return p;
    }

    @Test
    void potentialStarsMapToBands() {
        assertThat(dev.potentialStars(90)).isEqualTo(5);
        assertThat(dev.potentialStars(85)).isEqualTo(5);
        assertThat(dev.potentialStars(80)).isEqualTo(4);
        assertThat(dev.potentialStars(78)).isEqualTo(4);
        assertThat(dev.potentialStars(77)).isEqualTo(3);
        assertThat(dev.potentialStars(72)).isEqualTo(3);
        assertThat(dev.potentialStars(65)).isEqualTo(2);
        assertThat(dev.potentialStars(62)).isEqualTo(2);
        assertThat(dev.potentialStars(61)).isEqualTo(1);
        assertThat(dev.potentialStars(50)).isEqualTo(1);
    }

    @Test
    void aProspectGrowsTowardPotentialButNeverPastIt() {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player q = prospect(ov + 25);
        int before = q.getOverall();
        dev.develop(q);
        assertThat(q.getOverall()).isGreaterThan(before);
        assertThat(q.getOverall()).isLessThanOrEqualTo(ov + 25);
    }

    @Test
    void aProspectAtItsCeilingDoesNotGrow() {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player q = prospect(ov); // potential == current overall
        int before = q.getOverall();
        dev.develop(q);
        assertThat(q.getOverall()).isEqualTo(before);
    }

    @Test
    void aProspectWithinOnePointOfItsCeilingDoesNotGrow() {
        int ov = overall.compute(Position.MID, 50, 50, 50, 50, 10, 50);
        Player q = prospect(ov + 1); // potential exactly one above current overall
        int before = q.getOverall();
        dev.develop(q);
        assertThat(q.getOverall()).isEqualTo(before);
    }
}
