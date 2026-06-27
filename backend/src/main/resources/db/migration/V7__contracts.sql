-- Free agents have no club; every player has a contract length (years remaining).
ALTER TABLE players ALTER COLUMN club_id DROP NOT NULL;
ALTER TABLE players ADD COLUMN contract_years INT NOT NULL DEFAULT 0;

-- Personal terms attached to an OUTGOING buy offer (null for AI/incoming bids).
ALTER TABLE transfer_offer ADD COLUMN wage NUMERIC(14,2);
ALTER TABLE transfer_offer ADD COLUMN contract_years INT;
