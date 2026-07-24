-- Explicit formation-slot assignment for a starter: index into FormationSlots.of(formation)
-- (0 = GK slot). NULL for bench rows and legacy lineups saved before drag-and-drop existed —
-- the engine then falls back to greedy assignment.
ALTER TABLE lineup_players ADD COLUMN slot_index INT;
