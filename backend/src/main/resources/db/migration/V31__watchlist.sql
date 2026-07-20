CREATE TABLE watchlist_entry (
    id           UUID PRIMARY KEY,
    save_game_id UUID NOT NULL REFERENCES save_games(id) ON DELETE CASCADE,
    player_id    UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    created_at   TIMESTAMP NOT NULL,
    UNIQUE (save_game_id, player_id)
);

CREATE INDEX idx_watchlist_save_game ON watchlist_entry(save_game_id);
CREATE INDEX idx_watchlist_player ON watchlist_entry(player_id);
