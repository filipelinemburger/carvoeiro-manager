ALTER TABLE save_games ADD COLUMN last_finance_month INT NOT NULL DEFAULT 0;
ALTER TABLE finance_transaction ADD COLUMN tx_date DATE;
