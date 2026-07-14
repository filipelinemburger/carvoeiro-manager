-- fixture_appearance's player_id/club_id FKs didn't cascade on delete (an oversight in V24),
-- so deleting an account whose save games had recorded appearances left orphaned rows
-- blocking the player/club delete — the same class of bug V14 fixed for match_events.

ALTER TABLE fixture_appearance
    DROP CONSTRAINT fixture_appearance_player_id_fkey,
    ADD  CONSTRAINT fixture_appearance_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE;
ALTER TABLE fixture_appearance
    DROP CONSTRAINT fixture_appearance_club_id_fkey,
    ADD  CONSTRAINT fixture_appearance_club_id_fkey FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE;
