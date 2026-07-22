package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaffEffectsServiceTest {

    private final UUID CLUB_ID = UUID.randomUUID();
    private final UUID SAVE_ID = UUID.randomUUID();

    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final StaffEffectsService service = new StaffEffectsService(staffRepository);

    private Staff staffWith(StaffRole role, int primary, int secondary) {
        return Staff.create(SAVE_ID, CLUB_ID, role, StaffQuality.forAverage((primary + secondary) / 2.0),
                "Profissional Teste", new BigDecimal("45000.00"), primary, secondary);
    }

    @Test
    void anchorAverageTenReproducesRegularValues() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staffWith(StaffRole.ASSISTENTE, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staffWith(StaffRole.PREPARADOR_FISICO, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 10, 10)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staffWith(StaffRole.OLHEIRO, 10, 10)));

        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(0);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }

    @Test
    void anchorAverageSeventeenReproducesExcelenteValues() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staffWith(StaffRole.ASSISTENTE, 17, 17)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staffWith(StaffRole.PREPARADOR_FISICO, 17, 17)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 17, 17)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staffWith(StaffRole.OLHEIRO, 17, 17)));

        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isCloseTo(1.10, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(2);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isCloseTo(0.70, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isCloseTo(0.90, within(1e-9));
    }

    @Test
    void eliteAveragesAreClampedAtTheCeilings() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staffWith(StaffRole.ASSISTENTE, 19, 19)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staffWith(StaffRole.PREPARADOR_FISICO, 19, 19)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 19, 19)));
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staffWith(StaffRole.OLHEIRO, 19, 19)));

        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isCloseTo(1.12, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(2);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isCloseTo(0.65, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isCloseTo(0.88, within(1e-9));
    }

    @Test
    void aiClubsUseTheFixedBaselineAverageFourteen() {
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 + 0.10 * 4 / 7.0, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.30 * 4 / 7.0, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.10 * 4 / 7.0, within(1e-9));
        verifyNoInteractions(staffRepository);
    }

    @Test
    void managedClubWithoutTheRoleGetsNeutralEffects() {
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(0);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }
}
