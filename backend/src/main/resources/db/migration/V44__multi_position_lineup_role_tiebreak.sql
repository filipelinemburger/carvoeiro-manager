-- Corrects the one non-deterministic statement in V41__multi_position.sql (step 2), which refined
-- a player's derived familiar archetype from their persisted lineup role:
--
--     UPDATE players p SET familiar_roles = CASE lp.role ... END
--     FROM lineup_players lp
--     WHERE lp.player_id = p.id AND (...)
--
-- Postgres evaluates an UPDATE ... FROM against ONE arbitrary matching row when the join produces
-- several, and a player really can have several. `lineups` is UNIQUE (save_game_id, club_id), so
-- there is one lineup PER CLUB, and LineupService.setLineup only checks that the club belongs to
-- the caller's save — not that it is the club they manage. Transfers clean up just the managed
-- club's lineup (TransferExecutor.cleanManagedLineup), so a player who was in club A's lineup and
-- moved to club B keeps his stale row in A alongside the new one in B. Where those two rows named
-- different roles, V41 picked between them arbitrarily.
--
-- V41 is left byte-identical rather than corrected in place: it is already applied (and pushed),
-- and rewriting it would change its Flyway checksum and refuse to start every database that has
-- it. Correcting forward is also sufficient — step 2 only ever touches rows that existed BEFORE
-- the migration ran, so on a fresh database it is a no-op (no careers, no lineups) and the two
-- migrations together are deterministic for everyone.
--
-- Note the column: V42__role_archetypes.sql migrated `lineup_players.role` into `archetype`
-- (+ `focus`) and DROPPED `role`, so the three archetypes V41's step 2 cared about are now spelled
-- STOPPER / BALL_PLAYING_DEFENDER / TARGET_FORWARD — the same names their '++' tokens use.
--
-- THE RULE: the lineup of the club the player CURRENTLY belongs to wins. That is the manager's
-- live intent, and it is unique by construction (one lineup per club, one club per player), so
-- there is nothing left to break ties on. Stale rows from former clubs are ignored.
--
-- SCOPE: only players whose lineup rows actually disagreed, and only where the value now stored is
-- itself one of those rows' derivations. Anything else — a value V41 derived deterministically, or
-- one V43__research_backfill.sql later wrote from the researched seed data — is left alone. The
-- one case this cannot separate is a researched player who is ALSO multi-lineup and whose
-- researched token coincides with a stale row's derivation; provenance isn't recorded, and both
-- values are defensible, so that row is corrected toward the current club like the rest.

WITH derived AS (
    SELECT p.id AS player_id,
           l.club_id,
           lp.archetype || '++' AS token
    FROM players p
    JOIN lineup_players lp ON lp.player_id = p.id
    JOIN lineups l ON l.id = lp.lineup_id AND l.save_game_id = p.save_game_id
    WHERE (lp.archetype IN ('STOPPER', 'BALL_PLAYING_DEFENDER') AND p.detailed_position = 'ZAGUEIRO')
       OR (lp.archetype = 'TARGET_FORWARD' AND p.detailed_position = 'ATACANTE')
),
-- Players V41 had to guess about: two or more lineup rows offering different archetypes.
ambiguous AS (
    SELECT player_id
    FROM derived
    GROUP BY player_id
    HAVING count(DISTINCT token) > 1
)
UPDATE players p
SET familiar_roles = d.token
FROM derived d
WHERE d.player_id = p.id
  AND d.club_id = p.club_id
  AND p.id IN (SELECT player_id FROM ambiguous)
  AND p.familiar_roles <> d.token
  AND p.familiar_roles IN (SELECT token FROM derived stale WHERE stale.player_id = p.id);
