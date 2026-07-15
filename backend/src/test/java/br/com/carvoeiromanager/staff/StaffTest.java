package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffTest {

    @Test
    void createBuildsAStaffMemberWithEveryFieldSet() {
        UUID saveGameId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();

        Staff s = Staff.create(saveGameId, clubId, StaffRole.PREPARADOR_FISICO,
                StaffQuality.BOM, "Carlos Andrade", new BigDecimal("45000.00"));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getSaveGameId()).isEqualTo(saveGameId);
        assertThat(s.getClubId()).isEqualTo(clubId);
        assertThat(s.getRole()).isEqualTo(StaffRole.PREPARADOR_FISICO);
        assertThat(s.getQuality()).isEqualTo(StaffQuality.BOM);
        assertThat(s.getName()).isEqualTo("Carlos Andrade");
        assertThat(s.getWage()).isEqualByComparingTo("45000.00");
    }

    @Test
    void twoStaffCreatedSeparatelyHaveDifferentIds() {
        Staff a = Staff.create(UUID.randomUUID(), UUID.randomUUID(), StaffRole.OLHEIRO,
                StaffQuality.REGULAR, "A", BigDecimal.TEN);
        Staff b = Staff.create(UUID.randomUUID(), UUID.randomUUID(), StaffRole.OLHEIRO,
                StaffQuality.REGULAR, "B", BigDecimal.TEN);

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}
