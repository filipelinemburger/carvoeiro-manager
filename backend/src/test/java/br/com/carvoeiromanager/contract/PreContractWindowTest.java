package br.com.carvoeiromanager.contract;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PreContractWindowTest {

    private final PreContractWindow window = new PreContractWindow();

    @Test
    void closedBeforeJulyFifteen() {
        assertThat(window.isOpen(LocalDate.of(2026, 1, 15))).isFalse();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 14))).isFalse();
    }

    @Test
    void openFromJulyFifteenToYearEnd() {
        assertThat(window.isOpen(LocalDate.of(2026, 7, 15))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 10, 3))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void nextSeasonStartsClosedAgain() {
        assertThat(window.isOpen(LocalDate.of(2027, 1, 15))).isFalse();
    }
}
