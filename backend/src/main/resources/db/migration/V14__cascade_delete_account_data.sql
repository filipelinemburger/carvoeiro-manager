-- Make every foreign key that points at clubs/players cascade (or null out) on
-- delete. The save_game_id foreign keys already cascade, but these cross-references
-- did not: deleting a save game cascade-deletes clubs and players, and the leftover
-- fixtures / match_events / lineup rows still referencing them blocked the delete
-- (order-dependent). Needed so deleting an account tears down ALL of its data.

ALTER TABLE fixtures
    DROP CONSTRAINT fixtures_home_club_id_fkey,
    ADD  CONSTRAINT fixtures_home_club_id_fkey FOREIGN KEY (home_club_id) REFERENCES clubs(id) ON DELETE CASCADE;
ALTER TABLE fixtures
    DROP CONSTRAINT fixtures_away_club_id_fkey,
    ADD  CONSTRAINT fixtures_away_club_id_fkey FOREIGN KEY (away_club_id) REFERENCES clubs(id) ON DELETE CASCADE;

ALTER TABLE match_events
    DROP CONSTRAINT match_events_player_id_fkey,
    ADD  CONSTRAINT match_events_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE;
ALTER TABLE match_events
    DROP CONSTRAINT match_events_club_id_fkey,
    ADD  CONSTRAINT match_events_club_id_fkey FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE;

ALTER TABLE lineup_players
    DROP CONSTRAINT lineup_players_player_id_fkey,
    ADD  CONSTRAINT lineup_players_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE;

-- Set-piece takers are nullable; clearing them (not deleting the lineup) is the
-- right behavior when the referenced player goes away.
ALTER TABLE lineups
    DROP CONSTRAINT lineups_penalty_taker_id_fkey,
    ADD  CONSTRAINT lineups_penalty_taker_id_fkey FOREIGN KEY (penalty_taker_id) REFERENCES players(id) ON DELETE SET NULL;
ALTER TABLE lineups
    DROP CONSTRAINT lineups_freekick_taker_id_fkey,
    ADD  CONSTRAINT lineups_freekick_taker_id_fkey FOREIGN KEY (freekick_taker_id) REFERENCES players(id) ON DELETE SET NULL;
ALTER TABLE lineups
    DROP CONSTRAINT lineups_corner_taker_id_fkey,
    ADD  CONSTRAINT lineups_corner_taker_id_fkey FOREIGN KEY (corner_taker_id) REFERENCES players(id) ON DELETE SET NULL;
