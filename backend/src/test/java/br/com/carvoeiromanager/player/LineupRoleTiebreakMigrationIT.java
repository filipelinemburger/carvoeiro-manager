package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers V44, which repairs the one arbitrary choice V41's step 2 could make: a player with lineup
 * rows at two clubs naming different archetypes.
 *
 * <p>Unlike {@link MultiPositionMigrationIT} and {@link ResearchBackfillMigrationIT}, which
 * reproduce their migration's UPDATE <em>shape</em> with hand-written SQL, this test executes the
 * REAL V44 file off the classpath. That is safe in the shared container even though V44 carries no
 * save filter: its {@code ambiguous} CTE only ever selects players holding two or more DISAGREEING
 * lineup rows, and no other test creates one. So running it verbatim cannot touch another test's
 * rows — and the statement under test is the one that will actually run in production, not a
 * paraphrase that could drift from it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupRoleTiebreakMigrationIT {

    private static final String MIGRATION = "/db/migration/V44__multi_position_lineup_role_tiebreak.sql";

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("tiebreak_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void theCurrentClubsLineupWinsWhenAStaleLineupRowDisagrees() throws IOException {
        SaveGameResponse save = createCareer();
        UUID formerClub = insertScratchClub(save.id(), "Antigo FC");
        UUID currentClub = insertScratchClub(save.id(), "Atual FC");
        // The transferred centre-back: STOPPER at his old club, BALL_PLAYING_DEFENDER at his new
        // one. V41 stored the stale club's derivation; the current club's must win.
        UUID playerId = insertZagueiro(save.id(), currentClub, "Transferido", "STOPPER++");
        insertLineupRow(insertScratchLineup(save.id(), formerClub), playerId, "STOPPER");
        insertLineupRow(insertScratchLineup(save.id(), currentClub), playerId, "BALL_PLAYING_DEFENDER");

        applyMigration();

        assertThat(familiarRolesOf(playerId)).isEqualTo("BALL_PLAYING_DEFENDER++");
    }

    @Test
    void aPlayerWithOneLineupRowIsLeftAlone() throws IOException {
        SaveGameResponse save = createCareer();
        UUID club = insertScratchClub(save.id(), "Unico FC");
        // V41 was already deterministic for this player, so V44 must not rewrite him — this is what
        // protects V43's researched values from being clobbered by a lineup-derived guess.
        UUID playerId = insertZagueiro(save.id(), club, "Fiel", "STOPPER++");
        insertLineupRow(insertScratchLineup(save.id(), club), playerId, "BALL_PLAYING_DEFENDER");

        applyMigration();

        assertThat(familiarRolesOf(playerId)).isEqualTo("STOPPER++");
    }

    @Test
    void anAmbiguousPlayerWhoseStoredValueCameFromElsewhereIsLeftAlone() throws IOException {
        SaveGameResponse save = createCareer();
        UUID formerClub = insertScratchClub(save.id(), "Antigo BC");
        UUID currentClub = insertScratchClub(save.id(), "Atual BC");
        // Ambiguous lineup rows, but the stored token is neither of their derivations — it is the
        // researched value V43 wrote. V44 only repairs a value that demonstrably came from one of
        // these rows, so this one survives.
        UUID playerId = insertZagueiro(save.id(), currentClub, "Pesquisado", "DEFENDER++");
        insertLineupRow(insertScratchLineup(save.id(), formerClub), playerId, "STOPPER");
        insertLineupRow(insertScratchLineup(save.id(), currentClub), playerId, "BALL_PLAYING_DEFENDER");

        applyMigration();

        assertThat(familiarRolesOf(playerId)).isEqualTo("DEFENDER++");
    }

    /** Runs the real V44 statement, verbatim, off the classpath. */
    private void applyMigration() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s must be on the classpath", MIGRATION).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.execute(sql);
    }

    private String familiarRolesOf(UUID playerId) {
        return jdbc.queryForObject("SELECT familiar_roles FROM players WHERE id = ?", String.class, playerId);
    }

    private UUID insertScratchClub(UUID saveGameId, String name) {
        UUID clubId = UUID.randomUUID();
        jdbc.update("INSERT INTO clubs (id, save_game_id, name, short_name) VALUES (?, ?, ?, 'SCR')",
                clubId, saveGameId, name);
        return clubId;
    }

    private UUID insertScratchLineup(UUID saveGameId, UUID clubId) {
        UUID lineupId = UUID.randomUUID();
        jdbc.update("INSERT INTO lineups (id, save_game_id, club_id, formation) VALUES (?, ?, ?, '4-4-2')",
                lineupId, saveGameId, clubId);
        return lineupId;
    }

    private void insertLineupRow(UUID lineupId, UUID playerId, String archetype) {
        jdbc.update("""
                INSERT INTO lineup_players (lineup_id, player_id, starting, archetype, focus)
                VALUES (?, ?, true, ?, 'BALANCED')
                """, lineupId, playerId, archetype);
    }

    private UUID insertZagueiro(UUID saveGameId, UUID clubId, String name, String familiarRoles) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO players (id, save_game_id, club_id, name, position, detailed_position,
                                     familiar_roles,
                                     age, nationality, pace, finishing, passing, tackling, goalkeeping,
                                     stamina, overall, form, morale, yellow_cards,
                                     suspension_remaining, injury_matches, fitness, youth_prospect,
                                     potential, injury_severity, wage, contract_years, transfer_listed,
                                     training_intensity, training_progress, season_appearances)
                VALUES (?, ?, ?, ?, 'DEF', 'ZAGUEIRO', ?, 25, 'Brasil', 50, 50, 50, 50, 50,
                        50, 60, 0, 0, 0,
                        0, 0, 100, false, 60, 'NONE', 0, 2, false, 'NORMAL', 0, 0)
                """, id, saveGameId, clubId, name, familiarRoles);
        return id;
    }
}
