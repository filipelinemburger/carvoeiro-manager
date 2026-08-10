package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StaffQualityTest {

    @Test
    void eachTierCarriesItsMonthlyWage() {
        assertThat(StaffQuality.REGULAR.monthlyWage()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(StaffQuality.BOM.monthlyWage()).isEqualByComparingTo(new BigDecimal("45000.00"));
        assertThat(StaffQuality.EXCELENTE.monthlyWage()).isEqualByComparingTo(new BigDecimal("80000.00"));
    }

    @Test
    void tecnicoIsNotHirable() {
        assertThat(StaffRole.HIRABLE).doesNotContain(StaffRole.TECNICO);
        assertThat(StaffRole.HIRABLE).containsExactlyInAnyOrder(
                StaffRole.ASSISTENTE, StaffRole.PREPARADOR_FISICO,
                StaffRole.OLHEIRO, StaffRole.FISIOTERAPEUTA);
    }

    @Test
    void tecnicoSortsFirstSoItLeadsTheCommissionListing() {
        assertThat(StaffRole.TECNICO.ordinal()).isZero();
    }
}
