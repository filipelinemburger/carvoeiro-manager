-- Multi-position players (spec §1): 12-value DetailedPosition, alternate positions, and
-- familiar-roles seeding. PONTA splits into PONTA_DIREITA/PONTA_ESQUERDA by id-hash parity;
-- FALSO_9 becomes ATACANTE and keeps its identity as the FALSE_9 familiar archetype.
-- familiar_roles is seeded here (before the remap consumes the legacy values) even though the
-- Java side only starts reading it in a later task — the column must exist with sane data the
-- moment detailed_position loses PONTA/FALSO_9.
--
-- familiar_roles is NOT NULL DEFAULT '' (unlike V39's detailed_position, which added nullable
-- then did a separate SET NOT NULL once backfilled): Task 1 does not map this column on the
-- Player entity yet, so every Hibernate-issued player INSERT omits it and must fall back to the
-- column default rather than violate a bare NOT NULL constraint. A later task maps
-- familiarRoles() on Player and starts writing real values.

ALTER TABLE players ADD COLUMN alt_positions VARCHAR(40);
ALTER TABLE players ADD COLUMN familiar_roles VARCHAR(120) NOT NULL DEFAULT '';

-- 1. Derive one fully-familiar (++) archetype from the legacy detailed position.
UPDATE players SET familiar_roles = CASE detailed_position
    WHEN 'GOLEIRO' THEN 'GOALKEEPER++'
    WHEN 'ZAGUEIRO' THEN 'DEFENDER++'
    WHEN 'LATERAL_ESQUERDO' THEN 'FULLBACK++'
    WHEN 'LATERAL_DIREITO' THEN 'FULLBACK++'
    WHEN 'VOLANTE' THEN 'HOLDING++'
    WHEN 'MEIA_ARMADOR' THEN 'PLAYMAKER++'
    WHEN 'PONTA' THEN 'WINGER++'
    WHEN 'FALSO_9' THEN 'FALSE_9++'
    WHEN 'ATACANTE' THEN 'POACHER++'
END;

-- 2. A manager's persisted lineup role refines the derived default where it names a more
--    specific archetype that stays valid for the player's position (the only such cases).
UPDATE players p SET familiar_roles = CASE lp.role
        WHEN 'STOPPER' THEN 'STOPPER++'
        WHEN 'BALL_PLAYING_DEF' THEN 'BALL_PLAYING_DEFENDER++'
        WHEN 'TARGET_MAN' THEN 'TARGET_FORWARD++'
    END
FROM lineup_players lp
WHERE lp.player_id = p.id
  AND ((lp.role IN ('STOPPER', 'BALL_PLAYING_DEF') AND p.detailed_position = 'ZAGUEIRO')
       OR (lp.role = 'TARGET_MAN' AND p.detailed_position = 'ATACANTE'));

-- 3. Remap the two retired position values (deterministic, hash-parity like V39).
UPDATE players SET detailed_position = CASE
    WHEN detailed_position = 'FALSO_9' THEN 'ATACANTE'
    WHEN abs(hashtext(id::text)) % 2 = 0 THEN 'PONTA_DIREITA'
    ELSE 'PONTA_ESQUERDA'
END
WHERE detailed_position IN ('PONTA', 'FALSO_9');
