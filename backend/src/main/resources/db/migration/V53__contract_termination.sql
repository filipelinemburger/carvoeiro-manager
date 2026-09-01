-- Which club released this player. Set when a manager terminates a contract; read by
-- signFreeAgent to refuse re-signing your own cast-off, and cleared at season rollover so
-- the block lasts exactly one season.
ALTER TABLE players ADD COLUMN released_by_club_id UUID;
