# AI Club Tactical Identity — Design

**Date:** 2026-07-07
**Status:** Approved

## Goal

Give every AI-controlled club match a real tactical identity — formation, mentality,
pressing, marking — sourced per real club, instead of every AI club using the same
hardcoded default (`BALANCED`, `4-4-2`, `MEDIUM`, `ZONAL`).

## Background (from the codebase)

- `TeamStrengthCalculator` already fully consumes `TacticalProfile` (mentality,
  pressing, marking, formation all move attack/defense multipliers, tempo,
  aggression, fitness decay) — the match engine side of "tactics affect
  simulation" is done.
- `TacticsService.profileFor(saveGameId, clubId, xi)` returns the managed club's
  persisted `Lineup` tactics when one exists, otherwise `defaultProfile(xi)` — a
  single hardcoded profile used for **every** AI club, in every fixture, all
  season. AI clubs never persist a `Lineup` row, so they always hit this default.
  `TacticsService.java:33-50`
- `FixtureSimulator.simulateFixture()` and `LiveMatchService` (live-match AI side)
  both call `profileFor()` per club per match — the only two call sites.
- `Club` already carries seed-authored identity data unrelated to gameplay
  (`primaryColor`, `secondaryColor`, `nickname`, `commonName`), set at career
  creation in `SaveGameService.createCareer()` from the matching `SeedClub` in
  `clubs.json`. This is the established pattern for "real club flavor, authored in
  the seed, persisted per save."
- Set-piece takers (`penaltyTakerId`, `freekickTakerId`, `cornerTakerId`) are
  computed by `defaultProfile()` from the current XI's best finisher/passer, not
  authored — that logic is roster-dependent and stays unchanged.
- `Formation` is a 16-value enum (`player/Formation.java`) with a `code()` like
  `"4-3-3"`; `Lineup.formation` stores the code as a plain `String`, not the enum,
  resolved via `Formation.fromCode()` at read time in the strength calculator.

## Decisions (user-approved)

1. **Source of identity: researched per real club**, same approach as the Série B
   roster work — one real formation/mentality/pressing/marking per club, authored
   in the seed. Smaller Catarinense-only clubs get a reasonable inferred style
   where a specific 2026 system isn't confidently known (same freshness caveat
   used for the Série B rosters).
2. **All 28 pool clubs** get an authored identity, including the managed club
   (CRI) and Avaí (in both competitions) — this is also the fallback profile
   shown before the human manager sets their own tactics.
3. **Set-piece takers stay computed at match time** from the XI, not authored —
   unchanged behavior.
4. **No dynamic in-match AI adjustment** (e.g. going defensive to protect a lead)
   — that is a separate "AI manager" feature, out of scope here.

## Data model

### Seed (`clubs.json` / `SeedClub`)

`SeedClub` gains 4 fields, alongside the existing color fields:

```java
public record SeedClub(
        String name,
        String shortName,
        String commonName,
        String nickname,
        String primaryColor,
        String secondaryColor,
        String formation,      // NEW — Formation code, e.g. "4-3-3"
        String mentality,      // NEW — Mentality enum name, e.g. "ATTACKING"
        String pressing,       // NEW — Pressing enum name, e.g. "HIGH"
        String marking,        // NEW — Marking enum name, e.g. "MAN_TIGHT"
        List<SeedPlayer> players
) {}
```

Every one of the 28 `clubPool` entries in `clubs.json` gets these 4 fields.

### Migration

New `backend/src/main/resources/db/migration/V21__club_tactical_identity.sql`,
adding 4 columns to `clubs`, matching the types/lengths of the equivalent
`lineups` columns:

```sql
ALTER TABLE clubs
    ADD COLUMN default_formation VARCHAR(10),
    ADD COLUMN default_mentality VARCHAR(12),
    ADD COLUMN default_pressing VARCHAR(8),
    ADD COLUMN default_marking VARCHAR(10);
```

Nullable at the DB level (existing rows in already-created saves have no seed
identity to backfill); `TacticsService` treats null as "fall back to the
hardcoded default," so old saves keep working unchanged.

### `Club` entity

4 new fields + getters/setters, following the exact pattern of
`primaryColor`/`secondaryColor`:

```java
@Column(name = "default_formation", length = 10)
private String defaultFormation;

@Column(name = "default_mentality", length = 12)
private String defaultMentality;

@Column(name = "default_pressing", length = 8)
private String defaultPressing;

@Column(name = "default_marking", length = 10)
private String defaultMarking;

public String getDefaultFormation() { return defaultFormation; }
public String getDefaultMentality() { return defaultMentality; }
public String getDefaultPressing() { return defaultPressing; }
public String getDefaultMarking() { return defaultMarking; }

public void setDefaultFormation(String v) { this.defaultFormation = v; }
public void setDefaultMentality(String v) { this.defaultMentality = v; }
public void setDefaultPressing(String v) { this.defaultPressing = v; }
public void setDefaultMarking(String v) { this.defaultMarking = v; }
```

Stored as plain strings (not `@Enumerated`), matching how `Lineup.formation` is
already a plain string — `Mentality`/`Pressing`/`Marking` get parsed via
`valueOf()` only where the profile is built, same as `Lineup`'s enum-typed
columns are read by JPA today but written from string in the seed loader path.

### `SaveGameService.createCareer()`

Right next to the existing color assignment (`SaveGameService.java:107-108`):

```java
club.setDefaultFormation(seedClub.formation());
club.setDefaultMentality(seedClub.mentality());
club.setDefaultPressing(seedClub.pressing());
club.setDefaultMarking(seedClub.marking());
```

## Behavior change

`TacticsService` gets `ClubRepository` injected (constructor injection, matching
existing style). `defaultProfile(xi)` becomes club-aware:

```java
public TacticalProfile profileFor(UUID saveGameId, UUID clubId, List<Player> xi) {
    return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
            .map(lineup -> fromLineup(lineup, xi))
            .orElseGet(() -> defaultProfile(clubId, xi));
}

private TacticalProfile defaultProfile(UUID clubId, List<Player> xi) {
    Club club = clubRepository.findById(clubId).orElse(null);
    Mentality mentality = parseOrDefault(club != null ? club.getDefaultMentality() : null,
            Mentality::valueOf, Mentality.BALANCED);
    String formation = club != null && club.getDefaultFormation() != null
            ? club.getDefaultFormation() : "4-4-2";
    Pressing pressing = parseOrDefault(club != null ? club.getDefaultPressing() : null,
            Pressing::valueOf, Pressing.MEDIUM);
    Marking marking = parseOrDefault(club != null ? club.getDefaultMarking() : null,
            Marking::valueOf, Marking.ZONAL);
    // ... roles / bestFinisher / bestPasser unchanged ...
    return new TacticalProfile(mentality, formation, pressing, marking,
            roles, bestFinisher, bestPasser, bestFinisher);
}
```

`parseOrDefault` is a small private helper swallowing `null`/`IllegalArgumentException`
into the fallback constant — defensive only; every seeded club has all 4 fields
set, so the fallback path is not expected to trigger in practice.

`profileFor()`'s public signature is unchanged; both existing call sites
(`FixtureSimulator.java:148-149`, `LiveMatchService.java:352-354`) need no
changes.

## Out of scope

- Dynamic in-match AI tactical adjustment (protecting a lead, chasing a game) —
  a separate "AI manager" feature.
- Set-piece event modeling (corner taker still unused; unified 18% set-piece
  probability) — a separate sub-project, planned next.
- Any change to the managed club's own tactics flow (`Lineup`, `TacticsController`,
  squad-page UI) — untouched.

## Testing

- `SeedLoaderTest`: every one of the 28 pool clubs has a formation whose code
  matches a real `Formation` enum value, and valid `Mentality`/`Pressing`/`Marking`
  enum names.
- `SaveGameServiceIT`: career creation persists the 4 fields onto `Club` rows;
  spot-check at least one Série B club and one Catarinense-only club against
  their seed values.
- `TacticsServiceIT`: two different non-managed clubs with different seed
  identities produce two different `TacticalProfile`s (formation/mentality/
  pressing/marking match each club's own seed values, not the old shared
  default); a club with all-null identity fields (constructed directly, not
  through the seed) still falls back to `BALANCED/4-4-2/MEDIUM/ZONAL`.
- Full `./mvnw test` green before completion.
