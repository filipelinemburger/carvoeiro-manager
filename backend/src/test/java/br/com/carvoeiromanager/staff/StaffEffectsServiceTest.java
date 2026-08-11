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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffEffectsServiceTest {

    private final UUID CLUB_ID = UUID.randomUUID();
    private final UUID SAVE_ID = UUID.randomUUID();

    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final StaffVacancyRepository staffVacancyRepository =
            Mockito.mock(StaffVacancyRepository.class);
    private final StaffEffectsService service =
            new StaffEffectsService(staffRepository, staffVacancyRepository);

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
    void aiClubsWithAnEmptySlotFallBackToTheFixedBaselineAverageFourteen() {
        // The row is still read — an AI club is no longer skipped outright — it is just empty.
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());

        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 + 0.10 * 4 / 7.0, within(1e-9));
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.30 * 4 / 7.0, within(1e-9));
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, false)).isCloseTo(1.0 - 0.10 * 4 / 7.0, within(1e-9));
        verify(staffRepository, atLeastOnce()).findByClubIdAndRole(eq(CLUB_ID), any());
    }

    @Test
    void anAiClubWithRealStaffUsesThoseAttributesInsteadOfTheBaseline() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 18, 18)));

        // The whole point of the slice: a researched physio beats the flat 14.0 baseline (~0.886).
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isLessThan(0.80);
    }

    @Test
    void anAiClubWithAnEmptySlotFallsBackToTheBaseline() {
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        // An empty slot on an AI club records a research gap, not a club without a physio —
        // so it must behave exactly as it did before this slice (design §3.2a). Pinned to the
        // exact double the production lerp() has always produced for average 14.0 (one ULP off
        // the brief's "1.0 - 0.3 * (4.0/7.0)" due to floating-point operation ordering).
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isEqualTo(0.8285714285714285);
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);
    }

    @Test
    void theManagedClubWithAnEmptySlotStaysNeutral() {
        when(staffRepository.findByClubIdAndRole(any(), any())).thenReturn(Optional.empty());

        // At your own club an empty slot IS a fact — you have not hired anyone — and hiring is
        // the mechanic that fixes it. No floor here.
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isZero();
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }

    @Test
    void managedClubWithoutTheRoleGetsNeutralEffects() {
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isEqualTo(0);
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
    }

    @Test
    void aSlotTheManagerEmptiedByPoachingIsNeutralInsteadOfFallingBackToTheBaseline() {
        // Given an AI club with no row for any role, but a vacancy on record: the player poached
        // the holder out of it.
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        when(staffVacancyRepository.existsByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(true);

        // Then every one of the four managed-flag effects reads neutral. The baseline is a stand-in
        // for research we never did; a vacancy is research we have — the slot really is empty.
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isZero();
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, false)).isEqualTo(1.0);
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, false)).isEqualTo(1.0);
    }

    @Test
    void aVacancyNeverImprovesTheClubItWasTakenFrom() {
        // Given a physio whose 12/11 average of 11.5 is well below the 14.0 baseline
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 12, 11)));
        double whileEmployed = service.injuryRiskMultiplierFor(CLUB_ID, false);

        // When they are poached away — the row goes, a vacancy replaces it
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.empty());
        when(staffVacancyRepository.existsByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(true);

        // Then the robbed club is worse off, never better. Lower is better for injuries, so the
        // multiplier must not fall. Without the vacancy this lands on 0.8286 — BELOW the 0.9357 they
        // had while employing him, i.e. fewer injuries as a reward for being robbed.
        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false))
                .isGreaterThanOrEqualTo(whileEmployed)
                .isEqualTo(1.0);
    }

    @Test
    void aNeverResearchedSlotStillGetsTheBaselineEvenThoughOtherSlotsWereVacated() {
        // Guards against over-correcting: the vacancy lookup is per (club, role), so emptying one
        // slot must not quietly delete the baseline from the club's other, merely unresearched ones.
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        when(staffVacancyRepository.existsByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(true);

        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, false)).isEqualTo(1);          // still 14.0
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, false))
                .isCloseTo(1.0 - 0.10 * 4 / 7.0, within(1e-9));                     // still 14.0
    }

    @Test
    void aRealStaffRowOutranksAVacancyLeftOverForTheSameSlot() {
        // Resolution order, top step first: if the club employs someone for the role, nothing else
        // is consulted — a stale vacancy row cannot suppress a real hire.
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(Optional.of(staffWith(StaffRole.FISIOTERAPEUTA, 17, 17)));
        when(staffVacancyRepository.existsByClubIdAndRole(CLUB_ID, StaffRole.FISIOTERAPEUTA))
                .thenReturn(true);

        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, false)).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void theManagedClubIsNeutralWithOrWithoutAVacancy() {
        // The managed club short-circuits to neutral before the vacancy step, so a vacancy row can
        // neither change its effects nor is it ever worth a query.
        when(staffRepository.findByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(Optional.empty());
        when(staffVacancyRepository.existsByClubIdAndRole(eq(CLUB_ID), any())).thenReturn(true);

        assertThat(service.injuryRiskMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.trainingBonusFor(CLUB_ID, true)).isZero();
        assertThat(service.wageDemandMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        assertThat(service.sponsorshipMultiplierFor(CLUB_ID, true)).isEqualTo(1.0);
        verify(staffVacancyRepository, never()).existsByClubIdAndRole(any(), any());
    }

    @Test
    void aClubWithNoHeadCoachIsExactlyNeutral() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.empty());

        // The managed club always lands here: it never has a TECNICO row, because the player
        // is the head coach and their decisions are already the engine's input.
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(1.0);
    }

    @Test
    void theCoachMultiplierSpansTheTierAnchorsAndClamps() {
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 10, 10)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isCloseTo(0.96, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 17, 17)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isCloseTo(1.04, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 20, 20)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isCloseTo(1.04, within(1e-9));

        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.of(staffWith(StaffRole.TECNICO, 1, 1)));
        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isCloseTo(0.96, within(1e-9));
    }

    @Test
    void theCoachMultiplierNeverConsultsVacancies() {
        // It reads rawAverageFor, which has no baseline to displace, so "empty" and "vacated" are
        // already the same neutral 1.0. Pinned so the vacancy lookup is never bolted on here by
        // symmetry with its four neighbours — that would be a query per club per fixture for a
        // value that cannot change.
        when(staffRepository.findByClubIdAndRole(CLUB_ID, StaffRole.TECNICO))
                .thenReturn(Optional.empty());

        assertThat(service.coachStrengthMultiplierFor(CLUB_ID)).isEqualTo(1.0);
        verify(staffVacancyRepository, never()).existsByClubIdAndRole(any(), any());
    }
}
