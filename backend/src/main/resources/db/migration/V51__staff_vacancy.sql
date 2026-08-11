-- Records that a club's role slot was emptied BY THE PLAYER (poaching), as distinct from a slot
-- the seed research simply never filled.
--
-- StaffEffectsService floors an AI club's empty slot at AI_BASELINE_AVERAGE = 14.0, on the
-- reasoning that "we found nobody" must not read as "they employ nobody". Poaching deletes the
-- source club's staff row, which produced exactly that empty state — so stealing a rival's staff
-- promoted them to the baseline. 46 of the 53 poachable rows sit below 14.0, so the overwhelmingly
-- common outcome was that robbing a rival made the rival BETTER, and nothing ever refilled the
-- slot. A row here marks the vacancy as a real, earned hole, which the effects service reads as
-- neutral instead of as the baseline.
--
-- ON DELETE CASCADE to clubs is load-bearing: account deletion cascades save_games -> clubs ->
-- children, and AccountDeletionIT asserts nothing survives that walk.

CREATE TABLE staff_vacancy (
    id            UUID PRIMARY KEY,
    save_game_id  UUID NOT NULL,
    club_id       UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL,
    UNIQUE (club_id, role)
);

CREATE INDEX idx_staff_vacancy_club ON staff_vacancy(club_id);
