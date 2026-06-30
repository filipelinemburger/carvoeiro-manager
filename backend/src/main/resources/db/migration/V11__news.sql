CREATE TABLE news_item (
    id           UUID         NOT NULL,
    save_game_id UUID         NOT NULL,
    seq          INT          NOT NULL,
    event_date   DATE,
    type         VARCHAR(16)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_news_item PRIMARY KEY (id)
);

CREATE INDEX idx_news_item_save_seq ON news_item (save_game_id, seq);
