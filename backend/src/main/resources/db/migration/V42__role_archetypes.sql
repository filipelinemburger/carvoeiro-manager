-- Role archetype + focus replace the legacy PlayerRole on lineup rows (spec §2.4). Focus is seeded
-- with each archetype's canonical default so migrated lineups score byte-identically at unit level.
-- (slot_index already exists from V40 — untouched here.)
ALTER TABLE lineup_players ADD COLUMN archetype VARCHAR(24);
ALTER TABLE lineup_players ADD COLUMN focus VARCHAR(16);

UPDATE lineup_players SET
    archetype = CASE role
        WHEN 'FULL_BACK' THEN 'FULLBACK'
        WHEN 'TARGET_MAN' THEN 'TARGET_FORWARD'
        WHEN 'HOLDING_MID' THEN 'HOLDING'
        WHEN 'BALL_PLAYING_DEF' THEN 'BALL_PLAYING_DEFENDER'
        ELSE role END,
    focus = CASE role
        WHEN 'GOALKEEPER' THEN 'DEFEND'
        WHEN 'BALL_PLAYING_DEF' THEN 'DEFEND'
        WHEN 'HOLDING_MID' THEN 'DEFEND'
        WHEN 'POACHER' THEN 'ATTACK'
        WHEN 'TARGET_MAN' THEN 'ATTACK'
        ELSE 'BALANCED' END;

ALTER TABLE lineup_players ALTER COLUMN archetype SET NOT NULL;
ALTER TABLE lineup_players ALTER COLUMN focus SET NOT NULL;
ALTER TABLE lineup_players DROP COLUMN role;
