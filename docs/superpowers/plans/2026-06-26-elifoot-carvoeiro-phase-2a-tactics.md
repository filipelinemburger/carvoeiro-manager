# Phase 2A — Tactics & Match Depth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full tactics board (mentality, formation, pressing, marking, set-piece takers, per-player roles), player form, in-match fitness (stamina decay), and card-driven suspensions to the existing match loop.

**Architecture:** A new `TeamStrengthCalculator` folds an XI + its `TacticalProfile` into an enriched `TeamMatchInput`; the extended `MatchSimulationService.simulate(TeamMatchInput, TeamMatchInput, seed)` consumes two of them and emits typed events (open-play goal, set-piece goal, yellow, red). `RoundSimulationService` wires it together and applies the per-round player-state lifecycle (cards→suspensions, form). The engine stays one pure function seeded from `saveGameId ^ fixtureId ^ round`.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 / Gradle 9.6.1; Flyway V3; JPA; React 19 + TS strict + Vite + TanStack Query; JUnit 5 + Testcontainers (Postgres) + AssertJ; Vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-06-26-elifoot-carvoeiro-phase-2a-tactics-design.md` (read it; this plan implements it).

## Global Constraints

- **Determinism:** no `Math.random`, no clock, no `UUID.randomUUID()` in the simulation RNG path. Every stochastic outcome is drawn from the single `Random(seed)` in `MatchSimulationService.simulate` (per-minute order: home-goal, home-goal-type, away-goal, away-goal-type, home-card, home-card-type, away-card, away-card-type). Scorer/carded-player attribution uses a separate `Random(seed ^ 0x5DEECE66DL)` in `RoundSimulationService`, iterating events in order. Same seed + same pre-match state ⇒ identical result.
- **Layering:** Controller → Service → Repository. `TeamStrengthCalculator` and `MatchSimulationService` are pure (no repositories).
- **Enums with behavior:** `Mentality`, `Pressing`, `Marking`, `Formation`, `PlayerRole`, `EventType` carry their modifiers as fields/methods.
- **Ownership:** every new/changed endpoint goes through `SaveGameService.requireOwned` (via `LineupService.requireOwnedClub` for club scoping); foreign/missing ⇒ `NOT_FOUND`.
- **Errors:** `ApiException(code, message, HttpStatus)`; 422 `UNPROCESSABLE_CONTENT` for semantic lineup/tactics errors, 404 for ownership.
- **IDs/time/money:** UUID ids; `Instant`/`LocalDate`; `BigDecimal` (not used in 2A but keep conventions).
- **Commits:** per task; message trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Under subagent-driven execution the controller commits after each task; implementers leave changes staged.
- **Coverage:** ≥80%; Given/When/Then; descriptive test names.
- **Refinement vs spec:** `TeamMatchInput` carries a precomputed `fitnessDecay` (computed by the calculator from pressing + average stamina) instead of raw `staminaAvg`; the engine consumes `fitnessDecay` directly. This is the spec's intent made concrete.

## File Structure

**Backend — new files** (package `br.com.elifootcarvoeiro`):
- `player/Mentality.java`, `player/Pressing.java`, `player/Marking.java`, `player/Formation.java`, `player/PlayerRole.java` — tactics enums (in `player` because they describe player/club tactical attributes; reused by `match`).
- `match/EventType.java` — match event types.
- `match/TacticalProfile.java` — value object (per-match tactical settings).
- `match/TeamMatchInput.java` — enriched engine input record.
- `match/SimulatedEvent.java` — typed in-match event.
- `match/TeamStrengthCalculator.java` — tactics → input (pure service).
- `match/TacticsService.java` — builds `TacticalProfile` (managed from persisted lineup; default for AI/unset).
- `match/SetTacticsRequest.java`, `match/TacticsResponse.java`, `match/TacticsController.java` — tactics API.
- `match/LineupStarter.java` — `{playerId, role}` element of the lineup request.

**Backend — modified files:**
- `db/migration/V3__tactics.sql` — additive columns.
- `player/Player.java` — `form`, `yellowCards`, `suspensionRemaining` + mutators.
- `match/Lineup.java` — tactics columns + enum getters.
- `match/LineupPlayer.java` — `role` + role-aware factory.
- `match/MatchSimulationService.java` — new signature + fitness/tempo/set-piece/cards.
- `match/SimulatedMatch.java` — `events` instead of `goals`.
- `match/RoundSimulationService.java` — wire calculator + lifecycle.
- `match/LineupService.java` — roles, suspension guard, eligibility-aware `bestXi`/`startingXi`, extended responses.
- `match/SetLineupRequest.java`, `match/LineupResponse.java`, `match/PlayerResponse.java`, `match/SimulateRoundResponse.java` — extended DTOs.
- `match/SquadController.java` — unchanged routes; request body shape changes.

**Backend — deleted:** `match/LineupStrength.java`, `match/SimulatedGoal.java` (replaced).

**Frontend — modified:** `src/api/types.ts`, `src/api/career.ts`, `src/features/squad/SquadPage.tsx` (+ test), `src/features/match/AdvancePanel.tsx`.
**Frontend — new:** `src/features/squad/TacticsPanel.tsx` (+ test).

---

### Task 1: Tactics enums

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/player/Mentality.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/player/Pressing.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/player/Marking.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/player/Formation.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/player/PlayerRole.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/EventType.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/player/PlayerRoleTest.java`, `backend/src/test/java/br/com/elifootcarvoeiro/player/FormationTest.java`

**Interfaces:**
- Consumes: `Position` (`GK|DEF|MID|ATT`), `Player` getters (`getPace/getFinishing/getPassing/getTackling/getGoalkeeping`).
- Produces:
  - `Mentality.attackMult():double`, `Mentality.defenseMult():double` — `DEFENSIVE(0.88,1.12)`, `BALANCED(1.0,1.0)`, `ATTACKING(1.12,0.90)`.
  - `Pressing.tempoMult():double`, `Pressing.aggressionAdd():double`, `Pressing.decayMult():double` — `LOW(0.90,0.0,0.85)`, `MEDIUM(1.0,0.0,1.0)`, `HIGH(1.12,0.50,1.25)`.
  - `Marking.defenseMult():double`, `Marking.aggressionAdd():double` — `ZONAL(1.0,0.0)`, `MAN_TIGHT(1.05,0.50)`.
  - `Formation.attackMult():double`, `Formation.defenseMult():double`, `Formation.fromCode(String):Formation` (unknown → `F_4_4_2`).
  - `PlayerRole.attackContribution(Player):double`, `PlayerRole.defenseContribution(Player):double`, `PlayerRole.isValidFor(Position):boolean`, `PlayerRole.defaultFor(Position):PlayerRole`.
  - `EventType` = `GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/br/com/elifootcarvoeiro/player/FormationTest.java`:
```java
package br.com.elifootcarvoeiro.player;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FormationTest {

    @Test
    void mapsKnownCodes() {
        assertThat(Formation.fromCode("4-3-3")).isEqualTo(Formation.F_4_3_3);
        assertThat(Formation.fromCode("5-3-2")).isEqualTo(Formation.F_5_3_2);
    }

    @Test
    void unknownCodeDefaultsTo442() {
        assertThat(Formation.fromCode("9-0-1")).isEqualTo(Formation.F_4_4_2);
        assertThat(Formation.fromCode(null)).isEqualTo(Formation.F_4_4_2);
    }

    @Test
    void attackingFormationsBiasAttackOverDefensiveOnes() {
        assertThat(Formation.F_4_3_3.attackMult()).isGreaterThan(Formation.F_5_3_2.attackMult());
        assertThat(Formation.F_5_3_2.defenseMult()).isGreaterThan(Formation.F_4_3_3.defenseMult());
    }
}
```

`backend/src/test/java/br/com/elifootcarvoeiro/player/PlayerRoleTest.java`:
```java
package br.com.elifootcarvoeiro.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerRoleTest {

    private Player outfield(Position pos, int pace, int fin, int pass, int tkl) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                pace, fin, pass, tkl, 10, 70, 75);
    }

    @Test
    void poacherWeightsFinishingForAttack() {
        Player p = outfield(Position.ATT, 50, 90, 40, 10);
        // 0.2*pace + 0.8*finishing = 0.2*50 + 0.8*90 = 82
        assertThat(PlayerRole.POACHER.attackContribution(p)).isEqualTo(82.0);
        assertThat(PlayerRole.POACHER.defenseContribution(p)).isEqualTo(0.0);
    }

    @Test
    void holdingMidWeightsTacklingForDefense() {
        Player p = outfield(Position.MID, 50, 50, 60, 80);
        // defense = 0.8 * tackling = 64
        assertThat(PlayerRole.HOLDING_MID.defenseContribution(p)).isEqualTo(64.0);
    }

    @Test
    void rolesValidateAgainstPositionBand() {
        assertThat(PlayerRole.POACHER.isValidFor(Position.ATT)).isTrue();
        assertThat(PlayerRole.POACHER.isValidFor(Position.DEF)).isFalse();
    }

    @Test
    void defaultRolePerBand() {
        assertThat(PlayerRole.defaultFor(Position.GK)).isEqualTo(PlayerRole.GOALKEEPER);
        assertThat(PlayerRole.defaultFor(Position.DEF)).isEqualTo(PlayerRole.FULL_BACK);
        assertThat(PlayerRole.defaultFor(Position.MID)).isEqualTo(PlayerRole.BOX_TO_BOX);
        assertThat(PlayerRole.defaultFor(Position.ATT)).isEqualTo(PlayerRole.POACHER);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*FormationTest' --tests '*PlayerRoleTest'`
Expected: FAIL — compilation errors (classes don't exist).

- [ ] **Step 3: Create the enums**

`Mentality.java`:
```java
package br.com.elifootcarvoeiro.player;

public enum Mentality {
    DEFENSIVE(0.88, 1.12),
    BALANCED(1.00, 1.00),
    ATTACKING(1.12, 0.90);

    private final double attackMult;
    private final double defenseMult;

    Mentality(double attackMult, double defenseMult) {
        this.attackMult = attackMult;
        this.defenseMult = defenseMult;
    }

    public double attackMult() { return attackMult; }
    public double defenseMult() { return defenseMult; }
}
```

`Pressing.java`:
```java
package br.com.elifootcarvoeiro.player;

public enum Pressing {
    LOW(0.90, 0.00, 0.85),
    MEDIUM(1.00, 0.00, 1.00),
    HIGH(1.12, 0.50, 1.25);

    private final double tempoMult;
    private final double aggressionAdd;
    private final double decayMult;

    Pressing(double tempoMult, double aggressionAdd, double decayMult) {
        this.tempoMult = tempoMult;
        this.aggressionAdd = aggressionAdd;
        this.decayMult = decayMult;
    }

    public double tempoMult() { return tempoMult; }
    public double aggressionAdd() { return aggressionAdd; }
    public double decayMult() { return decayMult; }
}
```

`Marking.java`:
```java
package br.com.elifootcarvoeiro.player;

public enum Marking {
    ZONAL(1.00, 0.00),
    MAN_TIGHT(1.05, 0.50);

    private final double defenseMult;
    private final double aggressionAdd;

    Marking(double defenseMult, double aggressionAdd) {
        this.defenseMult = defenseMult;
        this.aggressionAdd = aggressionAdd;
    }

    public double defenseMult() { return defenseMult; }
    public double aggressionAdd() { return aggressionAdd; }
}
```

`Formation.java`:
```java
package br.com.elifootcarvoeiro.player;

public enum Formation {
    F_4_4_2("4-4-2", 1.00, 1.00),
    F_4_3_3("4-3-3", 1.10, 0.94),
    F_3_5_2("3-5-2", 1.06, 0.96),
    F_5_3_2("5-3-2", 0.92, 1.10),
    F_4_5_1("4-5-1", 0.96, 1.06),
    F_4_2_3_1("4-2-3-1", 1.04, 1.00);

    private final String code;
    private final double attackMult;
    private final double defenseMult;

    Formation(String code, double attackMult, double defenseMult) {
        this.code = code;
        this.attackMult = attackMult;
        this.defenseMult = defenseMult;
    }

    public String code() { return code; }
    public double attackMult() { return attackMult; }
    public double defenseMult() { return defenseMult; }

    public static Formation fromCode(String code) {
        if (code != null) {
            for (Formation f : values()) {
                if (f.code.equals(code)) {
                    return f;
                }
            }
        }
        return F_4_4_2;
    }
}
```

`PlayerRole.java` (weight arrays are `[pace, finishing, passing, tackling, goalkeeping]`):
```java
package br.com.elifootcarvoeiro.player;

public enum PlayerRole {
    GOALKEEPER(Position.GK,       new double[]{0,    0,   0,   0,    0},   new double[]{0, 0, 0, 0,    1.0}),
    STOPPER(Position.DEF,         new double[]{0.1,  0.1, 0,   0,    0},   new double[]{0, 0, 0, 1.0,  0}),
    FULL_BACK(Position.DEF,       new double[]{0.3,  0,   0.2, 0,    0},   new double[]{0, 0, 0, 0.8,  0}),
    BALL_PLAYING_DEF(Position.DEF,new double[]{0.1,  0,   0.4, 0,    0},   new double[]{0, 0, 0, 0.85, 0}),
    HOLDING_MID(Position.MID,     new double[]{0,    0,   0.3, 0,    0},   new double[]{0, 0, 0, 0.8,  0}),
    BOX_TO_BOX(Position.MID,      new double[]{0.2,  0.3, 0.3, 0,    0},   new double[]{0, 0, 0, 0.5,  0}),
    PLAYMAKER(Position.MID,       new double[]{0,    0.2, 0.6, 0,    0},   new double[]{0, 0, 0, 0.2,  0}),
    POACHER(Position.ATT,         new double[]{0.2,  0.8, 0,   0,    0},   new double[]{0, 0, 0, 0,    0}),
    TARGET_MAN(Position.ATT,      new double[]{0,    0.6, 0.2, 0,    0},   new double[]{0, 0, 0, 0.1,  0}),
    WINGER(Position.ATT,          new double[]{0.6,  0.2, 0.2, 0,    0},   new double[]{0, 0, 0, 0.05, 0});

    private final Position band;
    private final double[] attackWeights;
    private final double[] defenseWeights;

    PlayerRole(Position band, double[] attackWeights, double[] defenseWeights) {
        this.band = band;
        this.attackWeights = attackWeights;
        this.defenseWeights = defenseWeights;
    }

    public boolean isValidFor(Position position) { return band == position; }

    public double attackContribution(Player p) { return weighted(attackWeights, p); }

    public double defenseContribution(Player p) { return weighted(defenseWeights, p); }

    private double weighted(double[] w, Player p) {
        return w[0] * p.getPace()
                + w[1] * p.getFinishing()
                + w[2] * p.getPassing()
                + w[3] * p.getTackling()
                + w[4] * p.getGoalkeeping();
    }

    public static PlayerRole defaultFor(Position position) {
        return switch (position) {
            case GK -> GOALKEEPER;
            case DEF -> FULL_BACK;
            case MID -> BOX_TO_BOX;
            case ATT -> POACHER;
        };
    }
}
```

`EventType.java`:
```java
package br.com.elifootcarvoeiro.match;

public enum EventType {
    GOAL, SET_PIECE_GOAL, YELLOW_CARD, RED_CARD
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*FormationTest' --tests '*PlayerRoleTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/player/{Mentality,Pressing,Marking,Formation,PlayerRole}.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/EventType.java \
        backend/src/test/java/br/com/elifootcarvoeiro/player/{FormationTest,PlayerRoleTest}.java
git commit -m "feat(tactics): tactics enums with behavior (mentality, pressing, marking, formation, role, event type)"
```

---

### Task 2: V3 migration + entity fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__tactics.sql`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/Lineup.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupPlayer.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsEntityIT.java`

**Interfaces:**
- Consumes: `Mentality`, `Pressing`, `Marking`, `PlayerRole` (Task 1).
- Produces:
  - `Player`: `getForm():int`, `getYellowCards():int`, `getSuspensionRemaining():int`, `applyForm(int delta)` (clamps `[-5,5]`), `addYellowCard()`, `addSuspension(int matches)`, `decrementSuspension()`, `isEligible():boolean` (`suspensionRemaining == 0`).
  - `Lineup`: `getMentality():Mentality`, `getPressing():Pressing`, `getMarking():Marking`, `getPenaltyTakerId():UUID`, `getFreekickTakerId():UUID`, `getCornerTakerId():UUID` + setters; `create(...)` initializes `BALANCED/MEDIUM/ZONAL`, null takers.
  - `LineupPlayer`: `getRole():PlayerRole`, `setRole(PlayerRole)`; `create(lineupId, playerId, starting)` defaults role `BOX_TO_BOX`; new `create(lineupId, playerId, starting, PlayerRole role)`.

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V3__tactics.sql`:
```sql
ALTER TABLE players
  ADD COLUMN form                 INT NOT NULL DEFAULT 0,
  ADD COLUMN yellow_cards         INT NOT NULL DEFAULT 0,
  ADD COLUMN suspension_remaining INT NOT NULL DEFAULT 0;

ALTER TABLE lineups
  ADD COLUMN mentality         VARCHAR(12) NOT NULL DEFAULT 'BALANCED',
  ADD COLUMN pressing          VARCHAR(8)  NOT NULL DEFAULT 'MEDIUM',
  ADD COLUMN marking           VARCHAR(10) NOT NULL DEFAULT 'ZONAL',
  ADD COLUMN penalty_taker_id  UUID NULL REFERENCES players(id),
  ADD COLUMN freekick_taker_id UUID NULL REFERENCES players(id),
  ADD COLUMN corner_taker_id   UUID NULL REFERENCES players(id);

ALTER TABLE lineup_players
  ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'BOX_TO_BOX';
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsEntityIT.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.player.Pressing;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsEntityIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    @Test
    void newColumnsPersistWithDefaultsAndMutators() {
        // Given a real career (lineups/lineup_players have FKs to save_games/clubs/players)
        User user = User.create("ent_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID club = save.managedClubId();
        Player anyPlayer = playerRepository.findByClubId(club).get(0);

        Lineup lineup = Lineup.create(save.id(), club, "4-3-3");
        assertThat(lineup.getMentality()).isEqualTo(Mentality.BALANCED);
        assertThat(lineup.getPressing()).isEqualTo(Pressing.MEDIUM);
        assertThat(lineup.getMarking()).isEqualTo(Marking.ZONAL);

        lineup.setMentality(Mentality.ATTACKING);
        lineupRepository.save(lineup);
        Lineup reloaded = lineupRepository.findById(lineup.getId()).orElseThrow();
        assertThat(reloaded.getMentality()).isEqualTo(Mentality.ATTACKING);

        LineupPlayer lp = LineupPlayer.create(lineup.getId(), anyPlayer.getId(), true, PlayerRole.POACHER);
        lineupPlayerRepository.save(lp);
        assertThat(lineupPlayerRepository.findByLineupId(lineup.getId()).get(0).getRole())
                .isEqualTo(PlayerRole.POACHER);
    }

    @Test
    void playerFormClampsAndSuspensionDecrements() {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID,
                25, "BRA", 70, 70, 70, 70, 10, 70, 72);
        assertThat(p.getForm()).isZero();
        assertThat(p.isEligible()).isTrue();

        for (int i = 0; i < 10; i++) p.applyForm(1);
        assertThat(p.getForm()).isEqualTo(5); // clamped

        p.applyForm(-20);
        assertThat(p.getForm()).isEqualTo(-5); // clamped

        p.addSuspension(2);
        assertThat(p.isEligible()).isFalse();
        p.decrementSuspension();
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TacticsEntityIT'`
Expected: FAIL — compilation errors (new methods/columns absent).

- [ ] **Step 4: Add entity fields**

In `Player.java`, add fields after `overall` and before the constructors:
```java
    @Column(name = "form", nullable = false)
    private int form;

    @Column(name = "yellow_cards", nullable = false)
    private int yellowCards;

    @Column(name = "suspension_remaining", nullable = false)
    private int suspensionRemaining;
```
New columns default to 0 via the DB and the JVM `int` default, so the existing `create(...)` factory needs no change. Add getters + mutators alongside the existing getters:
```java
    public int getForm() { return form; }
    public int getYellowCards() { return yellowCards; }
    public int getSuspensionRemaining() { return suspensionRemaining; }

    public void applyForm(int delta) {
        this.form = Math.max(-5, Math.min(5, this.form + delta));
    }

    public void addYellowCard() { this.yellowCards++; }

    public void addSuspension(int matches) { this.suspensionRemaining += matches; }

    public void decrementSuspension() {
        if (this.suspensionRemaining > 0) {
            this.suspensionRemaining--;
        }
    }

    public boolean isEligible() { return this.suspensionRemaining == 0; }
```

In `Lineup.java`, add imports and fields:
```java
import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Pressing;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
```
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "mentality", nullable = false, length = 12)
    private Mentality mentality;

    @Enumerated(EnumType.STRING)
    @Column(name = "pressing", nullable = false, length = 8)
    private Pressing pressing;

    @Enumerated(EnumType.STRING)
    @Column(name = "marking", nullable = false, length = 10)
    private Marking marking;

    @Column(name = "penalty_taker_id")
    private UUID penaltyTakerId;

    @Column(name = "freekick_taker_id")
    private UUID freekickTakerId;

    @Column(name = "corner_taker_id")
    private UUID cornerTakerId;
```
Update the private constructor + `create` to initialize tactics defaults:
```java
    private Lineup(UUID id, UUID saveGameId, UUID clubId, String formation) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.formation = formation;
        this.mentality = Mentality.BALANCED;
        this.pressing = Pressing.MEDIUM;
        this.marking = Marking.ZONAL;
    }
```
Add getters/setters:
```java
    public Mentality getMentality() { return mentality; }
    public Pressing getPressing() { return pressing; }
    public Marking getMarking() { return marking; }
    public UUID getPenaltyTakerId() { return penaltyTakerId; }
    public UUID getFreekickTakerId() { return freekickTakerId; }
    public UUID getCornerTakerId() { return cornerTakerId; }

    public void setMentality(Mentality mentality) { this.mentality = mentality; }
    public void setPressing(Pressing pressing) { this.pressing = pressing; }
    public void setMarking(Marking marking) { this.marking = marking; }
    public void setPenaltyTakerId(UUID id) { this.penaltyTakerId = id; }
    public void setFreekickTakerId(UUID id) { this.freekickTakerId = id; }
    public void setCornerTakerId(UUID id) { this.cornerTakerId = id; }
```

In `LineupPlayer.java`, add imports + field + role-aware factory:
```java
import br.com.elifootcarvoeiro.player.PlayerRole;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
```
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private PlayerRole role;
```
```java
    private LineupPlayer(UUID lineupId, UUID playerId, boolean starting, PlayerRole role) {
        this.lineupId = lineupId;
        this.playerId = playerId;
        this.starting = starting;
        this.role = role;
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting) {
        return new LineupPlayer(lineupId, playerId, starting, PlayerRole.BOX_TO_BOX);
    }

    public static LineupPlayer create(UUID lineupId, UUID playerId, boolean starting, PlayerRole role) {
        return new LineupPlayer(lineupId, playerId, starting, role);
    }

    public PlayerRole getRole() { return role; }
    public void setRole(PlayerRole role) { this.role = role; }
```
(Replace the old single-constructor/`create`; keep the protected no-arg constructor for JPA.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*TacticsEntityIT'`
Expected: PASS (Flyway applies V3; `ddl-auto=validate` confirms entities match schema).

- [ ] **Step 6: Run the full suite to confirm nothing regressed**

Run: `cd backend && ./gradlew test`
Expected: PASS (existing 38 tests + new ones; entity additions are backward compatible).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__tactics.sql \
        backend/src/main/java/br/com/elifootcarvoeiro/player/Player.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/Lineup.java \
        backend/src/main/java/br/com/elifootcarvoeiro/match/LineupPlayer.java \
        backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsEntityIT.java
git commit -m "feat(tactics): V3 migration + entity state (form, cards, suspensions, tactics, roles)"
```

---

### Task 3: TeamStrengthCalculator (tactics → input)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TacticalProfile.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TeamMatchInput.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TeamStrengthCalculator.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/TeamStrengthCalculatorTest.java`

**Interfaces:**
- Consumes: `Player`, `Position`, `Mentality`, `Pressing`, `Marking`, `Formation`, `PlayerRole`.
- Produces:
  - `TacticalProfile(Mentality mentality, String formation, Pressing pressing, Marking marking, Map<UUID,PlayerRole> roles, UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId)` — `formation` is the display code (e.g. `"4-3-3"`).
  - `TeamMatchInput(double attack, double defense, double tempo, double aggression, double setPieceThreat, double fitnessDecay)`.
  - `TeamStrengthCalculator.compute(List<Player> xi, TacticalProfile profile):TeamMatchInput` (pure, `@Service`).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/br/com/elifootcarvoeiro/match/TeamStrengthCalculatorTest.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.player.Pressing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TeamStrengthCalculatorTest {

    private final TeamStrengthCalculator calc = new TeamStrengthCalculator();

    private Player p(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all rated 70. */
    private List<Player> standardXi() {
        return List.of(
                p(Position.GK, 70),
                p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70),
                p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70),
                p(Position.ATT, 70), p(Position.ATT, 70));
    }

    private TacticalProfile profile(Mentality m, String formation, Pressing pr, Marking mk, List<Player> xi) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        return new TacticalProfile(m, formation, pr, mk, roles, null, null, null);
    }

    @Test
    void attackingMentalityRaisesAttackAndLowersDefenseVsDefensive() {
        List<Player> xi = standardXi();
        TeamMatchInput att = calc.compute(xi, profile(Mentality.ATTACKING, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        TeamMatchInput def = calc.compute(xi, profile(Mentality.DEFENSIVE, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(att.attack()).isGreaterThan(def.attack());
        assertThat(att.defense()).isLessThan(def.defense());
    }

    @Test
    void highPressRaisesTempoAggressionAndDecay() {
        List<Player> xi = standardXi();
        TeamMatchInput high = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.HIGH, Marking.ZONAL, xi));
        TeamMatchInput low = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.LOW, Marking.ZONAL, xi));
        assertThat(high.tempo()).isGreaterThan(low.tempo());
        assertThat(high.aggression()).isGreaterThan(low.aggression());
        assertThat(high.fitnessDecay()).isGreaterThan(low.fitnessDecay());
    }

    @Test
    void manTightRaisesDefenseAndAggression() {
        List<Player> xi = standardXi();
        TeamMatchInput tight = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.MAN_TIGHT, xi));
        TeamMatchInput zonal = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(tight.defense()).isGreaterThan(zonal.defense());
        assertThat(tight.aggression()).isGreaterThan(zonal.aggression());
    }

    @Test
    void formMultiplierRaisesContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyForm(1))); // +5 form -> +10%
        TeamMatchInput inForm = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(inForm.attack()).isGreaterThan(baseline.attack());
    }

    @Test
    void setPieceThreatTracksPenaltyTaker() {
        List<Player> xi = standardXi();
        Player ace = p(Position.ATT, 99);
        List<Player> withAce = new java.util.ArrayList<>(xi);
        withAce.set(10, ace);
        Map<UUID, PlayerRole> roles = new HashMap<>();
        withAce.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        TacticalProfile withTaker = new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, ace.getId(), ace.getId(), ace.getId());
        TacticalProfile noTaker = new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, null, null, null);
        assertThat(calc.compute(withAce, withTaker).setPieceThreat())
                .isGreaterThanOrEqualTo(calc.compute(withAce, noTaker).setPieceThreat());
        assertThat(calc.compute(withAce, withTaker).setPieceThreat()).isBetween(0.0, 1.0);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*TeamStrengthCalculatorTest'`
Expected: FAIL — compilation errors (classes don't exist).

- [ ] **Step 3: Create the value objects + calculator**

`TacticalProfile.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Pressing;

import java.util.Map;
import java.util.UUID;

/**
 * The tactical settings applied to one club for one match. Not a JPA entity:
 * built by {@link TacticsService} from a persisted lineup (managed club) or as a
 * default (AI clubs / unset). {@code formation} is the display code, e.g. "4-3-3".
 */
public record TacticalProfile(
        Mentality mentality,
        String formation,
        Pressing pressing,
        Marking marking,
        Map<UUID, PlayerRole> roles,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
```

`TeamMatchInput.java`:
```java
package br.com.elifootcarvoeiro.match;

/**
 * Enriched, engine-ready strength of one team for one match.
 * {@code fitnessDecay} is precomputed from pressing + average stamina; the engine
 * applies it per minute. {@code setPieceThreat} is ~[0,1].
 */
public record TeamMatchInput(
        double attack,
        double defense,
        double tempo,
        double aggression,
        double setPieceThreat,
        double fitnessDecay) {
}
```

`TeamStrengthCalculator.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Formation;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Pure domain service: folds an XI plus its TacticalProfile into a TeamMatchInput.
 * No repository access. See the Phase-2A design spec for the modifier pipeline.
 */
@Service
public class TeamStrengthCalculator {

    private static final double DECAY_BASE = 0.15;

    public TeamMatchInput compute(List<Player> xi, TacticalProfile profile) {
        double attackSum = 0.0;
        double outfieldDefenseSum = 0.0;
        double gkDefense = 0.0;
        double staminaSum = 0.0;
        int outfieldCount = 0;

        for (Player p : xi) {
            PlayerRole role = resolveRole(profile, p);
            double formMult = 1.0 + 0.02 * p.getForm();
            attackSum += role.attackContribution(p) * formMult;
            staminaSum += p.getStamina();
            double def = role.defenseContribution(p) * formMult;
            if (p.getPosition() == Position.GK) {
                gkDefense = def;
            } else {
                outfieldDefenseSum += def;
                outfieldCount++;
            }
        }

        double attack = attackSum / xi.size();
        double outfieldDefense = outfieldCount > 0 ? outfieldDefenseSum / outfieldCount : 0.0;
        double defense = 0.6 * outfieldDefense + 0.4 * gkDefense;

        Formation formation = Formation.fromCode(profile.formation());
        attack *= formation.attackMult() * profile.mentality().attackMult();
        defense *= formation.defenseMult() * profile.mentality().defenseMult() * profile.marking().defenseMult();

        double tempo = profile.pressing().tempoMult();
        double aggression = 1.0 + profile.pressing().aggressionAdd() + profile.marking().aggressionAdd();

        double staminaAvg = staminaSum / xi.size();
        double fitnessDecay = DECAY_BASE * profile.pressing().decayMult() * clamp(70.0 / staminaAvg, 0.7, 1.4);

        double setPieceThreat = setPieceThreat(xi, profile);

        return new TeamMatchInput(attack, defense, tempo, aggression, setPieceThreat, fitnessDecay);
    }

    private PlayerRole resolveRole(TacticalProfile profile, Player p) {
        PlayerRole role = profile.roles() == null ? null : profile.roles().get(p.getId());
        if (role == null || !role.isValidFor(p.getPosition())) {
            return PlayerRole.defaultFor(p.getPosition());
        }
        return role;
    }

    private double setPieceThreat(List<Player> xi, TacticalProfile profile) {
        UUID takerId = profile.penaltyTakerId();
        Player taker = null;
        if (takerId != null) {
            taker = xi.stream().filter(p -> p.getId().equals(takerId)).findFirst().orElse(null);
        }
        int finishing = taker != null
                ? taker.getFinishing()
                : xi.stream().mapToInt(Player::getFinishing).max().orElse(0);
        return finishing / 99.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*TeamStrengthCalculatorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/match/{TacticalProfile,TeamMatchInput,TeamStrengthCalculator}.java \
        backend/src/test/java/br/com/elifootcarvoeiro/match/TeamStrengthCalculatorTest.java
git commit -m "feat(tactics): TeamStrengthCalculator folds tactics + roles into TeamMatchInput"
```

---

### Task 4: TacticsService (build profiles)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TacticsService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsServiceIT.java`

**Interfaces:**
- Consumes: `LineupRepository.findBySaveGameIdAndClubId(UUID,UUID):Optional<Lineup>`, `LineupPlayerRepository.findByLineupId(UUID):List<LineupPlayer>`, `Lineup` tactics getters, `LineupPlayer.getRole()`, `Player`.
- Produces:
  - `TacticsService.profileFor(UUID saveGameId, UUID clubId, List<Player> xi):TacticalProfile` — from the persisted lineup if present, else `defaultProfile(xi)`.
  - `TacticsService.defaultProfile(List<Player> xi):TacticalProfile` — `BALANCED/"4-4-2"/MEDIUM/ZONAL`, default role per band, penalty+corner taker = highest finishing, freekick taker = highest passing.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsServiceIT.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsServiceIT {

    @Autowired TacticsService tacticsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("tacsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Synthetic XI for the read-only default test (no persistence -> no FK needed). */
    private Player mk(Position pos, int finishing, int passing) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, finishing, passing, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void defaultProfileWhenNoLineupRow() {
        List<Player> xi = new ArrayList<>();
        xi.add(mk(Position.GK, 5, 50));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.DEF, 30, 60));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.MID, 50, 80));
        xi.add(mk(Position.ATT, 90, 40));
        xi.add(mk(Position.ATT, 70, 40));

        // No lineup row for these ids -> default profile (no DB write happens).
        TacticalProfile profile = tacticsService.profileFor(UUID.randomUUID(), UUID.randomUUID(), xi);

        assertThat(profile.mentality()).isEqualTo(Mentality.BALANCED);
        assertThat(profile.formation()).isEqualTo("4-4-2");
        UUID bestFinisher = xi.stream().max(java.util.Comparator.comparingInt(Player::getFinishing))
                .orElseThrow().getId();
        assertThat(profile.penaltyTakerId()).isEqualTo(bestFinisher);
        assertThat(profile.roles().values()).contains(PlayerRole.GOALKEEPER, PlayerRole.POACHER);
    }

    @Test
    void loadsPersistedManagedProfile() {
        // Real career so the lineup/lineup_player FKs are satisfied.
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> xi = playerRepository.findByClubId(club);
        UUID firstId = xi.get(0).getId();

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineup.setMentality(Mentality.ATTACKING);
        lineupRepository.save(lineup);
        lineupPlayerRepository.save(LineupPlayer.create(lineup.getId(), firstId, true, PlayerRole.GOALKEEPER));

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);
        assertThat(profile.mentality()).isEqualTo(Mentality.ATTACKING);
        assertThat(profile.formation()).isEqualTo("4-3-3");
        assertThat(profile.roles().get(firstId)).isEqualTo(PlayerRole.GOALKEEPER);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TacticsServiceIT'`
Expected: FAIL — `TacticsService` does not exist.

- [ ] **Step 3: Create the service**

`TacticsService.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Pressing;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link TacticalProfile} for a club's match: the managed club's
 * persisted lineup tactics when present, otherwise a sensible default (also used
 * for all AI clubs, which never persist a lineup).
 */
@Service
public class TacticsService {

    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public TacticsService(LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository) {
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    public TacticalProfile profileFor(UUID saveGameId, UUID clubId, List<Player> xi) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> fromLineup(lineup, xi))
                .orElseGet(() -> defaultProfile(xi));
    }

    public TacticalProfile defaultProfile(List<Player> xi) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        }
        UUID bestFinisher = xi.stream().max(Comparator.comparingInt(Player::getFinishing))
                .map(Player::getId).orElse(null);
        UUID bestPasser = xi.stream().max(Comparator.comparingInt(Player::getPassing))
                .map(Player::getId).orElse(null);
        return new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, bestFinisher, bestPasser, bestFinisher);
    }

    private TacticalProfile fromLineup(Lineup lineup, List<Player> xi) {
        Map<UUID, PlayerRole> persisted = new HashMap<>();
        lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .forEach(lp -> persisted.put(lp.getPlayerId(), lp.getRole()));
        // Fill any XI member missing a persisted role with its band default.
        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), persisted.getOrDefault(p.getId(), PlayerRole.defaultFor(p.getPosition())));
        }
        return new TacticalProfile(
                lineup.getMentality(), lineup.getFormation(), lineup.getPressing(), lineup.getMarking(),
                roles, lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*TacticsServiceIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/match/TacticsService.java \
        backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsServiceIT.java
git commit -m "feat(tactics): TacticsService builds managed + default tactical profiles"
```

---

### Task 5: Match engine extension + rewire round simulation

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/SimulatedEvent.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/SimulatedMatch.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/MatchSimulationService.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java`
- Delete: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupStrength.java`
- Delete: `backend/src/main/java/br/com/elifootcarvoeiro/match/SimulatedGoal.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/MatchSimulationServiceTest.java` (rebaselined)
- Test (verify still green): `backend/src/test/java/br/com/elifootcarvoeiro/match/RoundSimulationServiceIT.java`

**Interfaces:**
- Consumes: `TeamMatchInput` (Task 3), `TacticsService` (Task 4), `TeamStrengthCalculator` (Task 3), `EventType` (Task 1), `LineupService.startingXi/bestXi`, existing repos.
- Produces:
  - `SimulatedEvent(int minute, EventType type, boolean home)`.
  - `SimulatedMatch(int homeGoals, int awayGoals, List<SimulatedEvent> events)`.
  - `MatchSimulationService.simulate(TeamMatchInput home, TeamMatchInput away, long seed):SimulatedMatch`.
- **Scope note:** this task wires the new engine and persists typed `MatchEvent`s (goals + cards). It does **not** yet apply form/suspension lifecycle (Tasks 6–7) and does **not** yet filter suspended players (Task 6) — `bestXi`/`startingXi` are called as they exist today.

- [ ] **Step 1: Rebaseline the engine test (write the new failing test)**

Replace the entire contents of `backend/src/test/java/br/com/elifootcarvoeiro/match/MatchSimulationServiceTest.java`:
```java
package br.com.elifootcarvoeiro.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchSimulationServiceTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    // attack, defense, tempo, aggression, setPieceThreat, fitnessDecay
    private TeamMatchInput input(double attack, double defense) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.15);
    }

    @Test
    void sameSeedAndInputsProduceIdenticalResult() {
        TeamMatchInput a = input(70, 65);
        TeamMatchInput b = input(60, 60);
        assertThat(engine.simulate(a, b, 42L)).isEqualTo(engine.simulate(a, b, 42L));
    }

    @Test
    void goalEventCountMatchesScoreline() {
        SimulatedMatch m = engine.simulate(input(75, 70), input(55, 50), 7L);
        long homeGoals = m.events().stream()
                .filter(SimulatedEvent::home)
                .filter(e -> e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)
                .count();
        long awayGoals = m.events().stream()
                .filter(e -> !e.home())
                .filter(e -> e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)
                .count();
        assertThat(homeGoals).isEqualTo(m.homeGoals());
        assertThat(awayGoals).isEqualTo(m.awayGoals());
    }

    @Test
    void eventMinutesAreWithinRegulationTime() {
        SimulatedMatch m = engine.simulate(input(80, 75), input(50, 45), 99L);
        assertThat(m.events()).allSatisfy(e -> assertThat(e.minute()).isBetween(1, 90));
    }

    @Test
    void strongerTeamWinsMoreOftenOverManySeeds() {
        TeamMatchInput strong = input(85, 80);
        TeamMatchInput weak = input(45, 40);
        int strongWins = 0;
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(strong, weak, seed);
            if (m.homeGoals() > m.awayGoals()) strongWins++;
        }
        assertThat(strongWins).isGreaterThan(120);
    }

    @Test
    void highDecayTeamScoresLargerShareOfGoalsInFirstHalf() {
        // Two evenly-matched teams; the "home" side has high fitness decay.
        TeamMatchInput tired = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.40);
        TeamMatchInput fresh = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.05);
        int firstHalf = 0;
        int secondHalf = 0;
        for (long seed = 0; seed < 400; seed++) {
            SimulatedMatch m = engine.simulate(tired, fresh, seed);
            for (SimulatedEvent e : m.events()) {
                if (e.home() && (e.type() == EventType.GOAL || e.type() == EventType.SET_PIECE_GOAL)) {
                    if (e.minute() <= 45) firstHalf++; else secondHalf++;
                }
            }
        }
        assertThat(firstHalf).isGreaterThan(secondHalf);
    }

    @Test
    void higherAggressionProducesMoreCardsOverManySeeds() {
        TeamMatchInput calm = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.15);
        TeamMatchInput fiery = new TeamMatchInput(70, 65, 1.0, 2.0, 0.5, 0.15);
        long calmCards = countCards(calm);
        long fieryCards = countCards(fiery);
        assertThat(fieryCards).isGreaterThan(calmCards);
    }

    private long countCards(TeamMatchInput side) {
        long total = 0;
        TeamMatchInput opp = new TeamMatchInput(70, 65, 1.0, 1.0, 0.5, 0.15);
        for (long seed = 0; seed < 200; seed++) {
            SimulatedMatch m = engine.simulate(side, opp, seed);
            total += m.events().stream()
                    .filter(SimulatedEvent::home)
                    .filter(e -> e.type() == EventType.YELLOW_CARD || e.type() == EventType.RED_CARD)
                    .count();
        }
        return total;
    }
}
```

- [ ] **Step 2: Run the engine test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*MatchSimulationServiceTest'`
Expected: FAIL — compilation errors (`TeamMatchInput`/`SimulatedEvent`/`events()` not used by engine yet).

- [ ] **Step 3: Create `SimulatedEvent` and update `SimulatedMatch`**

`SimulatedEvent.java`:
```java
package br.com.elifootcarvoeiro.match;

public record SimulatedEvent(int minute, EventType type, boolean home) {
}
```

Replace `SimulatedMatch.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.List;

public record SimulatedMatch(int homeGoals, int awayGoals, List<SimulatedEvent> events) {
}
```

- [ ] **Step 4: Rewrite `MatchSimulationService`**

Replace `MatchSimulationService.java`:
```java
package br.com.elifootcarvoeiro.match;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, seeded match engine. Pure function: simulate(home, away, seed).
 *
 * Per-minute RNG draw order (fixed for reproducibility):
 *   home-goal, home-goal-type, away-goal, away-goal-type,
 *   home-card, home-card-type, away-card, away-card-type.
 *
 * Effective strength declines over the match via fitnessDecay; combined tempo
 * scales the total number of chances; aggression drives the card rate; a share
 * of goals are set-piece goals (typed for downstream scorer attribution).
 */
@Service
public class MatchSimulationService {

    private static final int MINUTES = 90;
    private static final double HOME_ADVANTAGE = 5.0;
    private static final double BASE = 50.0;
    private static final double FITNESS_FLOOR = 0.75;
    private static final double SET_PIECE_SHARE = 0.18;
    private static final double BASE_CARD_RATE = 0.02;
    private static final double RED_SHARE = 0.06;

    public SimulatedMatch simulate(TeamMatchInput home, TeamMatchInput away, long seed) {
        Random rng = new Random(seed);
        double tempoFactor = (home.tempo() + away.tempo()) / 2.0;
        double homeSetPiece = setPieceProbability(home);
        double awaySetPiece = setPieceProbability(away);
        double homeCardChance = BASE_CARD_RATE * home.aggression();
        double awayCardChance = BASE_CARD_RATE * away.aggression();

        List<SimulatedEvent> events = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;

        for (int minute = 1; minute <= MINUTES; minute++) {
            double homeFf = fitnessFactor(home.fitnessDecay(), minute);
            double awayFf = fitnessFactor(away.fitnessDecay(), minute);

            double homeChance = perMinuteScoreChance(home.attack() * homeFf + HOME_ADVANTAGE,
                    away.defense() * awayFf) * tempoFactor;
            double awayChance = perMinuteScoreChance(away.attack() * awayFf,
                    home.defense() * homeFf + HOME_ADVANTAGE) * tempoFactor;

            if (rng.nextDouble() < homeChance) {
                homeGoals++;
                EventType type = rng.nextDouble() < homeSetPiece ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(new SimulatedEvent(minute, type, true));
            }
            if (rng.nextDouble() < awayChance) {
                awayGoals++;
                EventType type = rng.nextDouble() < awaySetPiece ? EventType.SET_PIECE_GOAL : EventType.GOAL;
                events.add(new SimulatedEvent(minute, type, false));
            }
            if (rng.nextDouble() < homeCardChance) {
                EventType type = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, true));
            }
            if (rng.nextDouble() < awayCardChance) {
                EventType type = rng.nextDouble() < RED_SHARE ? EventType.RED_CARD : EventType.YELLOW_CARD;
                events.add(new SimulatedEvent(minute, type, false));
            }
        }
        return new SimulatedMatch(homeGoals, awayGoals, List.copyOf(events));
    }

    private double fitnessFactor(double decay, int minute) {
        double frac = minute / (double) MINUTES;
        return Math.max(FITNESS_FLOOR, 1.0 - decay * frac * frac);
    }

    private double setPieceProbability(TeamMatchInput team) {
        return Math.max(0.0, Math.min(0.6, SET_PIECE_SHARE * (0.5 + team.setPieceThreat())));
    }

    private double perMinuteScoreChance(double attack, double defense) {
        double diff = (attack - defense) / BASE;
        double expectedGoals = Math.max(0.2, 1.4 + diff);
        return expectedGoals / MINUTES;
    }
}
```

- [ ] **Step 5: Delete the dead records**

```bash
git rm backend/src/main/java/br/com/elifootcarvoeiro/match/LineupStrength.java \
       backend/src/main/java/br/com/elifootcarvoeiro/match/SimulatedGoal.java
```

- [ ] **Step 6: Rewire `RoundSimulationService`**

Replace `RoundSimulationService.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Simulates a single league round deterministically. Builds each club's
 * TacticalProfile (managed: persisted; AI: default) and TeamMatchInput, runs the
 * engine, and persists the result and typed match events.
 *
 * Seed: saveGameId.getMostSignificantBits() ^ fixtureId.getMostSignificantBits() ^ round.
 */
@Service
public class RoundSimulationService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;

    public RoundSimulationService(SaveGameService saveGameService,
                                  SaveGameRepository saveGameRepository,
                                  CompetitionRepository competitionRepository,
                                  FixtureRepository fixtureRepository,
                                  ClubRepository clubRepository,
                                  LineupService lineupService,
                                  TacticsService tacticsService,
                                  TeamStrengthCalculator teamStrengthCalculator,
                                  MatchSimulationService matchSimulationService,
                                  MatchEventRepository matchEventRepository) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
    }

    @Transactional
    public SimulateRoundResponse simulateNextRound(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        final Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        final int nextRound = saveGame.getCurrentRound();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), nextRound);

        Map<UUID, Club> clubById = buildClubMap(saveGameId);
        final UUID managedClubId = saveGame.getManagedClubId();

        List<FixtureResultResponse> results = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            results.add(simulateFixture(fixture, saveGameId, managedClubId, clubById, nextRound));
        }

        final int newCurrentRound = nextRound + 1;
        saveGame.setCurrentRound(newCurrentRound);
        if (newCurrentRound >= competition.getTotalRounds()) {
            saveGame.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(saveGame);

        return new SimulateRoundResponse(newCurrentRound, List.copyOf(results),
                saveGame.getStatus() == SaveGameStatus.FINISHED);
    }

    private FixtureResultResponse simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                                  Map<UUID, Club> clubById, int round) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, managedClubId);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, managedClubId);

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile);
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) round;

        SimulatedMatch match = matchSimulationService.simulate(homeInput, awayInput, seed);

        fixture.recordResult(match.homeGoals(), match.awayGoals());
        fixtureRepository.save(fixture);

        saveEvents(fixture, match, homeXi, awayXi, homeProfile, awayProfile, homeClubId, awayClubId, seed);

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);
        return new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "",
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "",
                match.awayGoals());
    }

    /**
     * Persists a MatchEvent per simulated event. Open-play goals -> finishing-weighted
     * scorer; set-piece goals -> designated penalty/freekick taker (or finishing-weighted
     * fallback); cards -> tackling-weighted player. Deterministic via a fixed-seed RNG.
     */
    private void saveEvents(Fixture fixture, SimulatedMatch match,
                            List<Player> homeXi, List<Player> awayXi,
                            TacticalProfile homeProfile, TacticalProfile awayProfile,
                            UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        for (SimulatedEvent event : match.events()) {
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            Player player = switch (event.type()) {
                case GOAL -> pickWeighted(xi, Player::getFinishing, rng);
                case SET_PIECE_GOAL -> setPieceTaker(xi, profile, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
        }
    }

    private Player setPieceTaker(List<Player> xi, TacticalProfile profile, Random rng) {
        UUID takerId = profile.penaltyTakerId() != null ? profile.penaltyTakerId() : profile.freekickTakerId();
        if (takerId != null) {
            for (Player p : xi) {
                if (p.getId().equals(takerId)) {
                    return p;
                }
            }
        }
        return pickWeighted(xi, Player::getFinishing, rng);
    }

    private Player pickWeighted(List<Player> xi, ToIntFunction<Player> attribute, Random rng) {
        int totalWeight = xi.stream().mapToInt(p -> attribute.applyAsInt(p) + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player player : xi) {
            accumulated += attribute.applyAsInt(player) + 1;
            if (pick < accumulated) {
                return player;
            }
        }
        return xi.get(xi.size() - 1);
    }

    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId);
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
```

- [ ] **Step 7: Run the engine test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*MatchSimulationServiceTest'`
Expected: PASS.

- [ ] **Step 8: Run the full backend suite (verify round IT still green)**

Run: `cd backend && ./gradlew test`
Expected: PASS. `RoundSimulationServiceIT` exercises the full season loop; invariants (all fixtures played, goals-for == goals-against league-wide, champion crowned) still hold with the new engine. If any assertion pinned an exact Phase-1 scoreline, update it to assert the invariant instead (count/positivity), not the specific old number. Do **not** weaken determinism or ownership assertions.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/br/com/elifootcarvoeiro/match/{SimulatedEvent,SimulatedMatch,MatchSimulationService,RoundSimulationService}.java \
        backend/src/test/java/br/com/elifootcarvoeiro/match/MatchSimulationServiceTest.java
git add -A backend/src/main/java/br/com/elifootcarvoeiro/match/
git commit -m "feat(engine): tactics-driven match input, in-match fitness, set-piece + card events"
```

---

### Task 6: Per-round player-state lifecycle (cards → suspensions + form)

Merges the two player-state systems because they share one data-collection refactor and the same `simulateNextRound` method. The mutation logic lives in a pure `MatchOutcomeApplier` (unit-tested, no RNG); `RoundSimulationService` orchestrates and persists.

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/AttributedEvent.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/BookingResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/SuspensionResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/MatchOutcomeApplier.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/player/PlayerRepository.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/SimulateRoundResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupService.java` (eligibility-aware `bestXi`/`startingXi`)
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/RoundSimulationService.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/MatchOutcomeApplierTest.java`
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/SuspensionLifecycleIT.java`

**Interfaces:**
- Consumes: `Player` mutators (Task 2), `EventType`, `PlayerRepository`, `LineupService`.
- Produces:
  - `AttributedEvent(UUID playerId, EventType type, boolean home)`.
  - `BookingResponse(UUID playerId, String playerName, String type)`.
  - `SuspensionResponse(UUID playerId, String playerName, int matches)`.
  - `MatchOutcomeApplier.applyFixture(List<AttributedEvent> events, MatchOutcomeApplier.TeamOutcome home, MatchOutcomeApplier.TeamOutcome away, MatchOutcomeApplier.Summary acc)` and `applyNonPlayers(List<Player> nonPlayers)`; nested `TeamOutcome(List<Player> xi, int scored, int conceded, boolean managed)` and mutable `Summary` exposing `bookings():List<BookingResponse>` / `newSuspensions():List<SuspensionResponse>`.
  - `PlayerRepository.findBySaveGameId(UUID):List<Player>`.
  - `LineupService.bestXi`/`startingXi` exclude suspended players.
  - `SimulateRoundResponse(int round, List<FixtureResultResponse> results, boolean seasonFinished, List<BookingResponse> bookings, List<SuspensionResponse> newSuspensions)`.
- **Lifecycle rules:** yellow → `addYellowCard()`, every 3rd → `addSuspension(1)`; red → `addSuspension(1)`. Form for players who played: `min(2, goals) + (won?+1:lost?-1:0) + (GK/DEF: conceded==0?+1: conceded>=3?-1:0)`. Players who didn't play: regress form toward 0 by 1, and `decrementSuspension()` if banned. Bookings/new-suspensions surfaced for the managed club only.

- [ ] **Step 1: Write the failing unit test for `MatchOutcomeApplier`**

`backend/src/test/java/br/com/elifootcarvoeiro/match/MatchOutcomeApplierTest.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeApplierTest {

    private final MatchOutcomeApplier applier = new MatchOutcomeApplier();

    private Player player(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void thirdYellowTriggersOneMatchSuspension() {
        Player p = player(Position.MID);
        p.addYellowCard();
        p.addYellowCard(); // now at 2
        List<AttributedEvent> events = List.of(new AttributedEvent(p.getId(), EventType.YELLOW_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 1, 1, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 1, 1, false), acc);

        assertThat(p.getYellowCards()).isEqualTo(3);
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
        assertThat(acc.newSuspensions()).hasSize(1);
        assertThat(acc.bookings()).extracting(BookingResponse::type).containsExactly("YELLOW_CARD");
    }

    @Test
    void redCardSuspendsImmediately() {
        Player p = player(Position.DEF);
        List<AttributedEvent> events = List.of(new AttributedEvent(p.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 0, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 0, 0, false), acc);

        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
        assertThat(acc.bookings()).extracting(BookingResponse::type).containsExactly("RED_CARD");
    }

    @Test
    void scorerOnWinningTeamGainsForm() {
        Player scorer = player(Position.ATT);
        List<AttributedEvent> events = List.of(new AttributedEvent(scorer.getId(), EventType.GOAL, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(scorer), 3, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 0, 3, false), acc);

        // +1 goal, +1 win = +2
        assertThat(scorer.getForm()).isEqualTo(2);
    }

    @Test
    void defenderLosesFormOnHeavyDefeat() {
        Player def = player(Position.DEF);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(List.of(),
                new MatchOutcomeApplier.TeamOutcome(List.of(def), 0, 4, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 4, 0, false), acc);

        // -1 loss, -1 conceded>=3 = -2
        assertThat(def.getForm()).isEqualTo(-2);
    }

    @Test
    void nonPlayersRegressFormAndServeSuspension() {
        Player hot = player(Position.MID);
        hot.applyForm(3);
        Player banned = player(Position.MID);
        banned.addSuspension(1);

        applier.applyNonPlayers(List.of(hot, banned));

        assertThat(hot.getForm()).isEqualTo(2); // regressed toward 0
        assertThat(banned.getSuspensionRemaining()).isZero(); // served
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*MatchOutcomeApplierTest'`
Expected: FAIL — `MatchOutcomeApplier`/`AttributedEvent`/`BookingResponse`/`SuspensionResponse` do not exist.

- [ ] **Step 3: Create the records and the applier**

`AttributedEvent.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record AttributedEvent(UUID playerId, EventType type, boolean home) {
}
```

`BookingResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record BookingResponse(UUID playerId, String playerName, String type) {
}
```

`SuspensionResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record SuspensionResponse(UUID playerId, String playerName, int matches) {
}
```

`MatchOutcomeApplier.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure player-state mutation for a simulated round: applies cards -> suspensions
 * and form to players who played, and form regression + suspension service to
 * players who did not. No repository access; the caller persists the mutated
 * Player entities. Surfaces bookings/new-suspensions for the managed club.
 */
@Service
public class MatchOutcomeApplier {

    public record TeamOutcome(List<Player> xi, int scored, int conceded, boolean managed) {
    }

    public static final class Summary {
        private final List<BookingResponse> bookings = new ArrayList<>();
        private final List<SuspensionResponse> newSuspensions = new ArrayList<>();

        public List<BookingResponse> bookings() { return bookings; }
        public List<SuspensionResponse> newSuspensions() { return newSuspensions; }
    }

    public void applyFixture(List<AttributedEvent> events, TeamOutcome home, TeamOutcome away, Summary acc) {
        Map<UUID, Integer> goals = new HashMap<>();
        Map<UUID, Integer> yellows = new HashMap<>();
        Set<UUID> reds = new HashSet<>();
        for (AttributedEvent e : events) {
            switch (e.type()) {
                case GOAL, SET_PIECE_GOAL -> goals.merge(e.playerId(), 1, Integer::sum);
                case YELLOW_CARD -> yellows.merge(e.playerId(), 1, Integer::sum);
                case RED_CARD -> reds.add(e.playerId());
            }
        }
        applyTeam(home, goals, yellows, reds, acc);
        applyTeam(away, goals, yellows, reds, acc);
    }

    private void applyTeam(TeamOutcome team, Map<UUID, Integer> goals, Map<UUID, Integer> yellows,
                           Set<UUID> reds, Summary acc) {
        boolean won = team.scored() > team.conceded();
        boolean lost = team.scored() < team.conceded();
        for (Player p : team.xi()) {
            int yellowCount = yellows.getOrDefault(p.getId(), 0);
            for (int i = 0; i < yellowCount; i++) {
                p.addYellowCard();
                if (team.managed()) {
                    acc.bookings().add(new BookingResponse(p.getId(), p.getName(), "YELLOW_CARD"));
                }
                if (p.getYellowCards() % 3 == 0) {
                    p.addSuspension(1);
                    if (team.managed()) {
                        acc.newSuspensions().add(new SuspensionResponse(p.getId(), p.getName(), 1));
                    }
                }
            }
            if (reds.contains(p.getId())) {
                p.addSuspension(1);
                if (team.managed()) {
                    acc.bookings().add(new BookingResponse(p.getId(), p.getName(), "RED_CARD"));
                    acc.newSuspensions().add(new SuspensionResponse(p.getId(), p.getName(), 1));
                }
            }

            int delta = Math.min(2, goals.getOrDefault(p.getId(), 0));
            delta += won ? 1 : (lost ? -1 : 0);
            if (p.getPosition() == Position.GK || p.getPosition() == Position.DEF) {
                if (team.conceded() == 0) {
                    delta += 1;
                } else if (team.conceded() >= 3) {
                    delta -= 1;
                }
            }
            p.applyForm(delta);
        }
    }

    public void applyNonPlayers(List<Player> nonPlayers) {
        for (Player p : nonPlayers) {
            if (p.getForm() > 0) {
                p.applyForm(-1);
            } else if (p.getForm() < 0) {
                p.applyForm(1);
            }
            if (p.getSuspensionRemaining() > 0) {
                p.decrementSuspension();
            }
        }
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*MatchOutcomeApplierTest'`
Expected: PASS.

- [ ] **Step 5: Add the repository query + eligibility filtering + extended response**

In `PlayerRepository.java`, add:
```java
    List<Player> findBySaveGameId(UUID saveGameId);
```

Replace `SimulateRoundResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.List;

public record SimulateRoundResponse(
        int round,
        List<FixtureResultResponse> results,
        boolean seasonFinished,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions
) {
}
```

In `LineupService.java`, make `bestXi` and `startingXi` eligibility-aware. Replace the `bestXi` body's filtering so suspended players are excluded before selection:
```java
    public List<Player> bestXi(UUID clubId) {
        List<Player> allPlayers = playerRepository.findByClubId(clubId).stream()
                .filter(Player::isEligible)
                .toList();

        Player bestGk = allPlayers.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseThrow(() -> new ApiException(
                        "NO_GK_FOUND",
                        "Club " + clubId + " has no eligible goalkeeper",
                        HttpStatus.UNPROCESSABLE_CONTENT));

        List<Player> topOutfield = allPlayers.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .limit(10)
                .toList();

        return new java.util.ArrayList<>(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(bestGk), topOutfield.stream())
                        .toList()
        );
    }
```
In `startingXi`, drop suspended players from the persisted XI (the managed club then plays short until the user re-picks). Change the mapping so the resolved list is filtered:
```java
    public List<Player> startingXi(UUID saveGameId, UUID clubId) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> {
                    List<UUID> playerIds = lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                            .filter(LineupPlayer::isStarting)
                            .map(LineupPlayer::getPlayerId)
                            .toList();
                    return playerIds.stream()
                            .map(playerId -> playerRepository.findById(playerId)
                                    .orElseThrow(() -> new ApiException(
                                            "PLAYER_NOT_FOUND",
                                            "Player " + playerId + " not found",
                                            HttpStatus.NOT_FOUND)))
                            .filter(Player::isEligible)
                            .collect(java.util.stream.Collectors.toList());
                })
                .orElseGet(() -> bestXi(clubId));
    }
```

- [ ] **Step 6: Wire the lifecycle into `RoundSimulationService`**

Replace `RoundSimulationService.java` with the version below (extends Task 5: `saveEvents` now returns the attributed events, the applier runs per fixture, non-players are processed, and the summary rides on the response):
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Simulates a single league round deterministically, then applies the per-round
 * player-state lifecycle (cards -> suspensions, form) via {@link MatchOutcomeApplier}.
 */
@Service
public class RoundSimulationService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;

    public RoundSimulationService(SaveGameService saveGameService,
                                  SaveGameRepository saveGameRepository,
                                  CompetitionRepository competitionRepository,
                                  FixtureRepository fixtureRepository,
                                  ClubRepository clubRepository,
                                  PlayerRepository playerRepository,
                                  LineupService lineupService,
                                  TacticsService tacticsService,
                                  TeamStrengthCalculator teamStrengthCalculator,
                                  MatchSimulationService matchSimulationService,
                                  MatchEventRepository matchEventRepository,
                                  MatchOutcomeApplier matchOutcomeApplier) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
    }

    private record FixtureSim(FixtureResultResponse response, List<Player> homeXi, List<Player> awayXi,
                             int homeGoals, int awayGoals, List<AttributedEvent> events) {
    }

    @Transactional
    public SimulateRoundResponse simulateNextRound(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        final Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        final int nextRound = saveGame.getCurrentRound();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), nextRound);

        Map<UUID, Club> clubById = buildClubMap(saveGameId);
        final UUID managedClubId = saveGame.getManagedClubId();

        List<FixtureResultResponse> results = new ArrayList<>();
        Set<UUID> playedIds = new HashSet<>();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        List<Player> toPersist = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            FixtureSim sim = simulateFixture(fixture, saveGameId, managedClubId, clubById, nextRound);
            results.add(sim.response());

            boolean homeManaged = sim.homeXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            boolean awayManaged = sim.awayXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            matchOutcomeApplier.applyFixture(sim.events(),
                    new MatchOutcomeApplier.TeamOutcome(sim.homeXi(), sim.homeGoals(), sim.awayGoals(), homeManaged),
                    new MatchOutcomeApplier.TeamOutcome(sim.awayXi(), sim.awayGoals(), sim.homeGoals(), awayManaged),
                    summary);

            sim.homeXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
            sim.awayXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
        }

        List<Player> nonPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !playedIds.contains(p.getId()))
                .toList();
        matchOutcomeApplier.applyNonPlayers(nonPlayers);
        toPersist.addAll(nonPlayers);
        playerRepository.saveAll(toPersist);

        final int newCurrentRound = nextRound + 1;
        saveGame.setCurrentRound(newCurrentRound);
        if (newCurrentRound >= competition.getTotalRounds()) {
            saveGame.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(saveGame);

        return new SimulateRoundResponse(newCurrentRound, List.copyOf(results),
                saveGame.getStatus() == SaveGameStatus.FINISHED,
                List.copyOf(summary.bookings()), List.copyOf(summary.newSuspensions()));
    }

    private FixtureSim simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                       Map<UUID, Club> clubById, int round) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, managedClubId);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, managedClubId);

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile);
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) round;

        SimulatedMatch match = matchSimulationService.simulate(homeInput, awayInput, seed);

        fixture.recordResult(match.homeGoals(), match.awayGoals());
        fixtureRepository.save(fixture);

        List<AttributedEvent> attributed = saveEvents(fixture, match, homeXi, awayXi,
                homeProfile, awayProfile, homeClubId, awayClubId, seed);

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);
        FixtureResultResponse response = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "",
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "",
                match.awayGoals());
        return new FixtureSim(response, homeXi, awayXi, match.homeGoals(), match.awayGoals(), attributed);
    }

    private List<AttributedEvent> saveEvents(Fixture fixture, SimulatedMatch match,
                                             List<Player> homeXi, List<Player> awayXi,
                                             TacticalProfile homeProfile, TacticalProfile awayProfile,
                                             UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        List<AttributedEvent> attributed = new ArrayList<>();
        for (SimulatedEvent event : match.events()) {
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            Player player = switch (event.type()) {
                case GOAL -> pickWeighted(xi, Player::getFinishing, rng);
                case SET_PIECE_GOAL -> setPieceTaker(xi, profile, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
            attributed.add(new AttributedEvent(player.getId(), event.type(), event.home()));
        }
        return attributed;
    }

    private Player setPieceTaker(List<Player> xi, TacticalProfile profile, Random rng) {
        UUID takerId = profile.penaltyTakerId() != null ? profile.penaltyTakerId() : profile.freekickTakerId();
        if (takerId != null) {
            for (Player p : xi) {
                if (p.getId().equals(takerId)) {
                    return p;
                }
            }
        }
        return pickWeighted(xi, Player::getFinishing, rng);
    }

    private Player pickWeighted(List<Player> xi, ToIntFunction<Player> attribute, Random rng) {
        int totalWeight = xi.stream().mapToInt(p -> attribute.applyAsInt(p) + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player player : xi) {
            accumulated += attribute.applyAsInt(player) + 1;
            if (pick < accumulated) {
                return player;
            }
        }
        return xi.get(xi.size() - 1);
    }

    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId);
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
```

- [ ] **Step 7: Write the suspension-lifecycle integration test**

`backend/src/test/java/br/com/elifootcarvoeiro/match/SuspensionLifecycleIT.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SuspensionLifecycleIT {

    @Autowired RoundSimulationService roundSimulationService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("susp_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void suspendedPlayerIsExcludedFromBestXi() {
        // Given a player in a club is suspended
        Career career = createCareer();
        UUID clubId = career.save().managedClubId();
        Player victim = lineupService.bestXi(clubId).get(5);
        victim.addSuspension(1);
        playerRepository.save(victim);

        // When picking the best XI
        List<Player> xi = lineupService.bestXi(clubId);

        // Then the suspended player is not in it
        assertThat(xi).extracting(Player::getId).doesNotContain(victim.getId());
    }

    @Test
    void servingASuspensionClearsItAfterOneRound() {
        // Given a benched player (not in any XI) carrying a 1-match ban
        Career career = createCareer();
        UUID saveGameId = career.save().id();
        UUID clubId = career.save().managedClubId();

        List<Player> xi = lineupService.bestXi(clubId);
        Player benched = playerRepository.findByClubId(clubId).stream()
                .filter(p -> xi.stream().noneMatch(s -> s.getId().equals(p.getId())))
                .findFirst().orElseThrow();
        benched.addSuspension(1);
        playerRepository.save(benched);

        // When a round is simulated (the benched player sits out)
        roundSimulationService.simulateNextRound(saveGameId, career.userId());

        // Then the ban is served
        Player reloaded = playerRepository.findById(benched.getId()).orElseThrow();
        assertThat(reloaded.getSuspensionRemaining()).isZero();
    }
}
```

- [ ] **Step 8: Run the new tests, then the full suite**

Run: `cd backend && ./gradlew test --tests '*MatchOutcomeApplierTest' --tests '*SuspensionLifecycleIT'`
Expected: PASS.
Run: `cd backend && ./gradlew test`
Expected: PASS (RoundSimulationServiceIT still green; its assertions on round/played/zero-sum hold; it ignores the new response fields).

- [ ] **Step 9: Commit**

```bash
git add -A backend/src/main/java/br/com/elifootcarvoeiro backend/src/test/java/br/com/elifootcarvoeiro/match
git commit -m "feat(match): per-round lifecycle - cards->suspensions, form, eligibility filtering"
```

---

### Task 7: Lineup roles + extended squad/lineup DTOs

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupStarter.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/SetLineupRequest.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/PlayerResponse.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupService.java`
- Modify: `backend/src/test/java/br/com/elifootcarvoeiro/match/LineupServiceIT.java`

**Interfaces:**
- Consumes: `PlayerRole` (`valueOf`, `isValidFor`), `Player` state getters, `LineupPlayer.create(.., PlayerRole)` (Task 2).
- Produces:
  - `LineupStarter(UUID playerId, String role)`.
  - `SetLineupRequest(String formation, List<LineupStarter> starters)`.
  - `LineupResponse(UUID lineupId, UUID clubId, String formation, List<LineupStarter> starters)`.
  - `PlayerResponse(UUID id, String name, String position, int age, int overall, int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina, int form, int yellowCards, int suspensionRemaining, boolean eligible, String role)`.
  - New validation codes: `SUSPENDED_PLAYER` (422), `INVALID_ROLE` (422).

- [ ] **Step 1: Write/adjust the failing tests**

Create `LineupStarter.java`:
```java
package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LineupStarter(@NotNull UUID playerId, @NotBlank String role) {
}
```

Update `LineupServiceIT.java`. Replace the helper `buildValidXiIds` and add a starter helper + update the three `new SetLineupRequest(...)` constructions and assertions:

Replace the helper region (the `buildValidXiIds` method) with:
```java
    private List<LineupStarter> buildValidStarters(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(
                        p.getId(),
                        br.com.elifootcarvoeiro.player.PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());
    }

    private List<UUID> idsOf(List<LineupStarter> starters) {
        return starters.stream().map(LineupStarter::playerId).collect(Collectors.toList());
    }
```

In `settingValidXiPersistsAndSubsequentCallUpdatesRatherThanDuplicating`, replace the body's request/assertions:
```java
        List<LineupStarter> starters = buildValidStarters(clubId);
        SetLineupRequest req = new SetLineupRequest("4-4-2", starters);

        LineupResponse response = lineupService.setLineup(saveGameId, clubId, req, userId);

        assertThat(response.lineupId()).isNotNull();
        assertThat(response.clubId()).isEqualTo(clubId);
        assertThat(response.formation()).isEqualTo("4-4-2");
        assertThat(idsOf(response.starters())).containsExactlyInAnyOrderElementsOf(idsOf(starters));

        Lineup persistedLineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new AssertionError("Expected lineup to exist"));
        UUID lineupId = persistedLineup.getId();

        List<LineupPlayer> players = lineupPlayerRepository.findByLineupId(lineupId);
        assertThat(players).hasSize(11);
        assertThat(players).allMatch(LineupPlayer::isStarting);

        SetLineupRequest req2 = new SetLineupRequest("4-3-3", starters);
        LineupResponse response2 = lineupService.setLineup(saveGameId, clubId, req2, userId);
        assertThat(lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)).isPresent();
        assertThat(response2.formation()).isEqualTo("4-3-3");
        assertThat(lineupPlayerRepository.findByLineupId(lineupId)).hasSize(11);
```

In `lineupWithNoGkIsRejected`, build no-GK starters:
```java
        List<LineupStarter> noGk = lineupService.getSquad(career.id(), clubId, userId).players().stream()
                .filter(p -> !p.position().equals("GK"))
                .limit(11)
                .map(p -> new LineupStarter(p.id(), "BOX_TO_BOX"))
                .collect(Collectors.toList());
        SetLineupRequest req = new SetLineupRequest("4-4-2", noGk);
```
(keep the existing `assertThatThrownBy(... INVALID_LINEUP ... 422)` assertion).

In `lineupWithWrongCountIsRejected`:
```java
        List<LineupStarter> ten = buildValidStarters(clubId).subList(0, 10);
        SetLineupRequest req = new SetLineupRequest("4-4-2", ten);
```

In `lineupWithForeignPlayerIsRejected`, replace the XI build:
```java
        List<LineupStarter> validXi1 = buildValidStarters(clubId1);
        List<br.com.elifootcarvoeiro.player.Player> club2Players = playerRepository.findByClubId(clubId2);
        assertThat(club2Players).isNotEmpty();
        List<LineupStarter> withForeigner = new java.util.ArrayList<>(validXi1);
        withForeigner.set(10, new LineupStarter(club2Players.get(0).getId(), "POACHER"));
        SetLineupRequest req = new SetLineupRequest("4-4-2", withForeigner);
```
(keep the `assertThatThrownBy(... INVALID_LINEUP ... 422)` on `career1`).

Add two new tests at the end of the class (before the private helpers):
```java
    @Test
    void suspendedPlayerCannotBeSelected() {
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID clubId = career.managedClubId();

        List<LineupStarter> starters = buildValidStarters(clubId);
        // suspend the 6th starter
        br.com.elifootcarvoeiro.player.Player victim =
                playerRepository.findById(starters.get(5).playerId()).orElseThrow();
        victim.addSuspension(1);
        playerRepository.save(victim);

        assertThatThrownBy(() ->
                lineupService.setLineup(saveGameId, clubId, new SetLineupRequest("4-4-2", starters), career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("SUSPENDED_PLAYER");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void roleInvalidForPositionIsRejected() {
        Career career = createCareer();
        UUID clubId = career.managedClubId();
        List<LineupStarter> starters = new java.util.ArrayList<>(buildValidStarters(clubId));
        // Force the GK starter to carry an outfield role -> invalid
        int gkIndex = -1;
        List<PlayerResponse> squad = lineupService.getSquad(career.id(), clubId, career.userId()).players();
        for (int i = 0; i < starters.size(); i++) {
            UUID pid = starters.get(i).playerId();
            if (squad.stream().anyMatch(s -> s.id().equals(pid) && s.position().equals("GK"))) {
                gkIndex = i;
                break;
            }
        }
        starters.set(gkIndex, new LineupStarter(starters.get(gkIndex).playerId(), "POACHER"));

        assertThatThrownBy(() ->
                lineupService.setLineup(career.id(), clubId, new SetLineupRequest("4-4-2", starters), career.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_ROLE");
                    assertThat(apiEx.getStatus().value()).isEqualTo(422);
                });
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests '*LineupServiceIT'`
Expected: FAIL — compilation (new DTO shapes / methods not present).

- [ ] **Step 3: Update the DTOs**

Replace `SetLineupRequest.java`:
```java
package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetLineupRequest(
        @NotBlank String formation,
        @NotEmpty List<LineupStarter> starters) {
}
```

Replace `LineupResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.List;
import java.util.UUID;

public record LineupResponse(
        UUID lineupId,
        UUID clubId,
        String formation,
        List<LineupStarter> starters
) {
}
```

Replace `PlayerResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record PlayerResponse(
        UUID id,
        String name,
        String position,
        int age,
        int overall,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        int form,
        int yellowCards,
        int suspensionRemaining,
        boolean eligible,
        String role
) {
}
```

- [ ] **Step 4: Update `LineupService`**

Add imports:
```java
import br.com.elifootcarvoeiro.player.PlayerRole;
import java.util.HashMap;
import java.util.Map;
```

Replace `getSquad` + `toPlayerResponse` so role + state are included:
```java
    public SquadResponse getSquad(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        Map<UUID, PlayerRole> roleByPlayer = currentRoles(saveGameId, clubId);
        List<PlayerResponse> responses = playerRepository.findByClubId(clubId).stream()
                .map(p -> toPlayerResponse(p, roleByPlayer.get(p.getId())))
                .toList();
        return new SquadResponse(responses);
    }

    private Map<UUID, PlayerRole> currentRoles(UUID saveGameId, UUID clubId) {
        Map<UUID, PlayerRole> map = new HashMap<>();
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).ifPresent(lineup ->
                lineupPlayerRepository.findByLineupId(lineup.getId()).forEach(lp ->
                        map.put(lp.getPlayerId(), lp.getRole())));
        return map;
    }

    private PlayerResponse toPlayerResponse(Player p, PlayerRole role) {
        return new PlayerResponse(
                p.getId(),
                p.getName(),
                p.getPosition().name(),
                p.getAge(),
                p.getOverall(),
                p.getPace(),
                p.getFinishing(),
                p.getPassing(),
                p.getTackling(),
                p.getGoalkeeping(),
                p.getStamina(),
                p.getForm(),
                p.getYellowCards(),
                p.getSuspensionRemaining(),
                p.isEligible(),
                role != null ? role.name() : null);
    }
```

Replace `setLineup` so it validates/persists roles, rejects suspended players, and returns the new response:
```java
    @Transactional
    public LineupResponse setLineup(UUID saveGameId, UUID clubId, SetLineupRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        List<LineupStarter> starters = req.starters();
        List<UUID> startingIds = starters == null ? List.of()
                : starters.stream().map(LineupStarter::playerId).toList();

        validatePlayerCount(startingIds);

        List<Player> clubPlayers = playerRepository.findByClubId(clubId);
        validatePlayersOwnedByClub(startingIds, clubPlayers);
        validateGkCount(startingIds, clubPlayers);
        validateNoSuspended(startingIds, clubPlayers);
        Map<UUID, PlayerRole> resolvedRoles = validateAndParseRoles(starters, clubPlayers);

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).orElse(null);
        if (lineup == null) {
            lineup = Lineup.create(saveGameId, clubId, req.formation());
            lineupRepository.save(lineup);
        } else {
            lineup.setFormation(req.formation());
            lineupRepository.save(lineup);
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
        }

        final UUID lineupId = lineup.getId();
        for (LineupStarter starter : starters) {
            lineupPlayerRepository.save(
                    LineupPlayer.create(lineupId, starter.playerId(), true, resolvedRoles.get(starter.playerId())));
        }

        return new LineupResponse(lineupId, clubId, req.formation(), List.copyOf(starters));
    }

    private void validateNoSuspended(List<UUID> startingIds, List<Player> clubPlayers) {
        Set<UUID> startingSet = Set.copyOf(startingIds);
        List<UUID> suspended = clubPlayers.stream()
                .filter(p -> startingSet.contains(p.getId()))
                .filter(p -> !p.isEligible())
                .map(Player::getId)
                .toList();
        if (!suspended.isEmpty()) {
            throw new ApiException("SUSPENDED_PLAYER",
                    "These players are suspended and cannot be selected: " + suspended,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Map<UUID, PlayerRole> validateAndParseRoles(List<LineupStarter> starters, List<Player> clubPlayers) {
        Map<UUID, Player> byId = clubPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p));
        Map<UUID, PlayerRole> resolved = new HashMap<>();
        for (LineupStarter starter : starters) {
            PlayerRole role;
            try {
                role = PlayerRole.valueOf(starter.role());
            } catch (IllegalArgumentException ex) {
                throw new ApiException("INVALID_ROLE",
                        "Unknown role: " + starter.role(), HttpStatus.UNPROCESSABLE_CONTENT);
            }
            Player player = byId.get(starter.playerId());
            if (player != null && !role.isValidFor(player.getPosition())) {
                throw new ApiException("INVALID_ROLE",
                        "Role " + role + " is not valid for position " + player.getPosition(),
                        HttpStatus.UNPROCESSABLE_CONTENT);
            }
            resolved.put(starter.playerId(), role);
        }
        return resolved;
    }
```
(Keep the existing `validatePlayerCount`, `validatePlayersOwnedByClub`, `validateGkCount`, `requireOwnedClub`, `clubNotFound`, `startingXi`, `bestXi` methods — only `getSquad`/`toPlayerResponse`/`setLineup` change here, plus the new private validators.)

- [ ] **Step 5: Run tests**

Run: `cd backend && ./gradlew test --tests '*LineupServiceIT'`
Expected: PASS.
Run: `cd backend && ./gradlew test`
Expected: PASS (whole backend suite).

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/br/com/elifootcarvoeiro/match backend/src/test/java/br/com/elifootcarvoeiro/match/LineupServiceIT.java
git commit -m "feat(api): per-starter roles, suspended-player guard, squad state in responses"
```

---

### Task 8: Tactics endpoints (PUT/GET)

**Files:**
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/SetTacticsRequest.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TacticsResponse.java`
- Create: `backend/src/main/java/br/com/elifootcarvoeiro/match/TacticsController.java`
- Modify: `backend/src/main/java/br/com/elifootcarvoeiro/match/LineupService.java` (add `setTactics`, `getTactics`)
- Test: `backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsApiIT.java`

**Interfaces:**
- Consumes: `Mentality`/`Pressing`/`Marking` (`valueOf`), `Lineup` setters/getters (Task 2), `requireOwnedClub`.
- Produces:
  - `SetTacticsRequest(String mentality, String pressing, String marking, UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId)`.
  - `TacticsResponse(String mentality, String pressing, String marking, UUID penaltyTakerId, UUID freekickTakerId, UUID cornerTakerId)`.
  - `LineupService.setTactics(UUID saveGameId, UUID clubId, SetTacticsRequest req, UUID userId):TacticsResponse`, `LineupService.getTactics(UUID saveGameId, UUID clubId, UUID userId):TacticsResponse`.
  - `PUT/GET /api/v1/save-games/{saveGameId}/clubs/{clubId}/tactics`.
  - New codes: `INVALID_TACTICS` (422), `LINEUP_REQUIRED` (422).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsApiIT.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsApiIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("tac_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private List<LineupStarter> starters(UUID clubId) {
        return lineupService.bestXi(clubId).stream()
                .map(p -> new LineupStarter(p.getId(),
                        br.com.elifootcarvoeiro.player.PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
    }

    @Test
    void setAndGetTacticsRoundTrips() {
        Career c = createCareer();
        List<LineupStarter> xi = starters(c.clubId());
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi), c.userId());
        UUID taker = xi.get(10).playerId();

        TacticsResponse saved = lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("ATTACKING", "HIGH", "MAN_TIGHT", taker, taker, taker), c.userId());
        assertThat(saved.mentality()).isEqualTo("ATTACKING");
        assertThat(saved.pressing()).isEqualTo("HIGH");
        assertThat(saved.penaltyTakerId()).isEqualTo(taker);

        TacticsResponse loaded = lineupService.getTactics(c.id(), c.clubId(), c.userId());
        assertThat(loaded.marking()).isEqualTo("MAN_TIGHT");
        assertThat(loaded.freekickTakerId()).isEqualTo(taker);
    }

    @Test
    void getReturnsDefaultsWhenNoLineupYet() {
        Career c = createCareer();
        TacticsResponse loaded = lineupService.getTactics(c.id(), c.clubId(), c.userId());
        assertThat(loaded.mentality()).isEqualTo("BALANCED");
        assertThat(loaded.pressing()).isEqualTo("MEDIUM");
        assertThat(loaded.marking()).isEqualTo("ZONAL");
        assertThat(loaded.penaltyTakerId()).isNull();
    }

    @Test
    void invalidEnumIsRejected() {
        Career c = createCareer();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters(c.clubId())), c.userId());
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("WILD", "HIGH", "ZONAL", null, null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TACTICS"));
    }

    @Test
    void settingTacticsBeforeLineupIsRejected() {
        Career c = createCareer();
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", null, null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("LINEUP_REQUIRED"));
    }

    @Test
    void takerNotInXiIsRejected() {
        Career c = createCareer();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters(c.clubId())), c.userId());
        assertThatThrownBy(() -> lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", UUID.randomUUID(), null, null), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TACTICS"));
    }

    @Test
    void foreignUserCannotReadTactics() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> lineupService.getTactics(owner.id(), owner.clubId(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests '*TacticsApiIT'`
Expected: FAIL — types/methods missing.

- [ ] **Step 3: Create the DTOs**

`SetTacticsRequest.java`:
```java
package br.com.elifootcarvoeiro.match;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SetTacticsRequest(
        @NotBlank String mentality,
        @NotBlank String pressing,
        @NotBlank String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
```

`TacticsResponse.java`:
```java
package br.com.elifootcarvoeiro.match;

import java.util.UUID;

public record TacticsResponse(
        String mentality,
        String pressing,
        String marking,
        UUID penaltyTakerId,
        UUID freekickTakerId,
        UUID cornerTakerId) {
}
```

- [ ] **Step 4: Add `setTactics` / `getTactics` to `LineupService`**

Add imports:
```java
import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Pressing;
```
Add methods:
```java
    @Transactional
    public TacticsResponse setTactics(UUID saveGameId, UUID clubId, SetTacticsRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        Mentality mentality = parseMentality(req.mentality());
        Pressing pressing = parsePressing(req.pressing());
        Marking marking = parseMarking(req.marking());

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new ApiException("LINEUP_REQUIRED",
                        "Set a lineup before configuring tactics", HttpStatus.UNPROCESSABLE_CONTENT));

        Set<UUID> xiIds = lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .map(LineupPlayer::getPlayerId)
                .collect(Collectors.toSet());
        requireTakerInXi(req.penaltyTakerId(), xiIds);
        requireTakerInXi(req.freekickTakerId(), xiIds);
        requireTakerInXi(req.cornerTakerId(), xiIds);

        lineup.setMentality(mentality);
        lineup.setPressing(pressing);
        lineup.setMarking(marking);
        lineup.setPenaltyTakerId(req.penaltyTakerId());
        lineup.setFreekickTakerId(req.freekickTakerId());
        lineup.setCornerTakerId(req.cornerTakerId());
        lineupRepository.save(lineup);

        return toTacticsResponse(lineup);
    }

    public TacticsResponse getTactics(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(this::toTacticsResponse)
                .orElseGet(() -> new TacticsResponse(
                        Mentality.BALANCED.name(), Pressing.MEDIUM.name(), Marking.ZONAL.name(),
                        null, null, null));
    }

    private TacticsResponse toTacticsResponse(Lineup lineup) {
        return new TacticsResponse(
                lineup.getMentality().name(), lineup.getPressing().name(), lineup.getMarking().name(),
                lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }

    private void requireTakerInXi(UUID takerId, Set<UUID> xiIds) {
        if (takerId != null && !xiIds.contains(takerId)) {
            throw new ApiException("INVALID_TACTICS",
                    "Set-piece taker " + takerId + " is not in the starting XI",
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Mentality parseMentality(String v) {
        try { return Mentality.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown mentality: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Pressing parsePressing(String v) {
        try { return Pressing.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown pressing: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Marking parseMarking(String v) {
        try { return Marking.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown marking: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
```

- [ ] **Step 5: Create the controller**

`TacticsController.java`:
```java
package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/tactics")
public class TacticsController {

    private final LineupService lineupService;

    public TacticsController(LineupService lineupService) {
        this.lineupService = lineupService;
    }

    @GetMapping
    public ResponseEntity<TacticsResponse> getTactics(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.getTactics(saveGameId, clubId, userId));
    }

    @PutMapping
    public ResponseEntity<TacticsResponse> setTactics(
            @PathVariable UUID saveGameId,
            @PathVariable UUID clubId,
            @Valid @RequestBody SetTacticsRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(lineupService.setTactics(saveGameId, clubId, request, userId));
    }
}
```

- [ ] **Step 6: Run tests, then full suite**

Run: `cd backend && ./gradlew test --tests '*TacticsApiIT'`
Expected: PASS.
Run: `cd backend && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/java/br/com/elifootcarvoeiro/match backend/src/test/java/br/com/elifootcarvoeiro/match/TacticsApiIT.java
git commit -m "feat(api): tactics endpoints (set/get mentality, pressing, marking, set-piece takers)"
```

---

### Task 9: Frontend types + client + squad page (roles & player state)

Types ripple into the squad page, so they ship together to keep the frontend compiling and green.

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/career.ts`
- Modify: `frontend/src/features/squad/SquadPage.tsx`
- Modify: `frontend/src/features/squad/SquadPage.test.tsx`

**Interfaces:**
- Produces (types): `LineupStarter`, updated `SetLineupRequest`/`LineupResponse`/`PlayerResponse`/`SimulateRoundResponse`, `TacticsRequest`, `TacticsResponse`, `Booking`, `Suspension`, and the `Mentality`/`Pressing`/`Marking`/`PlayerRole` string unions; client `getTactics`/`setTactics`.

- [ ] **Step 1: Update `types.ts`**

Replace the `PlayerResponse`, `SetLineupRequest`, `LineupResponse`, and `SimulateRoundResponse` blocks and add the new types:
```typescript
export type Mentality = 'DEFENSIVE' | 'BALANCED' | 'ATTACKING'
export type Pressing = 'LOW' | 'MEDIUM' | 'HIGH'
export type Marking = 'ZONAL' | 'MAN_TIGHT'
export type PlayerRole =
  | 'GOALKEEPER'
  | 'STOPPER' | 'FULL_BACK' | 'BALL_PLAYING_DEF'
  | 'HOLDING_MID' | 'BOX_TO_BOX' | 'PLAYMAKER'
  | 'POACHER' | 'TARGET_MAN' | 'WINGER'

export type PlayerResponse = {
  id: string
  name: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  age: number
  overall: number
  pace: number
  finishing: number
  passing: number
  tackling: number
  goalkeeping: number
  stamina: number
  form: number
  yellowCards: number
  suspensionRemaining: number
  eligible: boolean
  role: PlayerRole | null
}

export type LineupStarter = {
  playerId: string
  role: PlayerRole
}

export type SetLineupRequest = {
  formation: string
  starters: LineupStarter[]
}

export type LineupResponse = {
  lineupId: string
  clubId: string
  formation: string
  starters: LineupStarter[]
}

export type TacticsRequest = {
  mentality: Mentality
  pressing: Pressing
  marking: Marking
  penaltyTakerId: string | null
  freekickTakerId: string | null
  cornerTakerId: string | null
}

export type TacticsResponse = TacticsRequest

export type Booking = {
  playerId: string
  playerName: string
  type: 'YELLOW_CARD' | 'RED_CARD'
}

export type Suspension = {
  playerId: string
  playerName: string
  matches: number
}

export type SimulateRoundResponse = {
  round: number
  results: FixtureResultResponse[]
  seasonFinished: boolean
  bookings: Booking[]
  newSuspensions: Suspension[]
}
```
(Leave `SaveGameResponse`, `SquadResponse`, `StandingRow`, `FixtureResultResponse` unchanged; `SquadResponse` already references `PlayerResponse`.)

- [ ] **Step 2: Update `career.ts`**

Add to imports and add two functions:
```typescript
import { apiGet, apiPost, apiPut } from './client'
import type {
  SaveGameResponse,
  SquadResponse,
  SetLineupRequest,
  LineupResponse,
  SimulateRoundResponse,
  StandingRow,
  TacticsRequest,
  TacticsResponse,
} from './types'
```
```typescript
export function getTactics(
  saveGameId: string,
  clubId: string,
  token: string,
): Promise<TacticsResponse> {
  return apiGet<TacticsResponse>(`/save-games/${saveGameId}/clubs/${clubId}/tactics`, token)
}

export function setTactics(
  saveGameId: string,
  clubId: string,
  req: TacticsRequest,
  token: string,
): Promise<TacticsResponse> {
  return apiPut<TacticsResponse>(`/save-games/${saveGameId}/clubs/${clubId}/tactics`, req, token)
}
```
(`setLineup` keeps its signature; the `SetLineupRequest` type change flows through.)

- [ ] **Step 3: Update `SquadPage.test.tsx` (write the failing test first)**

Replace the GK/outfield fixtures so they include the new fields, fix the `setLineup` mock, and add role + suspension assertions. Apply these edits:

Add a state-defaults spread to every fixture. Change `GK`, `makeOutfield`, and `GK2` to include:
```typescript
const STATE = { form: 0, yellowCards: 0, suspensionRemaining: 0, eligible: true, role: null }
```
and spread `...STATE` into `GK`, the object returned by `makeOutfield`, and `GK2` (e.g. `const GK: PlayerResponse = { id: 'p-gk', name: 'Goleiro Teste', position: 'GK', age: 25, overall: 72, pace: 50, finishing: 20, passing: 40, tackling: 30, goalkeeping: 80, stamina: 60, ...STATE }`).

Change the `setLineup` mock return to the new shape:
```typescript
    mockSetLineup.mockResolvedValue({
      lineupId: 'l-1',
      clubId: 'club-1',
      formation: '4-3-3',
      starters: [],
    })
```

Add a suspended player and two new tests:
```typescript
const SUSPENDED = makeOutfield('p-susp', 'Banido Um', 'MID')
SUSPENDED.eligible = false
SUSPENDED.suspensionRemaining = 1
```
Add `SUSPENDED` to the `squad.players` array (the player's name deliberately avoids the word "suspenso" so the badge assertion stays unambiguous). Then add:
```typescript
  it('does not let a suspended player be selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Banido Um'))
    const checkbox = screen.getByRole('checkbox', { name: /banido um/i })
    expect(checkbox).toBeDisabled()
    expect(screen.getByText(/\(Suspenso 1\)/i)).toBeInTheDocument()
  })

  it('shows a role selector for a selected outfield player', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    fireEvent.click(screen.getByRole('checkbox', { name: /defensor um/i }))
    expect(screen.getByRole('combobox', { name: /função de defensor um/i })).toBeInTheDocument()
  })
```

- [ ] **Step 4: Run the squad test to verify failure**

Run: `cd frontend && npm run test -- SquadPage`
Expected: FAIL (role selector absent; suspended checkbox not disabled; type mismatches).

- [ ] **Step 5: Rewrite `SquadPage.tsx`**

```tsx
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSquad, setLineup } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { PlayerResponse, PlayerRole, LineupStarter } from '../../api/types'

const FORMATIONS = ['4-4-2', '4-3-3', '3-5-2', '4-2-3-1', '5-3-2']

const ROLES_BY_POSITION: Record<PlayerResponse['position'], PlayerRole[]> = {
  GK: ['GOALKEEPER'],
  DEF: ['STOPPER', 'FULL_BACK', 'BALL_PLAYING_DEF'],
  MID: ['HOLDING_MID', 'BOX_TO_BOX', 'PLAYMAKER'],
  ATT: ['POACHER', 'TARGET_MAN', 'WINGER'],
}

const DEFAULT_ROLE: Record<PlayerResponse['position'], PlayerRole> = {
  GK: 'GOALKEEPER',
  DEF: 'FULL_BACK',
  MID: 'BOX_TO_BOX',
  ATT: 'POACHER',
}

function positionLabel(pos: PlayerResponse['position']): string {
  return { GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }[pos]
}

function formLabel(form: number): string {
  if (form > 0) return `▲ +${form}`
  if (form < 0) return `▼ ${form}`
  return '–'
}

function validate(selected: string[], players: PlayerResponse[]): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const gkCount = players.filter(p => selected.includes(p.id) && p.position === 'GK').length
  if (gkCount !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
  }
  return null
}

export function SquadPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const [formation, setFormation] = useState('4-3-3')
  const [selected, setSelected] = useState<string[]>([])
  const [roles, setRoles] = useState<Record<string, PlayerRole>>({})
  const [saveSuccess, setSaveSuccess] = useState(false)

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  const players = data?.players ?? []

  const mutation = useMutation({
    mutationFn: () => {
      const starters: LineupStarter[] = selected.map(id => {
        const player = players.find(p => p.id === id)!
        return { playerId: id, role: roles[id] ?? DEFAULT_ROLE[player.position] }
      })
      return setLineup(saveGameId, clubId, { formation, starters }, token ?? '')
    },
    onSuccess: () => {
      setSaveSuccess(true)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
    },
  })

  const validationError = players.length > 0 ? validate(selected, players) : null
  const canSave = validationError === null

  function togglePlayer(player: PlayerResponse) {
    if (!player.eligible) return
    setSaveSuccess(false)
    setSelected(prev => {
      if (prev.includes(player.id)) {
        return prev.filter(p => p !== player.id)
      }
      if (prev.length >= 11) return prev
      return [...prev, player.id]
    })
    setRoles(prev =>
      prev[player.id] ? prev : { ...prev, [player.id]: DEFAULT_ROLE[player.position] },
    )
  }

  function changeRole(player: PlayerResponse, role: PlayerRole) {
    setRoles(prev => ({ ...prev, [player.id]: role }))
  }

  if (isLoading) return <p>Carregando elenco…</p>
  if (error) return <p>Erro ao carregar elenco.</p>

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Escalação</h2>

      <div style={{ marginBottom: '1rem' }}>
        <label htmlFor="formation-select">Formação: </label>
        <select id="formation-select" value={formation} onChange={e => setFormation(e.target.value)}>
          {FORMATIONS.map(f => (
            <option key={f} value={f}>{f}</option>
          ))}
        </select>
      </div>

      <p style={{ fontSize: '0.875rem', color: '#888' }}>
        Selecione 11 jogadores (1 goleiro obrigatório). Selecionados: {selected.length}/11
      </p>

      {validationError && selected.length > 0 && (
        <p role="alert" style={{ color: 'red' }}>{validationError}</p>
      )}
      {saveSuccess && <p style={{ color: 'green' }}>Escalação salva com sucesso!</p>}
      {mutation.isError && <p style={{ color: 'red' }}>Erro ao salvar escalação.</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th></th>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th>
            <th>OVR</th>
            <th>Forma</th>
            <th>Fôlego</th>
            <th>Função</th>
          </tr>
        </thead>
        <tbody>
          {players.map(player => {
            const isSelected = selected.includes(player.id)
            return (
              <tr
                key={player.id}
                style={{ background: isSelected ? 'rgba(0,128,0,0.08)' : undefined }}
              >
                <td>
                  <input
                    type="checkbox"
                    aria-label={player.name}
                    checked={isSelected}
                    disabled={!player.eligible}
                    onChange={() => togglePlayer(player)}
                  />
                </td>
                <td>
                  {player.name}
                  {!player.eligible && (
                    <span style={{ color: 'red', marginLeft: '0.5rem' }}>
                      (Suspenso {player.suspensionRemaining})
                    </span>
                  )}
                </td>
                <td style={{ textAlign: 'center' }}>{positionLabel(player.position)}</td>
                <td style={{ textAlign: 'center' }}>{player.overall}</td>
                <td style={{ textAlign: 'center' }}>{formLabel(player.form)}</td>
                <td style={{ textAlign: 'center' }}>{player.stamina}</td>
                <td style={{ textAlign: 'center' }}>
                  {isSelected && (
                    <select
                      aria-label={`Função de ${player.name}`}
                      value={roles[player.id] ?? DEFAULT_ROLE[player.position]}
                      onChange={e => changeRole(player, e.target.value as PlayerRole)}
                    >
                      {ROLES_BY_POSITION[player.position].map(r => (
                        <option key={r} value={r}>{r}</option>
                      ))}
                    </select>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <button
        className="cec-btn"
        disabled={!canSave || mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
      </button>
    </div>
  )
}
```

- [ ] **Step 6: Run squad tests + typecheck/build**

Run: `cd frontend && npm run test -- SquadPage`
Expected: PASS.
Run: `cd frontend && npm run build`
Expected: PASS (tsc strict + Vite build clean).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/career.ts \
        frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx
git commit -m "feat(ui): squad roles, form/suspension/endurance indicators, new lineup payload"
```

---

### Task 10: Tactics panel + advance results (cards/suspensions)

**Files:**
- Create: `frontend/src/features/squad/TacticsPanel.tsx`
- Create: `frontend/src/features/squad/TacticsPanel.test.tsx`
- Modify: `frontend/src/features/squad/SquadPage.tsx` (embed the panel)
- Modify: `frontend/src/features/squad/SquadPage.test.tsx` (mock `getTactics`/`setTactics`)
- Modify: `frontend/src/features/match/AdvancePanel.tsx`
- Create: `frontend/src/features/match/AdvancePanel.test.tsx`

**Interfaces:**
- Consumes: `getTactics`/`setTactics` (Task 9), `Mentality`/`Pressing`/`Marking` unions, `advanceRound` (returns `bookings`/`newSuspensions`).

- [ ] **Step 1: Write the failing `TacticsPanel` test**

`frontend/src/features/squad/TacticsPanel.test.tsx`:
```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TacticsPanel } from './TacticsPanel'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getTactics: vi.fn(),
  setTactics: vi.fn(),
}))

import { getTactics, setTactics } from '../../api/career'

const mockGet = vi.mocked(getTactics)
const mockSet = vi.mocked(setTactics)

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TacticsPanel
            saveGameId="sg-1"
            clubId="club-1"
            starters={[{ id: 'p1', name: 'Craque Um' }]}
          />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('TacticsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    mockGet.mockResolvedValue({
      mentality: 'ATTACKING',
      pressing: 'HIGH',
      marking: 'ZONAL',
      penaltyTakerId: null,
      freekickTakerId: null,
      cornerTakerId: null,
    })
    mockSet.mockResolvedValue({
      mentality: 'ATTACKING',
      pressing: 'HIGH',
      marking: 'MAN_TIGHT',
      penaltyTakerId: 'p1',
      freekickTakerId: null,
      cornerTakerId: null,
    })
  })

  it('prefills from getTactics and saves via setTactics', async () => {
    renderPanel()
    await waitFor(() => {
      expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING')
    })
    fireEvent.change(screen.getByLabelText('Marcação'), { target: { value: 'MAN_TIGHT' } })
    fireEvent.change(screen.getByLabelText('Pênaltis'), { target: { value: 'p1' } })
    fireEvent.click(screen.getByRole('button', { name: /salvar tática/i }))

    await waitFor(() => expect(mockSet).toHaveBeenCalled())
    expect(mockSet).toHaveBeenCalledWith(
      'sg-1',
      'club-1',
      expect.objectContaining({ marking: 'MAN_TIGHT', penaltyTakerId: 'p1' }),
      'test-token',
    )
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm run test -- TacticsPanel`
Expected: FAIL — `TacticsPanel` does not exist.

- [ ] **Step 3: Create `TacticsPanel.tsx`**

```tsx
import { useEffect, useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getTactics, setTactics } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { Mentality, Pressing, Marking } from '../../api/types'

interface Starter {
  id: string
  name: string
}

interface TacticsPanelProps {
  saveGameId: string
  clubId: string
  starters: Starter[]
}

const MENTALITIES: Mentality[] = ['DEFENSIVE', 'BALANCED', 'ATTACKING']
const PRESSINGS: Pressing[] = ['LOW', 'MEDIUM', 'HIGH']
const MARKINGS: Marking[] = ['ZONAL', 'MAN_TIGHT']

export function TacticsPanel({ saveGameId, clubId, starters }: TacticsPanelProps) {
  const { token } = useAuth()
  const [mentality, setMentality] = useState<Mentality>('BALANCED')
  const [pressing, setPressing] = useState<Pressing>('MEDIUM')
  const [marking, setMarking] = useState<Marking>('ZONAL')
  const [penalty, setPenalty] = useState('')
  const [freekick, setFreekick] = useState('')
  const [corner, setCorner] = useState('')
  const [saved, setSaved] = useState(false)

  const { data } = useQuery({
    queryKey: ['tactics', saveGameId, clubId],
    queryFn: () => getTactics(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  useEffect(() => {
    if (data) {
      setMentality(data.mentality)
      setPressing(data.pressing)
      setMarking(data.marking)
      setPenalty(data.penaltyTakerId ?? '')
      setFreekick(data.freekickTakerId ?? '')
      setCorner(data.cornerTakerId ?? '')
    }
  }, [data])

  const mutation = useMutation({
    mutationFn: () =>
      setTactics(
        saveGameId,
        clubId,
        {
          mentality,
          pressing,
          marking,
          penaltyTakerId: penalty || null,
          freekickTakerId: freekick || null,
          cornerTakerId: corner || null,
        },
        token ?? '',
      ),
    onSuccess: () => setSaved(true),
  })

  function takerSelect(label: string, value: string, onChange: (v: string) => void) {
    return (
      <div>
        <label>
          {label}:{' '}
          <select
            aria-label={label}
            value={value}
            onChange={e => {
              setSaved(false)
              onChange(e.target.value)
            }}
          >
            <option value="">—</option>
            {starters.map(s => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </label>
      </div>
    )
  }

  return (
    <div style={{ marginTop: '1.5rem' }}>
      <h3>Tática</h3>
      <div>
        <label>
          Mentalidade:{' '}
          <select
            aria-label="Mentalidade"
            value={mentality}
            onChange={e => { setSaved(false); setMentality(e.target.value as Mentality) }}
          >
            {MENTALITIES.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>
      <div>
        <label>
          Pressão:{' '}
          <select
            aria-label="Pressão"
            value={pressing}
            onChange={e => { setSaved(false); setPressing(e.target.value as Pressing) }}
          >
            {PRESSINGS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
      </div>
      <div>
        <label>
          Marcação:{' '}
          <select
            aria-label="Marcação"
            value={marking}
            onChange={e => { setSaved(false); setMarking(e.target.value as Marking) }}
          >
            {MARKINGS.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>
      {takerSelect('Pênaltis', penalty, setPenalty)}
      {takerSelect('Faltas', freekick, setFreekick)}
      {takerSelect('Escanteios', corner, setCorner)}

      <button className="cec-btn" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
        {mutation.isPending ? 'Salvando…' : 'Salvar tática'}
      </button>
      {saved && <p style={{ color: 'green' }}>Tática salva!</p>}
      {mutation.isError && (
        <p style={{ color: 'red' }}>Erro ao salvar tática (defina e salve a escalação primeiro).</p>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Embed the panel in `SquadPage.tsx`**

Add the import:
```tsx
import { TacticsPanel } from './TacticsPanel'
```
Render it directly after the "Salvar escalação" `<button>` (still inside the root `<div>`):
```tsx
      <TacticsPanel
        saveGameId={saveGameId}
        clubId={clubId}
        starters={selected.map(id => {
          const p = players.find(pp => pp.id === id)!
          return { id, name: p.name }
        })}
      />
```

Update `SquadPage.test.tsx`'s `vi.mock('../../api/career', ...)` factory to also stub the tactics calls and resolve defaults in `beforeEach`:
```tsx
vi.mock('../../api/career', () => ({
  getSquad: vi.fn(),
  setLineup: vi.fn(),
  getTactics: vi.fn(),
  setTactics: vi.fn(),
}))
```
```tsx
import { getSquad, setLineup, getTactics } from '../../api/career'
// ...in beforeEach:
vi.mocked(getTactics).mockResolvedValue({
  mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL',
  penaltyTakerId: null, freekickTakerId: null, cornerTakerId: null,
})
```

- [ ] **Step 5: Add bookings/suspensions to `AdvancePanel.tsx`**

Inside the `{result && (...)}` block, after the results `<ul>`, add:
```tsx
          {result.bookings.length > 0 && (
            <div style={{ marginTop: '0.75rem' }}>
              <h4>Cartões</h4>
              <ul>
                {result.bookings.map((b, i) => (
                  <li key={`${b.playerId}-${i}`}>
                    {b.playerName} — {b.type === 'RED_CARD' ? 'Vermelho' : 'Amarelo'}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {result.newSuspensions.length > 0 && (
            <div style={{ marginTop: '0.75rem' }}>
              <h4>Suspensões</h4>
              <ul>
                {result.newSuspensions.map((s, i) => (
                  <li key={`${s.playerId}-${i}`}>
                    {s.playerName} — {s.matches} jogo(s)
                  </li>
                ))}
              </ul>
            </div>
          )}
```

- [ ] **Step 6: Add `AdvancePanel.test.tsx`**

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AdvancePanel } from './AdvancePanel'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({ advanceRound: vi.fn() }))
import { advanceRound } from '../../api/career'

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <AdvancePanel saveGameId="sg-1" competitionId="c-1" />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AdvancePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows bookings and suspensions after advancing', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 3,
      results: [],
      seasonFinished: false,
      bookings: [{ playerId: 'p1', playerName: 'Bruto Um', type: 'RED_CARD' }],
      newSuspensions: [{ playerId: 'p1', playerName: 'Bruto Um', matches: 1 }],
    })

    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))

    await waitFor(() => expect(screen.getByText(/Cartões/)).toBeInTheDocument())
    expect(screen.getByText(/Bruto Um — Vermelho/)).toBeInTheDocument()
    expect(screen.getByText(/Suspensões/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 7: Run frontend tests + build**

Run: `cd frontend && npm run test`
Expected: PASS (all suites incl. SquadPage, TacticsPanel, AdvancePanel).
Run: `cd frontend && npm run build`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/squad/TacticsPanel.tsx frontend/src/features/squad/TacticsPanel.test.tsx \
        frontend/src/features/squad/SquadPage.tsx frontend/src/features/squad/SquadPage.test.tsx \
        frontend/src/features/match/AdvancePanel.tsx frontend/src/features/match/AdvancePanel.test.tsx
git commit -m "feat(ui): tactics panel + advance results show bookings and suspensions"
```

---

## Final verification (after all tasks)

- [ ] **Backend:** `cd backend && ./gradlew test` — all suites green (Phase-1 classes + new: enums, entity, calculator, tactics service, engine, applier, suspension lifecycle, lineup roles, tactics API).
- [ ] **Frontend:** `cd frontend && npm run test && npm run build` — green.
- [ ] **Manual E2E:** `docker compose up -d` (Postgres) → run backend + frontend; create a Criciúma career; set an XI with roles + a full tactical profile (mentality/formation/pressing/marking/takers); advance several rounds; confirm: results vary with tactics, form moves on the squad screen, bookings/suspensions appear and a suspended player can't be selected (and is excluded from the next match), and the season still completes with a correct final table.

## Self-Review notes (author)

- **Spec coverage:** enums+behavior (T1) · V3 migration & state (T2) · TeamStrengthCalculator pipeline (T3) · managed/AI/default profiles (T4) · engine: fitness/tempo/set-piece/cards + determinism (T5) · cards→suspensions + form + eligibility (T6) · roles + suspended guard + squad-state responses (T7) · tactics endpoints (T8) · FE squad roles/indicators (T9) · FE tactics panel + advance bookings/suspensions (T10). All spec sections map to a task.
- **Determinism:** new randomness confined to `MatchSimulationService` `Random(seed)` and the existing `seed ^ 0x5DEECE66DL` attribution RNG; form/suspension logic is non-random.
- **Type consistency:** `TeamMatchInput` fields, `TacticalProfile` shape, `SetLineupRequest.starters`, `LineupResponse.starters`, `PlayerResponse` extra fields, and `SimulateRoundResponse.bookings/newSuspensions` are used identically across backend tasks and the frontend `types.ts`.
- **Refinement flagged:** spec's `staminaAvg` is realized as precomputed `fitnessDecay` on `TeamMatchInput` (Global Constraints).
- **Known degradations (intended, documented):** a managed XI containing a now-suspended starter plays short-handed until re-picked (T6 `startingXi` filters); red card = next-match ban only, no in-match 10-men (deferred per spec §10).
