package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiManagerBrainTest {

    // ---- decide(): the full posture matrix. Column order: identity, scoreDiff, minute, expected.
    // Thresholds: chasing base PUSH/ALL_OUT = 46/70 (losing 2+) and 60/80 (losing 1);
    // protecting base PROTECT/PARK = 75/85 (winning 1) and PROTECT 80 (winning 2+).
    // Offsets: chase ATTACKING -10 / DEFENSIVE +10; protect ATTACKING +5 / DEFENSIVE -10.
    // Every effective threshold clamps to minute >= 46.
    @ParameterizedTest
    @CsvSource({
            // first-half lockout: always NORMAL before 46, whatever the situation
            "BALANCED,   -2, 45, NORMAL",
            "ATTACKING,  -2, 45, NORMAL",
            "DEFENSIVE,   2, 45, NORMAL",
            // losing by 2+ — BALANCED: PUSH from 46, ALL_OUT from 70
            "BALANCED,   -2, 46, PUSH",
            "BALANCED,   -2, 69, PUSH",
            "BALANCED,   -2, 70, ALL_OUT",
            "BALANCED,   -3, 70, ALL_OUT",
            // losing by 2+ — ATTACKING (chase -10, clamped): PUSH from 46 (36->46), ALL_OUT from 60
            "ATTACKING,  -2, 46, PUSH",
            "ATTACKING,  -2, 59, PUSH",
            "ATTACKING,  -2, 60, ALL_OUT",
            // losing by 2+ — DEFENSIVE (chase +10): PUSH from 56, ALL_OUT from 80
            "DEFENSIVE,  -2, 55, NORMAL",
            "DEFENSIVE,  -2, 56, PUSH",
            "DEFENSIVE,  -2, 79, PUSH",
            "DEFENSIVE,  -2, 80, ALL_OUT",
            // losing by 1 — BALANCED: PUSH from 60, ALL_OUT from 80
            "BALANCED,   -1, 59, NORMAL",
            "BALANCED,   -1, 60, PUSH",
            "BALANCED,   -1, 79, PUSH",
            "BALANCED,   -1, 80, ALL_OUT",
            // losing by 1 — ATTACKING: PUSH from 50, ALL_OUT from 70
            "ATTACKING,  -1, 49, NORMAL",
            "ATTACKING,  -1, 50, PUSH",
            "ATTACKING,  -1, 70, ALL_OUT",
            // losing by 1 — DEFENSIVE: PUSH from 70, ALL_OUT from 90
            "DEFENSIVE,  -1, 69, NORMAL",
            "DEFENSIVE,  -1, 70, PUSH",
            "DEFENSIVE,  -1, 89, PUSH",
            "DEFENSIVE,  -1, 90, ALL_OUT",
            // drawing — identity-specific fixed rules (no offsets)
            "BALANCED,    0, 90, NORMAL",
            "ATTACKING,   0, 74, NORMAL",
            "ATTACKING,   0, 75, PUSH",
            "DEFENSIVE,   0, 84, NORMAL",
            "DEFENSIVE,   0, 85, PROTECT",
            // winning by 1 — BALANCED: PROTECT from 75, PARK from 85
            "BALANCED,    1, 74, NORMAL",
            "BALANCED,    1, 75, PROTECT",
            "BALANCED,    1, 84, PROTECT",
            "BALANCED,    1, 85, PARK_THE_BUS",
            // winning by 1 — ATTACKING (protect +5): PROTECT from 80, PARK from 90
            "ATTACKING,   1, 79, NORMAL",
            "ATTACKING,   1, 80, PROTECT",
            "ATTACKING,   1, 89, PROTECT",
            "ATTACKING,   1, 90, PARK_THE_BUS",
            // winning by 1 — DEFENSIVE (protect -10): PROTECT from 65, PARK from 75
            "DEFENSIVE,   1, 64, NORMAL",
            "DEFENSIVE,   1, 65, PROTECT",
            "DEFENSIVE,   1, 75, PARK_THE_BUS",
            // winning by 2+: PROTECT from 80 (+offset), never parks the bus
            "BALANCED,    2, 79, NORMAL",
            "BALANCED,    2, 80, PROTECT",
            "BALANCED,    3, 90, PROTECT",
            "ATTACKING,   2, 84, NORMAL",
            "ATTACKING,   2, 85, PROTECT",
            "DEFENSIVE,   2, 70, PROTECT",
    })
    void postureMatrix(Mentality identity, int scoreDiff, int minute, Posture expected) {
        assertThat(AiManagerBrain.decide(identity, scoreDiff, minute, false)).isEqualTo(expected);
    }

    // ---- decide(): red-card (downAMan) rules
    @ParameterizedTest
    @CsvSource({
            // not losing + down a man -> PROTECT immediately; PARK from clamp(75 + protectOffset) when winning
            "BALANCED,    0, 46, PROTECT",
            "ATTACKING,   0, 80, PROTECT",      // overrides the drawing ATTACKING PUSH-from-75 rule
            "DEFENSIVE,   0, 90, PROTECT",
            "BALANCED,    1, 46, PROTECT",
            "BALANCED,    1, 74, PROTECT",
            "BALANCED,    1, 75, PARK_THE_BUS",
            "BALANCED,    2, 75, PARK_THE_BUS", // winning by 2 down a man DOES park (unlike 11-a-side)
            "ATTACKING,   1, 79, PROTECT",
            "ATTACKING,   1, 80, PARK_THE_BUS", // 75 + 5
            "DEFENSIVE,   1, 65, PARK_THE_BUS", // 75 - 10
            // losing + down a man -> the chase is unaffected
            "BALANCED,   -1, 60, PUSH",
            "BALANCED,   -2, 70, ALL_OUT",
            // first half + down a man -> still NORMAL
            "BALANCED,    1, 45, NORMAL",
    })
    void redCardRules(Mentality identity, int scoreDiff, int minute, Posture expected) {
        assertThat(AiManagerBrain.decide(identity, scoreDiff, minute, true)).isEqualTo(expected);
    }

    // ---- profileFor(): overrides on top of identity; marking/roles/takers always preserved
    private static final Map<UUID, RoleAssignment> ROLES =
            Map.of(UUID.randomUUID(), RoleAssignment.defaultFor(DetailedPosition.MEIA_CENTRAL));
    private static final UUID PEN = UUID.randomUUID();
    private static final UUID FK = UUID.randomUUID();
    private static final UUID CORNER = UUID.randomUUID();
    private static final TacticalProfile IDENTITY = new TacticalProfile(
            Mentality.BALANCED, GameStyle.JOGO_DIRETO, AttackZone.MISTO, "3-5-2", Pressing.MEDIUM,
            Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER, Map.of(), Map.of());

    @Test
    void normalReturnsTheIdentityUnchanged() {
        assertThat(AiManagerBrain.profileFor(Posture.NORMAL, IDENTITY)).isEqualTo(IDENTITY);
    }

    @Test
    void pushOnlyRaisesMentality() {
        assertThat(AiManagerBrain.profileFor(Posture.PUSH, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.ATTACKING, GameStyle.JOGO_DIRETO, AttackZone.MISTO, "3-5-2", Pressing.MEDIUM,
                Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER, Map.of(), Map.of()));
    }

    @Test
    void allOutGoesAttackingHighPressFourTwoFour() {
        assertThat(AiManagerBrain.profileFor(Posture.ALL_OUT, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.ATTACKING, GameStyle.JOGO_DIRETO, AttackZone.MISTO, "4-2-4", Pressing.HIGH,
                Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER, Map.of(), Map.of()));
    }

    @Test
    void protectOnlyLowersMentality() {
        assertThat(AiManagerBrain.profileFor(Posture.PROTECT, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.DEFENSIVE, GameStyle.JOGO_DIRETO, AttackZone.MISTO, "3-5-2", Pressing.MEDIUM,
                Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER, Map.of(), Map.of()));
    }

    @Test
    void parkTheBusGoesDefensiveLowPressFiveFourOne() {
        assertThat(AiManagerBrain.profileFor(Posture.PARK_THE_BUS, IDENTITY)).isEqualTo(new TacticalProfile(
                Mentality.DEFENSIVE, GameStyle.JOGO_DIRETO, AttackZone.MISTO, "5-4-1", Pressing.LOW,
                Marking.MAN_TIGHT, ROLES, PEN, FK, CORNER, Map.of(), Map.of()));
    }
}
