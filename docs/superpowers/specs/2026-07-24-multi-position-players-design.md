# Multi-Position Players + EAFC Roles — Design

**Date:** 2026-07-24
**Status:** Approved (sections 1–4 reviewed with user)

## Goal

Players can play more than one position, with real-player data researched from real-world
sources. The manager can improvise any player in any slot (with a visible warning and an
engine penalty), while "Melhor time" (best XI) strictly respects natural positions. The
position/role model is enriched to the full EAFC 26 taxonomy from futbin.com/26/roles:
12 positions × ~30 role archetypes × focus variants.

Task keys from the user:

- research the real players' positions
- identify if they play in more than 1 position
- allow manager to select a player to a different position than he plays
- when selecting best players, use the position as base and do not set a player out of position
- enrich positions with all positions/roles defined in EAFC (futbin.com/26/roles)

## Approach (chosen: two-axis model)

Positions and roles are separate axes, mirroring futbin 1:1:

- **Positions:** `DetailedPosition` grows to the 12 EAFC positions. A player has a primary
  position plus 0–2 alternates.
- **Roles:** new `RoleArchetype` enum (~30 archetypes, each valid for specific positions)
  + new `RoleFocus` enum (10 focuses, each archetype declares its valid subset). A player
  has 1–2 *familiar* archetypes (`++` = fully familiar, `+` = somewhat).
- Supersedes and deletes both `PlayerRole` and `RoleBehaviour` (the concurrent-session
  behaviour-variant layer); `RoleBehaviour`'s weight-delta pattern is reused for focuses.

Rejected alternatives: (B) one flat enum of position+role combos (~90 values, loses the
structure); (C) extending `PlayerRole` in place (can't express position validity or focus
without fighting the existing shape).

## 1. Positions

### 1.1 DetailedPosition: 9 → 12 values

| Value | Short | Coarse | EAFC |
|---|---|---|---|
| GOLEIRO | GOL | GK | GK |
| ZAGUEIRO | ZAG | DEF | CB |
| LATERAL_DIREITO | LD | DEF | RB |
| LATERAL_ESQUERDO | LE | DEF | LB |
| VOLANTE | VOL | MID | CDM |
| MEIA_CENTRAL *(new)* | MC | MID | CM |
| MEIA_ARMADOR | MEI | MID | CAM |
| MEIA_DIREITA *(new)* | MD | MID | RM |
| MEIA_ESQUERDA *(new)* | ME | MID | LM |
| PONTA_DIREITA *(new)* | PD | ATT | RW |
| PONTA_ESQUERDA *(new)* | PE | ATT | LW |
| ATACANTE | ATA | ATT | ST |

`PONTA` and `FALSO_9` are removed as positions: in EAFC, False 9 is an ST *role*, not a
position. Ex-PONTA players become PD/PE; ex-FALSO_9 players become ATACANTE with the
FALSE_9 archetype fully familiar.

### 1.2 Persistence

- `players.detailed_position` stays the primary position.
- New `players.alt_positions VARCHAR(40) NULL` — CSV of 0–2 alternate `DetailedPosition`
  names. `Player.positions()` returns the ordered list (primary first). GK never has
  alternates, and no outfield player has GOLEIRO as an alternate.

### 1.3 Migration (V40)

- Remap `PONTA → PONTA_DIREITA/PONTA_ESQUERDA` by id-hash parity; `FALSO_9 → ATACANTE`
  (plus `FALSE_9++` familiar role, see §2.4). Same remap applied to any other table
  storing `DetailedPosition` values, and to `FormationSlots`-dependent data.
- `alt_positions` starts NULL for everyone; researched backfill in §3.1c.

### 1.4 Frontend / formations

- `positionLabels.ts`, `pitchSlots.ts`, backend `FormationSlots` all move to the 12-value
  set. 4-4-2-family wide mids become MD/ME natural (PD/PE adjacent); 4-3-3-family wingers
  become PD/PE. Sync-guard tests on both sides updated together.

## 2. Roles

### 2.1 RoleArchetype (~30 values)

Each archetype declares: valid positions, valid focuses (first = default), PT-BR label,
and an engine effect profile (attack/defense attribute weight maps, replacing
`PlayerRole`'s). Full matrix (mirrors futbin EAFC 26 exactly — kept in sync by a guard
test, §6.1):

| Position | Archetype | PT-BR label | Focuses (default first) |
|---|---|---|---|
| GOL | GOALKEEPER | Goleiro | DEFEND, BALANCED |
| GOL | SWEEPER_KEEPER | Goleiro-líbero | BALANCED, BUILD_UP |
| GOL | BALL_PLAYING_KEEPER | Goleiro construtor | BUILD_UP |
| LD/LE | FULLBACK | Lateral | BALANCED, DEFEND, VERSATILE |
| LD/LE | WINGBACK | Ala | BALANCED, SUPPORT |
| LD/LE | FALSEBACK | Falso lateral | BALANCED, DEFEND |
| LD/LE | ATTACKING_WINGBACK | Ala ofensivo | SUPPORT, ATTACK |
| LD/LE | INVERTED_WINGBACK | Ala invertido | BUILD_UP, ATTACK |
| ZAG | DEFENDER | Zagueiro | DEFEND, BALANCED |
| ZAG | STOPPER | Xerife | BALANCED, AGGRESSIVE |
| ZAG | BALL_PLAYING_DEFENDER | Zagueiro construtor | DEFEND, BUILD_UP, AGGRESSIVE |
| ZAG | WIDE_BACK | Zagueiro ala | DEFEND, SUPPORT, AGGRESSIVE |
| VOL | HOLDING | Primeiro volante | DEFEND, ROAMING, BALL_WINNING |
| VOL | DEEP_LYING_PLAYMAKER | Volante armador | DEFEND, ROAMING, BUILD_UP |
| VOL | CENTRE_HALF | Terceiro zagueiro | DEFEND |
| VOL | WIDE_HALF | Volante ala | DEFEND, BUILD_UP |
| VOL | BOX_CRASHER | Volante de chegada | BALANCED |
| MC | BOX_TO_BOX | Box-to-box | BALANCED, BALL_WINNING |
| MC | HOLDING | Primeiro volante | DEFEND, BALL_WINNING |
| MC | DEEP_LYING_PLAYMAKER | Volante armador | DEFEND, BUILD_UP |
| MC | PLAYMAKER | Armador | ATTACK, ROAMING |
| MC | HALF_WINGER | Meia-ala | BALANCED, ATTACK, SUPPORT |
| MEI | PLAYMAKER | Armador | BALANCED, ROAMING, BUILD_UP |
| MEI | HALF_WINGER | Meia-ala | BALANCED, ATTACK, ROAMING |
| MEI | SHADOW_STRIKER | Segundo atacante | ATTACK |
| MEI | CLASSIC_10 | Camisa 10 | ATTACK, WIDE, VERSATILE |
| MD/ME | WINGER | Ponta | BALANCED, ATTACK |
| MD/ME | WIDE_PLAYMAKER | Armador aberto | ATTACK, BUILD_UP |
| MD/ME | WIDE_MIDFIELDER | Meia de lado | SUPPORT, DEFEND, BUILD_UP |
| MD/ME | INSIDE_FORWARD | Atacante por dentro | BALANCED, ATTACK |
| PD/PE | WINGER | Ponta | BALANCED, ATTACK, VERSATILE |
| PD/PE | WIDE_PLAYMAKER | Armador aberto | ATTACK, BUILD_UP |
| PD/PE | INSIDE_FORWARD | Atacante por dentro | BALANCED, ATTACK, ROAMING |
| ATA | ADVANCED_FORWARD | Atacante móvel | ATTACK, VERSATILE, SUPPORT |
| ATA | TARGET_FORWARD | Pivô | ATTACK, BALANCED, WIDE |
| ATA | FALSE_9 | Falso 9 | BUILD_UP, ATTACK |
| ATA | POACHER | Oportunista | ATTACK, SUPPORT, VERSATILE |

Note: an archetype appearing under multiple positions (e.g. WINGER, HOLDING) is a single
enum value whose valid-positions set covers them; where futbin gives different focus
lists per position (WINGER at RM/LM vs RW/LW), the archetype stores focuses per position.

### 2.2 RoleFocus (10 values)

DEFEND (Defesa), BALANCED (Equilibrado), ATTACK (Ataque), BUILD_UP (Construção),
SUPPORT (Apoio), ROAMING (Livre), VERSATILE (Versátil), AGGRESSIVE (Agressivo),
BALL_WINNING (Combativo), WIDE (Aberto).

### 2.3 Familiarity

- New `players.familiar_roles VARCHAR(120) NULL` — CSV like `WINGBACK++,FULLBACK+`.
  `++` = fully familiar, `+` = somewhat familiar. Archetype-level (focus is a free
  tactical choice, never penalized).

### 2.4 Migration (V40, continued)

- Legacy `PlayerRole` → familiar archetype `++`: FULL_BACK→FULLBACK,
  TARGET_MAN→TARGET_FORWARD, HOLDING_MID→HOLDING, BALL_PLAYING_DEF→BALL_PLAYING_DEFENDER,
  GOALKEEPER→GOALKEEPER, STOPPER→STOPPER, BOX_TO_BOX→BOX_TO_BOX, PLAYMAKER→PLAYMAKER,
  POACHER→POACHER, WINGER→WINGER. Ex-FALSO_9 players get FALSE_9++ instead.
- `lineup_players.role` → `archetype VARCHAR(24)` + `focus VARCHAR(16)` (focus = the
  archetype's default), plus new `slot SMALLINT NULL` (explicit slot index into
  `FormationSlots`; NULL = auto-assigned).
- `PlayerRole` and `RoleBehaviour` are deleted once all callers are migrated.

### 2.5 Validation (LineupService)

Existing chain gains: (archetype, focus) must be a valid pair; archetype must be valid
for the assigned slot's position; explicit `slot` indices must be unique and in range.
PT-BR 422 messages, same style as current role/instruction validation.

## 3. Real-player research & data

### 3.1 Research pipeline (804 players: 625 club + 179 free agents)

a. **Method:** parallel research subagents, one batch per club (28) + free-agent chunks.
   Each researches its players' real position(s) and realistic role archetype(s) via web
   sources (Transfermarkt, Sofascore, futbin, ogol) + football knowledge. Output per
   player: `positions` (primary + up to 2 alternates) and `roles` (1–2 archetypes with
   `++`/`+`).
b. **Storage:** new fields directly in `seed/clubs.json` and `seed/free-agents.json`:
   `"positions": ["PONTA_ESQUERDA", "MEIA_ESQUERDA"]`,
   `"roles": ["INSIDE_FORWARD++", "WINGER+"]`. `SaveGameService` reads them for new
   careers. A validation test asserts every entry parses, positions are
   coarse-consistent with the existing `position` field, roles are position-valid, and
   **all 804 players have researched data** (no silent gaps).
c. **Existing saves:** V40 can't research from SQL, so the same research data is compiled
   into a name+club-matched backfill (name-only for free agents where unambiguous).
   Unmatched players (newgens, renamed, ambiguous) keep their derived single position and
   the legacy-mapped familiar role from §2.4.

### 3.2 Compatibility stance

Like the V36 clubs backfill precedent, this feature is intentionally **not byte-identical
for existing saves** — researched positions/roles legitimately change team strengths. The
mechanics remain deterministic: same inputs → same outputs, no RNG draws added, removed,
or reordered anywhere.

## 4. Engine effects

### 4.1 Position fit (replaces band-based 1.0/0.92/0.80)

Per-player multiplier on both attack and defense contributions:

- **Natural** (slot position ∈ player's positions list): **1.00**
- **Adjacent** improvisation: **0.92**
- **Far**: **0.80**
- **GK ↔ any outfield** (either direction): **0.50**

"Adjacent" comes from a small distance matrix on `DetailedPosition` (1 step):
LD↔MD, LE↔ME, MD↔PD, ME↔PE, VOL↔MC, MC↔MEI, MEI↔ATA, ZAG↔VOL, LD↔ZAG, LE↔ZAG, PD↔ATA,
PE↔ATA. Everything else outfield is far. Multi-position aware: fit = best fit across
the player's positions list.

### 4.2 Role familiarity

Multiplier on both contributions, stacking multiplicatively with position fit:
`++` = 1.00, `+` = 0.97, unfamiliar archetype = 0.92. Focus choice is never penalized.

### 4.3 Archetype profiles & focus deltas

- Each archetype carries attack/defense attribute weight maps. The 10 legacy-mapped
  archetypes start with the *exact* old `PlayerRole` weights (migrated lineups score
  identically at unit level); the ~20 new archetypes get new profiles in the same value
  range.
- **Focus applies a weight delta** on top (RoleBehaviour's proven delta pattern). The
  default focus of every archetype has a **zero delta** — choosing defaults reproduces
  the archetype's base profile exactly.
- Event-attribution weight tweaks stay on the archetype, same mechanism as today
  (WINGER assist ×1.5; POACHER/ADVANCED_FORWARD scorer lean; TARGET_FORWARD header lean;
  FALSE_9 assist lean). No new RNG draws.

### 4.4 Best XI & auto-assignment (natural-first strict)

- `bestXi` and `SlotAssigner`: fill every slot only from players whose positions list
  contains the slot's position (rating desc, then familiarity, then id — deterministic).
  Only when a slot has **no** natural candidate left does it fall back, choosing the
  candidate with the smallest position distance (ZAG before winger at LE). A natural
  player is never benched to improvise a stronger one.
- Best XI also auto-picks each starter's best familiar archetype valid for the slot
  (default focus), producing a fully coherent lineup.
- AI clubs use the same routine → AI lineups are near-all-natural (fit ≈ 1.0).

## 5. Lineup UX & generated players

### 5.1 Slot picker on the pitch

- Squad-page pitch: **tap a slot → picker dialog** listing natural players for the
  slot's position first (rating desc), then everyone else flagged **"improvisado"** with
  their natural positions shown. Any player can go anywhere.
- Explicit choices persist via `lineup_players.slot`; untouched slots stay NULL =
  auto-assigned. **"Melhor time"** clears explicit slots back to full auto.
- Player token shows the slot's short label; improvised players get the **amber warn
  tint** + tooltip "Improvisado — posição natural: {labels}".

### 5.2 Role + focus selects

- Two selects on slot assignment / lineup PlayerDetails: **"Função"** (archetypes valid
  for the slot's position; familiar ones marked ★★/★ and listed first) and **"Foco"**
  (focuses valid for the chosen archetype, default preselected). Changing archetype
  resets focus to its default.
- PlayerDetails everywhere gains read-only **"Posições"** (e.g. "PE · ME") and
  **"Funções conhecidas"** ("Atacante por dentro ★★, Ponta ★") lines.

### 5.3 Newgens & youth prospects

Generation (newgen, youth intake, retirement replacements) keeps using per-player
id-seeded `Random`s — no shared-stream disturbance:

- `DetailedPosition.defaultFor` reworked to the 12-value set with realistic distribution.
- ~30% chance of 1 alternate position, ~5% of 2 (adjacent positions only; never GK).
- Always 1 familiar `++` archetype (weighted by position/attributes — e.g. high-PASSE MC
  leans DEEP_LYING_PLAYMAKER); ~25% chance of a second `+` archetype.

## 6. Testing

1. **Futbin sync-guard:** asserts the complete RoleArchetype/RoleFocus matrix (valid
   positions, valid focuses per position, defaults) against a checked-in copy of the
   futbin EAFC 26 taxonomy — any drift fails loudly.
2. **Seed validation:** all 804 players have parseable, coarse-consistent,
   position-valid `positions`/`roles`.
3. **Best XI strictness:** natural always beats a higher-rated improviser; fallback
   picks smallest distance; deterministic tiebreak (rating → familiarity → id).
4. **Engine regressions:** legacy-mapped archetype + default focus + natural position ⇒
   contributions identical to old `PlayerRole` math (unit); fixed-seed match with
   all-natural lineups unchanged in mechanics (RNG draw-count audit).
5. **Migration IT (V40):** PONTA→PD/PE parity distribution, FALSO_9→ATA + FALSE_9++,
   legacy role→familiar seeding, lineup role→archetype/focus conversion, name+club
   backfill matching.
6. **Frontend (RTL):** picker ordering + improvisado flag + warn tint, cascading
   role/focus selects, "Melhor time" reset; pitchSlots ↔ FormationSlots 12-position
   sync-guards on both sides.

## 7. Constraints & workflow

- Migrations: next free number is **V40** (through V39 exist); take the next free number
  if a concurrent session claims it first.
- Determinism doctrine: no RNG draw insert/remove/reorder in existing streams; any new
  randomness uses its own id-seeded `Random`.
- Concurrent sessions on `main`: commit only named files, never `git add -A`; per-task
  commit protocol from the batch plan applies.
- All UI text PT-BR; existing MUI sx + TanStack Query patterns.
- Backend suite: `cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew test`;
  frontend type-check via `npm run build` (`tsc --noEmit` is a no-op).

## Out of scope

- Position retraining / familiarity growth over time.
- Focus-level familiarity.
- Per-focus hand-tuned profiles beyond the delta system.
- Researching newgen appearance/attributes (only positions/roles).
