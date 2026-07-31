package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherEngineIntegrationTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    private static TeamMatchInput team(GameStyle style) {
        return new TeamMatchInput(72.0, 70.0, 1.0, 1.0, 0.5, 0.5, 0.8, 0.20,
                Mentality.BALANCED, style);
    }

    @Test
    void neutralWeatherIsByteIdenticalAcrossManySeeds() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch sunny = engine.simulate(home, away,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed);
            SimulatedMatch neutral = engine.simulate(home, away, Weather.neutral(), seed);

            assertThat(sunny.homeGoals()).isEqualTo(neutral.homeGoals());
            assertThat(sunny.awayGoals()).isEqualTo(neutral.awayGoals());
        }
    }

    @Test
    void heavyRainLowersTotalGoalsAcrossManyMatches() {
        TeamMatchInput home = team(GameStyle.EQUILIBRADO);
        TeamMatchInput away = team(GameStyle.EQUILIBRADO);

        int dry = 0;
        int wet = 0;
        for (long seed = 0; seed < 3000; seed++) {
            SimulatedMatch d = engine.simulate(home, away, Weather.neutral(), seed);
            SimulatedMatch w = engine.simulate(home, away,
                    new Weather(WeatherCondition.CHUVA_FORTE, 20), seed);
            dry += d.homeGoals() + d.awayGoals();
            wet += w.homeGoals() + w.awayGoals();
        }
        assertThat(wet).isLessThan(dry);
    }

    @Test
    void aHotDayPunishesAHighPressingSide() {
        TeamMatchInput pressing = team(GameStyle.PRESSAO_ALTA);
        TeamMatchInput balanced = team(GameStyle.EQUILIBRADO);

        int mildPressingGoals = 0;
        int hotPressingGoals = 0;
        for (long seed = 0; seed < 3000; seed++) {
            mildPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 20), seed).homeGoals();
            hotPressingGoals += engine.simulate(pressing, balanced,
                    new Weather(WeatherCondition.ENSOLARADO, 36), seed).homeGoals();
        }
        assertThat(hotPressingGoals).isLessThan(mildPressingGoals);
    }

    // ---- LiveMatchEngine: the weather response must track a live mid-match game-style change ----

    private final StaffEffectsService staffEffectsService = Mockito.mock(StaffEffectsService.class);
    private final LiveMatchEngine liveEngine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staffEffectsService);

    {
        Mockito.when(staffEffectsService.injuryRiskMultiplierFor(
                ArgumentMatchers.any(), ArgumentMatchers.anyBoolean())).thenReturn(1.0);
    }

    private static Player livePlayer(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 70);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all rated 70 — same shape as LiveMatchEngineTest's buildXi(). */
    private static List<Player> liveXi() {
        return new ArrayList<>(List.of(
                livePlayer(Position.GK),
                livePlayer(Position.DEF), livePlayer(Position.DEF), livePlayer(Position.DEF), livePlayer(Position.DEF),
                livePlayer(Position.MID), livePlayer(Position.MID), livePlayer(Position.MID), livePlayer(Position.MID),
                livePlayer(Position.ATT), livePlayer(Position.ATT)));
    }

    private static TacticalProfile liveProfile(GameStyle style, Map<UUID, RoleAssignment> roles) {
        return new TacticalProfile(Mentality.BALANCED, style, AttackZone.MISTO, "4-4-2",
                Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());
    }

    private static int secondHalfHomeGoals(LiveTimeline t) {
        return (int) t.events().stream()
                .filter(e -> e.home() && e.type().isGoal() && e.minute() > 45)
                .count();
    }

    @Test
    void switchingToJogoDiretoMidMatchInHeavyRainEarnsTheDirectBonusInsteadOfKeepingThePassingPenalty() {
        // Regression for the bug where LiveMatchEngine resolved weatherImpact exactly ONCE at
        // kickoff and never revisited it, even though a manager can change gameStyle mid-match via
        // a tactics change. Under CHUVA_FORTE (wetness 1.0), WeatherEffects.styleResponse gives
        // JOGO_DE_PASSES a homeChanceMult of 0.93*(1-0.06) = 0.8742 but JOGO_DIRETO
        // 0.93*(1+0.04) = 0.9672 — a direct ~10.6% swing on the side's OWN per-minute scoring
        // chance (not a compounding fatigue term), so it is far more statistically visible than the
        // decay path. A side that kicks off JOGO_DE_PASSES and switches to JOGO_DIRETO at minute 46
        // must, from that minute on, earn the direct-play bonus like a side that played JOGO_DIRETO
        // all along — not keep paying the passing-game penalty for adapting to the weather.
        //
        // "switched" and "alwaysDireto" share the identical XI, identical JOGO_DIRETO fit (same
        // players -> same TeamStrengthCalculator attack/defense terms) and identical weather from
        // minute 46 onward — the only thing that can differ between them post-switch is whether
        // weatherImpact was actually recomputed. So, fixed, their second-half scoring must land in
        // the same ballpark. Pre-fix, "switched" keeps JOGO_DE_PASSES's locked-in rain PENALTY for
        // the whole match and must meaningfully UNDERSCORE "alwaysDireto" in the second half — this
        // assertion catches that regression (verified empirically: this test passes on the fixed
        // engine and fails when LiveMatchEngine's dirty-triggered weatherImpact recompute is
        // reverted).
        List<Player> homeXi = liveXi();
        List<Player> awayXi = liveXi();
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(p -> fitness.put(p.getId(), 100));
        awayXi.forEach(p -> fitness.put(p.getId(), 100));

        Map<UUID, RoleAssignment> homeRoles = LegacyRoles.forXi(homeXi);
        TacticalProfile passesProfile = liveProfile(GameStyle.JOGO_DE_PASSES, homeRoles);
        TacticalProfile diretoProfile = liveProfile(GameStyle.JOGO_DIRETO, homeRoles);
        TacticalProfile awayProfile = liveProfile(GameStyle.EQUILIBRADO, LegacyRoles.forXi(awayXi));

        Weather heavyRain = new Weather(WeatherCondition.CHUVA_FORTE, 20);
        TimedTactics switchToDireto = new TimedTactics(46, true, Mentality.BALANCED,
                GameStyle.JOGO_DIRETO, null, Pressing.MEDIUM, Marking.ZONAL, "4-4-2");

        int switchedSecondHalfGoals = 0;
        int alwaysDiretoSecondHalfGoals = 0;
        int seeds = 1500;
        for (long seed = 0; seed < seeds; seed++) {
            LiveMatchConfig switchCfg = new LiveMatchConfig(seed, homeXi, awayXi, List.of(), List.of(),
                    passesProfile, awayProfile, fitness, List.of(), List.of(switchToDireto), null, heavyRain);
            LiveMatchConfig alwaysCfg = new LiveMatchConfig(seed, homeXi, awayXi, List.of(), List.of(),
                    diretoProfile, awayProfile, fitness, List.of(), List.of(), null, heavyRain);

            switchedSecondHalfGoals += secondHalfHomeGoals(liveEngine.simulate(switchCfg));
            alwaysDiretoSecondHalfGoals += secondHalfHomeGoals(liveEngine.simulate(alwaysCfg));
        }

        // Pre-fix, "switched" would trail "alwaysDireto" by roughly the 0.8742/0.9672 chance-mult
        // ratio (~10%); fixed, the two must land within noise of each other. A generous one-sided
        // 8% tolerance below alwaysDireto still comfortably separates "recomputed" from "locked".
        assertThat(switchedSecondHalfGoals)
                .as("switching to JOGO_DIRETO mid-match must earn the rain bonus from minute 46 on: "
                        + "switched=%d alwaysDireto=%d", switchedSecondHalfGoals, alwaysDiretoSecondHalfGoals)
                .isGreaterThanOrEqualTo(alwaysDiretoSecondHalfGoals
                        - (int) Math.round(alwaysDiretoSecondHalfGoals * 0.08));
    }
}
