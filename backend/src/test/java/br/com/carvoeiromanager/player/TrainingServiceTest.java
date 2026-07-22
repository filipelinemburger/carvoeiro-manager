package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class TrainingServiceTest {

    // 2026-03-16 is the Monday of ISO week 12/2026 → week int 202612; +1 week → 202613.
    private static final LocalDate WEEK_12 = LocalDate.of(2026, 3, 16);
    private static final LocalDate WEEK_13 = LocalDate.of(2026, 3, 23);

    private final StaffEffectsService staffEffectsService = Mockito.mock(StaffEffectsService.class);
    private final SaveGameRepository saveGameRepository = Mockito.mock(SaveGameRepository.class);
    private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final TrainingService training = new TrainingService(new OverallCalculator(),
            staffEffectsService, saveGameRepository, playerRepository, staffRepository);

    // Pinned MSB so the weekly RNG is reproducible across runs: new Random(1L ^ 202612) and
    // new Random(1L ^ 202613) both open with nextDouble() == 0.588..., far above every weekly
    // injury risk — no test here can flake on a surprise injury entry. (The injury test below
    // pins its own id the opposite way.)
    private final UUID saveGameId = new UUID(1L, 0L);
    private final UUID managedClubId = UUID.randomUUID();
    private final UUID aiClubId = UUID.randomUUID();

    private SaveGame stubSave(UUID id) {
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(id)).thenReturn(Optional.of(save));
        return save;
    }

    private Player managedPlayer(int attr) {
        return Player.create(saveGameId, managedClubId, "Fulano", Position.MID,
                24, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    private void stubPlayers(Player... players) {
        Mockito.when(playerRepository.findBySaveGameId(saveGameId)).thenReturn(List.of(players));
    }

    private void hirePhysio(StaffQuality quality) {
        Mockito.when(staffRepository.findByClubIdAndRole(managedClubId, StaffRole.PREPARADOR_FISICO))
                .thenReturn(Optional.of(Staff.create(saveGameId, managedClubId,
                        StaffRole.PREPARADOR_FISICO, quality, "Preparador Teste", new BigDecimal("45000"))));
    }

    // ---- weekly guard ----

    @Test
    void theSameWeekIsNeverProcessedTwice() {
        // Given a save already ticked for week 202612 by a first call
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(p);
        training.processWeek(saveGameId, WEEK_12);
        int progressAfterFirst = p.getTrainingProgress();

        // When called again with a date in the same ISO week (Friday of week 12)
        TrainingWeekReport second = training.processWeek(saveGameId, LocalDate.of(2026, 3, 20));

        // Then nothing happens: no report, no extra progress
        assertThat(second).isNull();
        assertThat(p.getTrainingProgress()).isEqualTo(progressAfterFirst);
    }

    @Test
    void aNewWeekTicksAndAdvancesTheGuard() {
        SaveGame save = stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);
        training.processWeek(saveGameId, WEEK_13);

        // NORMAL (+2/week, no staff bonus — unstubbed mock int returns 0) over two weeks = 4
        assertThat(p.getTrainingProgress()).isEqualTo(4);
        assertThat(save.getLastTrainingWeek()).isEqualTo(202613);
    }

    // ---- progress accumulation & ticks ----

    @Test
    void fortyProgressUnitsBecomeOnePointOnTheFocusAttribute() {
        // Given a player one LEVE week (+1) away from a full point
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then Finalização ticked +1 and the meter emptied; other anchors untouched
        assertThat(p.getFinishing()).isEqualTo(51);
        assertThat(p.getTrainingProgress()).isZero();
        assertThat(p.getPace()).isEqualTo(50);
        assertThat(p.getPassing()).isEqualTo(50);
    }

    @Test
    void theRemainderCarriesOverAfterATick() {
        // Given a NORMAL (+2) player at 39/40
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then 39+2=41 → one tick fires, 1 unit remains
        assertThat(p.getPassing()).isEqualTo(51);
        assertThat(p.getTrainingProgress()).isEqualTo(1);
    }

    @Test
    void theFocusAttributeIsClampedAtNinetyNine() {
        // Given a maxed player whose meter is about to fire
        stubSave(saveGameId);
        Player p = managedPlayer(99);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        // Then the tick is consumed but the attribute stays capped
        assertThat(p.getFinishing()).isEqualTo(99);
        assertThat(p.getTrainingProgress()).isEqualTo(1);
    }

    @Test
    void overallIsRecomputedWhenATickFires() {
        stubSave(saveGameId);
        // Stamina bumped to 53 (vs. the other flat-50 anchors) so the ATT weighting
        // (finishing .40/pace .25/passing .15/stamina .10/tackling .10) crosses a whole-number
        // boundary on the +1 Finalização tick: weighted 50.3 -> 50.7, round 50 -> 51. With every
        // anchor flat at 50 the +0.4 swing from a single tick (50.0 -> 50.4) never crosses a
        // rounding boundary, so getOverall() would incorrectly appear unchanged.
        Player p = Player.create(saveGameId, managedClubId, "Fulano", Position.ATT,
                24, "Brasil", 50, 50, 50, 50, 50, 53, 50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);
        int overallBefore = p.getOverall();

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getOverall()).isGreaterThan(overallBefore);
    }

    // ---- who trains ----

    @Test
    void aManagedPlayerWithNoFocusIsUntouched() {
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getTrainingProgress()).isZero();
        assertThat(p.getPassing()).isEqualTo(50);
    }

    @Test
    void freeAgentsAndYouthProspectsAreSkipped() {
        stubSave(saveGameId);
        Player freeAgent = Player.create(saveGameId, null, "Livre", Position.MID,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        Player prospect = managedPlayer(50);
        prospect.markAsProspect(80);
        prospect.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.NORMAL);
        stubPlayers(freeAgent, prospect);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(freeAgent.getTrainingProgress()).isZero();
        assertThat(prospect.getTrainingProgress()).isZero();
    }

    @Test
    void aiClubPlayersTrainWithTheHeuristicFocusAtNormal() {
        // Given an AI-club attacker and the AI baseline coach bonus (+1)
        stubSave(saveGameId);
        Mockito.when(staffEffectsService.trainingBonusFor(any(), eq(false))).thenReturn(1);
        Player ai = Player.create(saveGameId, aiClubId, "Adversário", Position.ATT,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        stubPlayers(ai);

        training.processWeek(saveGameId, WEEK_12);

        // Then NORMAL (+2) + baseline (+1) accumulated toward FINALIZACAO (the ATT heuristic)
        assertThat(ai.getTrainingProgress()).isEqualTo(3);
    }

    @Test
    void theCoachBonusAcceleratesTheManagedWeeklyRate() {
        // Given an Excelente coach (+2) on top of LEVE (+1)
        stubSave(saveGameId);
        Mockito.when(staffEffectsService.trainingBonusFor(managedClubId, true)).thenReturn(2);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.LEVE);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getTrainingProgress()).isEqualTo(3);
    }

    // ---- weekly injury roll ----

    @Test
    void leveNeverRollsATrainingInjury() {
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FOLEGO, TrainingIntensity.LEVE);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void intensaCanRollATrainingInjury() {
        // Given a save-game id hand-picked so the weekly RNG's first draw is under Intensa's
        // 0.002 weekly risk: new Random(9824L ^ 202612).nextDouble() == 0.000457...; the
        // following nextInt(2) draws 0, so the layoff is exactly 1 match.
        UUID pickedId = new UUID(9824L, 0L);
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(pickedId)).thenReturn(Optional.of(save));
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FOLEGO, TrainingIntensity.INTENSA);
        Mockito.when(playerRepository.findBySaveGameId(pickedId)).thenReturn(List.of(p));

        training.processWeek(pickedId, WEEK_12);

        assertThat(p.getInjuryMatches()).isEqualTo(1);
    }

    // ---- the report ----

    @Test
    void noPhysioMeansTrainingStillRunsButNoReport() {
        // Given an evolving player and NO Preparador Físico (mock Optional default = empty)
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNull();
        assertThat(p.getPassing()).isEqualTo(51); // the tick still happened
    }

    @Test
    void aQuietWeekProducesNoReportEvenWithAPhysio() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.EXCELENTE);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE); // 0+1=1 unit: nothing notable
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNull();
    }

    @Test
    void regularPhysioReportsAnEvolutionVaguely() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.REGULAR);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report).isNotNull();
        assertThat(report.staffName()).isEqualTo("Preparador Teste");
        assertThat(report.quality()).isEqualTo("REGULAR");
        assertThat(report.entries()).singleElement().satisfies(e -> {
            assertThat(e.playerName()).isEqualTo("Fulano");
            assertThat(e.message()).isEqualTo("Fulano evoluiu nos treinos.");
        });
    }

    @Test
    void bomPhysioNamesTheAttributeAndExcelenteAddsTheValues() {
        stubSave(saveGameId);
        Player bomPlayer = managedPlayer(50);
        bomPlayer.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        bomPlayer.setTrainingProgress(39);
        stubPlayers(bomPlayer);
        hirePhysio(StaffQuality.BOM);

        TrainingWeekReport bom = training.processWeek(saveGameId, WEEK_12);
        assertThat(bom.entries().get(0).message()).isEqualTo("Fulano evoluiu em Passe.");

        // Fresh scenario for EXCELENTE (new save id → fresh guard)
        UUID otherId = UUID.randomUUID();
        SaveGame other = SaveGame.create(UUID.randomUUID(), 2026);
        other.setManagedClubId(managedClubId);
        Mockito.when(saveGameRepository.findById(otherId)).thenReturn(Optional.of(other));
        Player excPlayer = managedPlayer(50);
        excPlayer.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        excPlayer.setTrainingProgress(39);
        Mockito.when(playerRepository.findBySaveGameId(otherId)).thenReturn(List.of(excPlayer));
        hirePhysio(StaffQuality.EXCELENTE);

        TrainingWeekReport exc = training.processWeek(otherId, WEEK_12);
        assertThat(exc.entries().get(0).message()).isEqualTo("Fulano evoluiu em Passe (50 → 51).");
    }

    @Test
    void aCeilingTickSuggestsSwappingTheFocus() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.BOM);
        Player p = managedPlayer(99);
        p.setTrainingAssignment(Attribute.VELOCIDADE, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo(
                        "Fulano não tem mais o que evoluir em Velocidade — considere trocar o foco."));
    }

    @Test
    void intensaOnAVeteranEmitsARiskWarning() {
        stubSave(saveGameId);
        hirePhysio(StaffQuality.BOM);
        Player veteran = Player.create(saveGameId, managedClubId, "Veterano", Position.DEF,
                32, "Brasil", 50, 50, 50, 50, 50, 50, 50);
        veteran.setTrainingAssignment(Attribute.DESARME, TrainingIntensity.INTENSA);
        stubPlayers(veteran);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo(
                        "Treino intenso para Veterano (32 anos) traz alto risco de lesão."));
    }

    @Test
    void excelenteAddsNearEvolutionLines() {
        // Given progress that lands at 20/40 (half a point) with no tick this week
        stubSave(saveGameId);
        hirePhysio(StaffQuality.EXCELENTE);
        Player p = managedPlayer(50);
        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.NORMAL);
        p.setTrainingProgress(18); // 18 + 2 = 20
        stubPlayers(p);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).singleElement().satisfies(e ->
                assertThat(e.message()).isEqualTo("Fulano está perto de evoluir Finalização."));
    }

    @Test
    void entriesAreCappedByQualityInPriorityOrder() {
        // Given a REGULAR physio (cap 3) and four players all evolving this week
        stubSave(saveGameId);
        hirePhysio(StaffQuality.REGULAR);
        Player[] four = new Player[4];
        for (int i = 0; i < 4; i++) {
            four[i] = Player.create(saveGameId, managedClubId, "Atleta" + i, Position.MID,
                    24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
            four[i].setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
            four[i].setTrainingProgress(39);
        }
        stubPlayers(four);

        TrainingWeekReport report = training.processWeek(saveGameId, WEEK_12);

        assertThat(report.entries()).hasSize(3);
        assertThat(report.entries()).allSatisfy(e ->
                assertThat(e.message()).endsWith("evoluiu nos treinos."));
    }

    // ---- the six new (non-anchor) focuses ----

    @Test
    void aNewFocusAttributeTicksUpWithOverallUnchanged() {
        // Given a player training CABECEIO (one of the 6 new focuses, not an overall anchor)
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setAttribute(Attribute.CABECEIO, 50);
        p.setTrainingAssignment(Attribute.CABECEIO, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);
        int overallBefore = p.getOverall();

        training.processWeek(saveGameId, WEEK_12);

        // Then Cabeceio ticked +1 but overall — anchor-based by design — did not move
        assertThat(p.attribute(Attribute.CABECEIO)).isEqualTo(51);
        assertThat(p.getOverall()).isEqualTo(overallBefore);
        // And the legacy anchors carried over untouched
        assertThat(p.getPace()).isEqualTo(50);
        assertThat(p.getFinishing()).isEqualTo(50);
    }

    @Test
    void aNewFocusAttributeIsAlsoClampedAtNinetyNine() {
        stubSave(saveGameId);
        Player p = managedPlayer(50);
        p.setAttribute(Attribute.FORCA, 99);
        p.setTrainingAssignment(Attribute.FORCA, TrainingIntensity.NORMAL);
        p.setTrainingProgress(39);
        stubPlayers(p);

        training.processWeek(saveGameId, WEEK_12);

        assertThat(p.attribute(Attribute.FORCA)).isEqualTo(99);
        assertThat(p.getTrainingProgress()).isEqualTo(1);
    }

    // ---- heuristic (unchanged) ----

    @Test
    void heuristicFocusMapsEachPositionToItsMostValuableAnchor() {
        assertThat(TrainingService.heuristicFocusFor(Position.GK)).isEqualTo(Attribute.REFLEXOS);
        assertThat(TrainingService.heuristicFocusFor(Position.DEF)).isEqualTo(Attribute.DESARME);
        assertThat(TrainingService.heuristicFocusFor(Position.MID)).isEqualTo(Attribute.PASSE);
        assertThat(TrainingService.heuristicFocusFor(Position.ATT)).isEqualTo(Attribute.FINALIZACAO);
    }
}
