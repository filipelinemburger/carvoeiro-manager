CREATE TABLE transfer_offer (
    id             UUID PRIMARY KEY,
    save_game_id   UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id      UUID NOT NULL,
    from_club_id   UUID NOT NULL,
    to_club_id     UUID NOT NULL,
    direction      VARCHAR(8) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL,
    counter_amount NUMERIC(15,2),
    status         VARCHAR(12) NOT NULL,
    round          INT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfer_offer_save_status ON transfer_offer(save_game_id, status);
CREATE INDEX idx_transfer_offer_save_to_club ON transfer_offer(save_game_id, to_club_id);
