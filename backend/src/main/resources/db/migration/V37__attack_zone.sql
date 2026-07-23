-- AttackZone (onde forçar o jogo): re-weights who scores/assists an open-play goal.
-- Lineups default to MISTO, the neutral no-op zone whose scorer/assist weights equal the
-- pre-AttackZone formulas, so a managed club's persisted tactics attribute byte-identically
-- until the manager picks a zone.
ALTER TABLE lineups ADD COLUMN attack_zone VARCHAR(10) NOT NULL DEFAULT 'MISTO';

-- Clubs carry a nullable default: no backfill, so every existing (and AI) club resolves to MISTO
-- and stays byte-identical. Fresh careers insert clubs with a null default_attack_zone.
ALTER TABLE clubs ADD COLUMN default_attack_zone VARCHAR(10);
