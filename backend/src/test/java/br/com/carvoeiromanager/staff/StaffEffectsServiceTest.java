package br.com.carvoeiromanager.staff;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class StaffEffectsServiceTest {

    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final StaffEffectsService effects = new StaffEffectsService(staffRepository);

    private Staff staff(StaffRole role, StaffQuality quality) {
        return Staff.create(UUID.randomUUID(), UUID.randomUUID(), role, quality, "Fulano", BigDecimal.TEN);
    }

    @Test
    void trainingBonusIsZeroWhenManagedClubHasNoCoachHired() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.empty());

        assertThat(effects.trainingBonusFor(clubId, true)).isZero();
    }

    @Test
    void trainingBonusMatchesTheHiredCoachsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(staff(StaffRole.PREPARADOR_FISICO, StaffQuality.EXCELENTE)));

        assertThat(effects.trainingBonusFor(clubId, true)).isEqualTo(2);
    }

    @Test
    void trainingBonusIsTheFixedAiBaselineForANonManagedClubRegardlessOfRepository() {
        UUID clubId = UUID.randomUUID();

        assertThat(effects.trainingBonusFor(clubId, false)).isEqualTo(1);
        Mockito.verifyNoInteractions(staffRepository);
    }

    @Test
    void injuryRiskMultiplierMatchesTheHiredPhysiosQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staff(StaffRole.FISIOTERAPEUTA, StaffQuality.BOM)));

        assertThat(effects.injuryRiskMultiplierFor(clubId, true)).isEqualTo(0.85);
    }

    @Test
    void injuryRiskMultiplierIsNeutralWhenManagedClubHasNoPhysioHired() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        assertThat(effects.injuryRiskMultiplierFor(clubId, true)).isEqualTo(1.0);
    }

    @Test
    void injuryRiskMultiplierIsTheFixedAiBaselineForANonManagedClub() {
        assertThat(effects.injuryRiskMultiplierFor(UUID.randomUUID(), false)).isEqualTo(0.85);
    }

    @Test
    void wageDemandMultiplierMatchesTheHiredScoutsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.OLHEIRO))
                .thenReturn(Optional.of(staff(StaffRole.OLHEIRO, StaffQuality.EXCELENTE)));

        assertThat(effects.wageDemandMultiplierFor(clubId, true)).isEqualTo(0.9);
    }

    @Test
    void sponsorshipMultiplierMatchesTheHiredAssistantsQualityTier() {
        UUID clubId = UUID.randomUUID();
        when(staffRepository.findByClubIdAndRole(clubId, StaffRole.ASSISTENTE))
                .thenReturn(Optional.of(staff(StaffRole.ASSISTENTE, StaffQuality.BOM)));

        assertThat(effects.sponsorshipMultiplierFor(clubId, true)).isEqualTo(1.05);
    }

    @Test
    void sponsorshipMultiplierIsTheFixedAiBaselineForANonManagedClub() {
        assertThat(effects.sponsorshipMultiplierFor(UUID.randomUUID(), false)).isEqualTo(1.05);
    }
}
