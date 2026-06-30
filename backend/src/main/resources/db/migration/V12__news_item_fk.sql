-- news_item was introduced (V11) without the save_games FK every other save-scoped table has.
-- Add it so the feed is cleaned up by the same ON DELETE CASCADE as clubs, players, events, etc.
ALTER TABLE news_item
    ADD CONSTRAINT fk_news_item_save_game
    FOREIGN KEY (save_game_id) REFERENCES save_games(id) ON DELETE CASCADE;
