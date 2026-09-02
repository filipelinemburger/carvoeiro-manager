-- A loan negotiation. Season-scoped and purged at rollover, exactly like transfer_offer: the
-- offer records the haggling, while the loan itself lives on the player's three loan columns.
CREATE TABLE loan_offer (
    id                       UUID PRIMARY KEY,
    save_game_id             UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id                UUID NOT NULL,
    owner_club_id            UUID NOT NULL,
    borrower_club_id         UUID NOT NULL,
    owner_wage_share         INT NOT NULL,
    may_face_owner           BOOLEAN NOT NULL,
    counter_owner_wage_share INT,
    counter_may_face_owner   BOOLEAN,
    status                   VARCHAR(12) NOT NULL,
    round                    INT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_loan_offer_save_status ON loan_offer(save_game_id, status);
CREATE INDEX idx_loan_offer_save_player ON loan_offer(save_game_id, player_id);
