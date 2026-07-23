-- GameStyle (estilo de jogo): scales attack/defense/decay by how well the XI fits the style.
-- Lineups default to EQUILIBRADO, the neutral no-op style, so a managed club's persisted
-- tactics behave byte-identically until the manager picks a style.
ALTER TABLE lineups ADD COLUMN game_style VARCHAR(16) NOT NULL DEFAULT 'EQUILIBRADO';

-- Clubs carry an identity-matched default so existing AI clubs express a style consistent with
-- their tactical mentality (attacking sides keep the ball, defensive sides counter). This is a
-- deliberate gameplay carry-over, NOT byte-identical: on an upgraded save, ATTACKING/DEFENSIVE AI
-- clubs shift to a non-neutral attack/defense multiplier. Only EQUILIBRADO clubs stay neutral.
-- Fresh careers insert clubs with a null default_game_style (→ resolved as EQUILIBRADO) until the
-- seed wires per-club styles.
ALTER TABLE clubs ADD COLUMN default_game_style VARCHAR(16);
UPDATE clubs SET default_game_style = CASE default_mentality
    WHEN 'ATTACKING' THEN 'JOGO_DE_PASSES'
    WHEN 'DEFENSIVE' THEN 'CONTRA_ATAQUE'
    ELSE 'EQUILIBRADO'
END;
