-- Background competitions now survive rollover in the season history.
--
-- Until now `season` only ever held competitions the managed club plays in, so the three
-- managed_club_* columns were always answerable and were declared NOT NULL (V4). Série C is the
-- first competition that is simulated in the background AND crowns a champion, and that champion
-- was being erased at rollover: no season row, no standings, no news (the feed is purged wholesale).
--
-- A background competition is archived for its champion and its final table. The player has no club
-- in it, so there is no honest managed club, managed club name, or finishing position to record —
-- the row carries NULL for all three, and every reader must treat NULL as "you were not in this
-- competition".
--
-- NULL is the only defensible value. The archive site derived the position from
-- `finalOrder.indexOf(managedClubId) + 1`, which is 0 for a non-member and fell into a defensive
-- branch that rewrote it as `finalOrder.size()` — silently recording the player as having finished
-- LAST in a competition they never entered. Persisted history must not contain that lie.
--
-- Nothing else in the schema depends on these three columns: `season` carries only
-- idx_season_save_game on save_game_id and UNIQUE (save_game_id, season_year, competition_id),
-- neither of which touches them, and no foreign key references them. Dropping NOT NULL is
-- therefore the whole change; existing rows (all managed) keep their values untouched.

ALTER TABLE season
    ALTER COLUMN managed_club_id DROP NOT NULL,
    ALTER COLUMN managed_club_name DROP NOT NULL,
    ALTER COLUMN managed_club_position DROP NOT NULL;
