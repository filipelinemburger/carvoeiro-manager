package br.com.carvoeiromanager.transfer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWindowDateTest {

    private final TransferWindow window = new TransferWindow();

    @Test
    void preSeasonWindowIsOpenForTheFirst42Days() {
        assertThat(window.isOpen(LocalDate.of(2026, 1, 15))).isTrue();  // start
        assertThat(window.isOpen(LocalDate.of(2026, 2, 25))).isTrue();  // +41d
        assertThat(window.isOpen(LocalDate.of(2026, 2, 26))).isFalse(); // +42d -> closed
    }

    @Test
    void midSeasonWindowIsOpenInItsRange() {
        // SEASON_START = 2026-01-15; mid window [+140, +168) = [2026-06-04, 2026-07-02)
        assertThat(window.isOpen(LocalDate.of(2026, 6, 4))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 1))).isTrue();
        assertThat(window.isOpen(LocalDate.of(2026, 7, 2))).isFalse();
        assertThat(window.isOpen(LocalDate.of(2026, 5, 1))).isFalse(); // between windows
    }
}
