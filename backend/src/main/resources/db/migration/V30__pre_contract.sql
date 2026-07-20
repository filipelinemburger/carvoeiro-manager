CREATE TABLE pre_contract_agreement (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL,
    player_id      UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    from_club_id   UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    to_club_id     UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    wage           NUMERIC(14,2) NOT NULL,
    contract_years INT NOT NULL,
    status         VARCHAR(10) NOT NULL,
    created_date   DATE NOT NULL
);

CREATE INDEX idx_pre_contract_save_game ON pre_contract_agreement(save_game_id);
CREATE INDEX idx_pre_contract_player ON pre_contract_agreement(player_id);
