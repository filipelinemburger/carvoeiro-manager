-- A loan splits "who owns the player" from "who he plays for". club_id keeps meaning the latter,
-- so every existing reader stays correct; owner_club_id being non-null IS the definition of
-- "on loan". loan_wage_share is the percentage the OWNER pays, 0-100.
ALTER TABLE players ADD COLUMN owner_club_id UUID;
ALTER TABLE players ADD COLUMN loan_wage_share INT;
ALTER TABLE players ADD COLUMN loan_may_face_owner BOOLEAN;
