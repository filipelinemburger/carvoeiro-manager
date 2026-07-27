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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the V41 multi-position migration end to end (Testcontainers). Flyway has already applied
 * V41 to the fresh test database before any test method runs — exactly like
 * {@link DetailedPositionMigrationIT}'s V39 coverage, legacy-shaped rows are inserted directly via
 * JDBC (name-tagged {@code "Legacy ..."}) and the migration's UPDATE statements are re-applied
 * verbatim, scoped to this test's own save_game_id + name prefix so a Testcontainers database
 * shared across IT classes — and the real squad {@code createCareer()} generates in the same save,
 * which already carries new-style detailed_position values the legacy CASE doesn't cover — is never
 * touched.
 * <ul>
 *   <li>Every legacy detailed_position maps to its expected familiar_roles archetype.</li>
 *   <li>A ZAGUEIRO with a persisted STOPPER lineup role ends up STOPPER++ (role refines the default).</li>
 *   <li>PONTA rows split into PONTA_DIREITA/PONTA_ESQUERDA by id-hash parity (both appear);
 *       FALSO_9 rows become ATACANTE with familiar_roles = FALSE_9++.</li>
 *   <li>alt_positions is never backfilled (stays NULL); familiar_roles is never NULL.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MultiPositionMigrationIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("multipos_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void everyLegacyDetailedPositionMapsToItsExpectedFamiliarRoleArchetype() {
        SaveGameResponse save = createCareer();
        Map<String, String> expected = Map.ofEntries(
                Map.entry("GOLEIRO", "GOALKEEPER++"),
                Map.entry("ZAGUEIRO", "DEFENDER++"),
                Map.entry("LATERAL_ESQUERDO", "FULLBACK++"),
                Map.entry("LATERAL_DIREITO", "FULLBACK++"),
                Map.entry("VOLANTE", "HOLDING++"),
                Map.entry("MEIA_ARMADOR", "PLAYMAKER++"),
                Map.entry("PONTA", "WINGER++"),
                Map.entry("FALSO_9", "FALSE_9++"),
                Map.entry("ATACANTE", "POACHER++"));

        for (String legacy : expected.keySet()) {
            for (int i = 0; i < 10; i++) {
                insertLegacyPlayer(save.id(), UUID.randomUUID(), legacy, legacy + " " + i);
            }
        }

        applyFamiliarRolesBackfill(save.id());

        for (Map.Entry<String, String> e : expected.entrySet()) {
            List<String> roles = jdbc.queryForList(
                    "SELECT familiar_roles FROM players WHERE save_game_id = ? AND name LIKE ?",
                    String.class, save.id(), "Legacy " + e.getKey() + " %");
            assertThat(roles).as("familiar_roles for legacy %s", e.getKey()).hasSize(10)
                    .allMatch(r -> r.equals(e.getValue()));
        }
    }

    @Test
    void zagueiroWithPersistedStopperLineupRoleEndsUpStopperPlusPlus() {
        SaveGameResponse save = createCareer();
        UUID stopperId = UUID.randomUUID();
        insertLegacyPlayer(save.id(), stopperId, "ZAGUEIRO", "Stopper candidate");
        UUID plainZagueiroId = UUID.randomUUID();
        insertLegacyPlayer(save.id(), plainZagueiroId, "ZAGUEIRO", "Plain zagueiro");

        UUID clubId = insertScratchClub(save.id());
        UUID lineupId = insertScratchLineup(save.id(), clubId);
        // V42 later retired lineup_players.role in favour of archetype+focus, so the persisted
        // lineup role is written (and read back below) in its post-V42 shape. STOPPER maps to itself.
        jdbc.update("""
                INSERT INTO lineup_players (lineup_id, player_id, starting, archetype, focus)
                VALUES (?, ?, true, 'STOPPER', 'BALANCED')
                """, lineupId, stopperId);

        applyFamiliarRolesBackfill(save.id());

        assertThat(jdbc.queryForObject("SELECT familiar_roles FROM players WHERE id = ?",
                String.class, stopperId)).isEqualTo("STOPPER++");
        assertThat(jdbc.queryForObject("SELECT familiar_roles FROM players WHERE id = ?",
                String.class, plainZagueiroId)).isEqualTo("DEFENDER++");
    }

    @Test
    void pontaSplitsIntoBothWingersAndFalso9BecomesAtacanteWithFalse9Role() {
        SaveGameResponse save = createCareer();
        for (int i = 0; i < 40; i++) {
            insertLegacyPlayer(save.id(), UUID.randomUUID(), "PONTA", "Ponta " + i);
        }
        for (int i = 0; i < 10; i++) {
            insertLegacyPlayer(save.id(), UUID.randomUUID(), "FALSO_9", "Falso9 " + i);
        }

        applyFamiliarRolesBackfill(save.id());
        applyPositionRemap(save.id());

        List<String> pontaResults = jdbc.queryForList(
                "SELECT detailed_position FROM players WHERE save_game_id = ? AND name LIKE 'Legacy Ponta %'",
                String.class, save.id());
        assertThat(pontaResults).hasSize(40)
                .allMatch(d -> d.equals("PONTA_DIREITA") || d.equals("PONTA_ESQUERDA"));
        assertThat(pontaResults).contains("PONTA_DIREITA");
        assertThat(pontaResults).contains("PONTA_ESQUERDA");

        List<Map<String, Object>> falso9Results = jdbc.queryForList("""
                SELECT detailed_position, familiar_roles FROM players
                WHERE save_game_id = ? AND name LIKE 'Legacy Falso9 %'
                """, save.id());
        assertThat(falso9Results).hasSize(10);
        for (Map<String, Object> row : falso9Results) {
            assertThat(row.get("detailed_position")).isEqualTo("ATACANTE");
            assertThat(row.get("familiar_roles")).isEqualTo("FALSE_9++");
        }
    }

    @Test
    void altPositionsStaysNullAndFamiliarRolesIsNeverNullAfterMigration() {
        SaveGameResponse save = createCareer();
        for (int i = 0; i < 20; i++) {
            insertLegacyPlayer(save.id(), UUID.randomUUID(), "ATACANTE", "AltCheck " + i);
        }

        applyFamiliarRolesBackfill(save.id());
        applyPositionRemap(save.id());

        Integer nonNullAlt = jdbc.queryForObject("""
                SELECT count(*) FROM players
                WHERE save_game_id = ? AND name LIKE 'Legacy AltCheck %' AND alt_positions IS NOT NULL
                """, Integer.class, save.id());
        assertThat(nonNullAlt).isZero();

        Integer nullFamiliarRoles = jdbc.queryForObject(
                "SELECT count(*) FROM players WHERE save_game_id = ? AND familiar_roles IS NULL",
                Integer.class, save.id());
        assertThat(nullFamiliarRoles).isZero();
    }

    // --- helpers reproducing the V41 migration's statements, scoped to this test's own rows ---

    /** Reproduces V41 steps 1 and 2 (familiar_roles derivation + lineup-role refinement). */
    private void applyFamiliarRolesBackfill(UUID saveGameId) {
        jdbc.update("""
                UPDATE players SET familiar_roles = CASE detailed_position
                    WHEN 'GOLEIRO' THEN 'GOALKEEPER++'
                    WHEN 'ZAGUEIRO' THEN 'DEFENDER++'
                    WHEN 'LATERAL_ESQUERDO' THEN 'FULLBACK++'
                    WHEN 'LATERAL_DIREITO' THEN 'FULLBACK++'
                    WHEN 'VOLANTE' THEN 'HOLDING++'
                    WHEN 'MEIA_ARMADOR' THEN 'PLAYMAKER++'
                    WHEN 'PONTA' THEN 'WINGER++'
                    WHEN 'FALSO_9' THEN 'FALSE_9++'
                    WHEN 'ATACANTE' THEN 'POACHER++'
                END
                WHERE save_game_id = ? AND name LIKE 'Legacy %'
                """, saveGameId);
        // V41 keyed this off lp.role; V42 dropped that column, so the reproduction reads the
        // equivalent post-V42 lp.archetype values (FULL_BACK→FULLBACK etc. per the V42 mapping).
        jdbc.update("""
                UPDATE players p SET familiar_roles = CASE lp.archetype
                        WHEN 'STOPPER' THEN 'STOPPER++'
                        WHEN 'BALL_PLAYING_DEFENDER' THEN 'BALL_PLAYING_DEFENDER++'
                        WHEN 'TARGET_FORWARD' THEN 'TARGET_FORWARD++'
                    END
                FROM lineup_players lp
                WHERE lp.player_id = p.id
                  AND p.save_game_id = ? AND p.name LIKE 'Legacy %'
                  AND ((lp.archetype IN ('STOPPER', 'BALL_PLAYING_DEFENDER') AND p.detailed_position = 'ZAGUEIRO')
                       OR (lp.archetype = 'TARGET_FORWARD' AND p.detailed_position = 'ATACANTE'))
                """, saveGameId);
    }

    /** Reproduces V41 step 3 (PONTA/FALSO_9 remap). */
    private void applyPositionRemap(UUID saveGameId) {
        jdbc.update("""
                UPDATE players SET detailed_position = CASE
                    WHEN detailed_position = 'FALSO_9' THEN 'ATACANTE'
                    WHEN abs(hashtext(id::text)) % 2 = 0 THEN 'PONTA_DIREITA'
                    ELSE 'PONTA_ESQUERDA'
                END
                WHERE save_game_id = ? AND name LIKE 'Legacy %'
                  AND detailed_position IN ('PONTA', 'FALSO_9')
                """, saveGameId);
    }

    /** Inserts a minimal legacy-shaped player row for a coarse-consistent legacy detailed position. */
    private void insertLegacyPlayer(UUID saveGameId, UUID id, String legacyDetailedPosition, String nameSuffix) {
        jdbc.update("""
                INSERT INTO players (id, save_game_id, club_id, name, position, detailed_position,
                                     age, nationality, pace, finishing, passing, tackling, goalkeeping,
                                     stamina, overall, form, morale, yellow_cards,
                                     suspension_remaining, injury_matches, fitness, youth_prospect,
                                     potential, injury_severity, wage, contract_years, transfer_listed,
                                     training_intensity, training_progress, season_appearances)
                VALUES (?, ?, NULL, ?, ?, ?, 25, 'Brasil', 50, 50, 50, 50, 50,
                        50, 60, 0, 0, 0,
                        0, 0, 100, false, 60, 'NONE', 0, 2, false, 'NORMAL', 0, 0)
                """, id, saveGameId, "Legacy " + nameSuffix, coarseFor(legacyDetailedPosition),
                legacyDetailedPosition);
    }

    private UUID insertScratchClub(UUID saveGameId) {
        UUID clubId = UUID.randomUUID();
        jdbc.update("INSERT INTO clubs (id, save_game_id, name, short_name) VALUES (?, ?, 'Legacy FC', 'LFC')",
                clubId, saveGameId);
        return clubId;
    }

    private UUID insertScratchLineup(UUID saveGameId, UUID clubId) {
        UUID lineupId = UUID.randomUUID();
        jdbc.update("INSERT INTO lineups (id, save_game_id, club_id, formation) VALUES (?, ?, ?, '4-4-2')",
                lineupId, saveGameId, clubId);
        return lineupId;
    }

    private static String coarseFor(String legacyDetailedPosition) {
        return switch (legacyDetailedPosition) {
            case "GOLEIRO" -> "GK";
            case "ZAGUEIRO", "LATERAL_ESQUERDO", "LATERAL_DIREITO" -> "DEF";
            case "VOLANTE", "MEIA_ARMADOR" -> "MID";
            default -> "ATT"; // PONTA, FALSO_9, ATACANTE
        };
    }
}
