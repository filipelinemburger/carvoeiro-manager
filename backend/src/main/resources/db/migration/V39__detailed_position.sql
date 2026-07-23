-- DetailedPosition (posição detalhada): every player carries a fine-grained position that refines
-- the coarse `position`. New saves set it at creation/generation; this migration backfills legacy
-- rows deterministically from position + a stable hash-parity of the id, so an upgraded save is
-- reproducible and every backfilled value is coarse-consistent with the existing `position`.
-- FALSO_9 is never backfilled (it is an opt-in generation-time flavour only).

-- 1. Add nullable first so the backfill can populate it.
ALTER TABLE players ADD COLUMN detailed_position VARCHAR(20);

-- 2. Deterministic backfill by coarse position + abs(hashtext(id)) parity. Using abs() keeps the
--    modulo operand positive (hashtext can be negative). The DEF split guarantees both laterais and
--    the zagueiro majority appear across a real roster; MID and ATT split similarly.
UPDATE players SET detailed_position = CASE position
    WHEN 'GK' THEN 'GOLEIRO'
    WHEN 'DEF' THEN CASE abs(hashtext(id::text)) % 5
        WHEN 3 THEN 'LATERAL_ESQUERDO'
        WHEN 4 THEN 'LATERAL_DIREITO'
        ELSE 'ZAGUEIRO'
    END
    WHEN 'MID' THEN CASE abs(hashtext(id::text)) % 2
        WHEN 1 THEN 'MEIA_ARMADOR'
        ELSE 'VOLANTE'
    END
    WHEN 'ATT' THEN CASE
        WHEN abs(hashtext(id::text)) % 10 < 3 THEN 'PONTA'
        ELSE 'ATACANTE'
    END
END;

-- 3. Lock it down: the application always supplies a value going forward.
ALTER TABLE players ALTER COLUMN detailed_position SET NOT NULL;
