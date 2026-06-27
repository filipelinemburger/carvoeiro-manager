package br.com.elifootcarvoeiro.transfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowTest {

    private final TransferWindow window = new TransferWindow();

    @Test
    void preSeasonWindowIsOpenForTheFirstThreeRounds() {
        // 18-round season
        assertThat(window.isOpen(0, 18)).isTrue();
        assertThat(window.isOpen(2, 18)).isTrue();
        assertThat(window.isOpen(3, 18)).isFalse();
        assertThat(window.isOpen(8, 18)).isFalse();
    }

    @Test
    void midSeasonWindowIsOpenAroundTheHalfwayPoint() {
        // totalRounds/2 = 9; open [9, 12)
        assertThat(window.isOpen(9, 18)).isTrue();
        assertThat(window.isOpen(11, 18)).isTrue();
        assertThat(window.isOpen(12, 18)).isFalse();
        assertThat(window.isOpen(17, 18)).isFalse();
    }
}
