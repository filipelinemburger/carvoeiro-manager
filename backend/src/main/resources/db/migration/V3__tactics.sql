ALTER TABLE players
  ADD COLUMN form                 INT NOT NULL DEFAULT 0,
  ADD COLUMN yellow_cards         INT NOT NULL DEFAULT 0,
  ADD COLUMN suspension_remaining INT NOT NULL DEFAULT 0;

ALTER TABLE lineups
  ADD COLUMN mentality         VARCHAR(12) NOT NULL DEFAULT 'BALANCED',
  ADD COLUMN pressing          VARCHAR(8)  NOT NULL DEFAULT 'MEDIUM',
  ADD COLUMN marking           VARCHAR(10) NOT NULL DEFAULT 'ZONAL',
  ADD COLUMN penalty_taker_id  UUID NULL REFERENCES players(id),
  ADD COLUMN freekick_taker_id UUID NULL REFERENCES players(id),
  ADD COLUMN corner_taker_id   UUID NULL REFERENCES players(id);

ALTER TABLE lineup_players
  ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'BOX_TO_BOX';
