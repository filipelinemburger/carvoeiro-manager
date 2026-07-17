# Real Free Agents — Design

## Context

This is cycle 1 of a 3-cycle bundle (real free agents → pre-contract offers → preferred-players
watchlist). Each cycle ships independently: spec → plan → implementation → review → push.

Today's free-agent pool is fully procedural: `FreeAgentGenerator` rolls 10 fictional players at
career creation (random name from `newgen-names.json`, random position, attributes rolled from
flat bands) and tops the pool back up to a floor of 6 at every season rollover. The goal of this
cycle is to replace that with a real, hand-authored pool of South American free agents — no
procedural generation left in the free-agent system at all.

## Scope

Replace the free-agent *sourcing* only. Nothing about the signing flow changes: the "Agentes
livres" tab, `ContractService.signFreeAgent`, wage negotiation (`ContractValuation`), the
transfer-window gate — all untouched. This cycle only changes *where the players in the pool
come from*.

Nationality display in the UI is explicitly out of scope for this cycle. `Player.nationality`
already exists as a required field on the entity and is already populated by every seed player
(club rosters, newgens); free agents will populate it the same way. It simply isn't surfaced in
any player-facing response or UI yet, and this cycle doesn't change that.

## Player pool composition

A new seed file, hand-researched and authored the same way the 28 club rosters were:

- ~40 real, currently-unattached Brazilian players plausible at Série B/Catarinense level
- ~20 real, currently-unattached players each from Argentina, Uruguay, Colombia, Paraguay,
  Chile, Venezuela, and Ecuador
- ~180 total. These are targets, not hard requirements — the research process authors as many
  verified-real free agents per country as it can find; exact counts may vary slightly per
  country.

Quality/position/age spread should be calibrated the way the club rosters were (a believable mix
of levels — past-peak veterans, squad players let go, unproven youngsters — not a flat curve),
not randomly rolled.

## Data model

New file: `backend/src/main/resources/seed/free-agents.json`, a flat JSON array using the
existing `SeedPlayer` shape (name, position, age, nationality, the six anchor stats, full
`attributes` block) — the same shape already used inside each club's `players` array in
`clubs.json`. No new Java record is needed.

## Backend changes

- **`seed/SeedLoader`**: add `loadFreeAgents()`, reading `seed/free-agents.json` into
  `List<SeedPlayer>`, following the same pattern as `loadCareer()`/`loadNewgenNames()`.
- **`career/SaveGameService.createCareer`**: replace the current
  `FreeAgentGenerator.generate(...)` loop (10 iterations) with a loop over the loaded seed list,
  calling the same `Player.create(saveGameId, null, ...)` constructor already used elsewhere
  (clubId null, wage `ZERO`, contractYears `0`).
- **`season/SeasonRolloverService`**: remove the floor-backfill call that currently tops the
  pool back up to 6 free agents using `FreeAgentGenerator` at rollover. No replacement — see
  "Pool depletion" below.
- **`contract/FreeAgentGenerator`** and its test become dead code once both call sites above are
  removed. Delete the class and its test file rather than leaving unused code in the tree.
- Every existing player-attribute researched player must have a name that doesn't collide with
  any name already used in `clubs.json`'s club rosters (dedup check during authoring).

## Pool depletion (explicit trade-off)

Free agents are never touched by the AI transfer engine — only the user signs them via
`ContractService.signFreeAgent`. With generation removed, the pool now only shrinks (as the user
signs players out of it) and only grows the way it already does today: when one of the user's
own squad players hits contract expiry and drops to free agency at rollover
(`SeasonRolloverService`'s existing expired-contract handling, unchanged). There is intentionally
no generated backfill. In a very long save, the real pool could eventually run low. That's an
accepted trade-off in exchange for the pool being fully real — no fallback mechanism is being
built for this.

## Testing

- Update existing tests that assert the old counts (10 at creation, floor of 6 at rollover) to
  match the new seeded totals.
- Add a seed-validation test mirroring the Catarinense roster pattern: every entry has required
  fields populated, and no free-agent name collides with any club-roster player name.
- Remove `FreeAgentGenerator`'s own test file along with the class.

## Out of scope for this cycle

- Nationality display anywhere in the API or UI.
- Pre-contract (Bosman-style) offers — cycle 2.
- Preferred-players watchlist — cycle 3.
- Any change to the signing flow, wage negotiation, or transfer-window gating.
