package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Covers the V42 role-archetype migration (Testcontainers). Flyway has already applied V42 to the
 * fresh test database before any test method runs — and V42 <b>drops</b> {@code lineup_players.role},
 * so the legacy shape no longer exists to insert into. Following {@code MultiPositionMigrationIT}'s
 * approach, the pre-V42 row shape is recreated in a per-test scratch table and V42's UPDATE is
 * re-applied verbatim (only the table name differs), then the real migrated table is asserted
 * structurally.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RoleMigrationIT {

    /** legacy role → (expected archetype, expected focus) straight from the V42 mapping table. */
    private static final Map<String, String> EXPECTED_ARCHETYPE = Map.ofEntries(
            Map.entry("GOALKEEPER", "GOALKEEPER"),
            Map.entry("STOPPER", "STOPPER"),
            Map.entry("FULL_BACK", "FULLBACK"),
            Map.entry("BALL_PLAYING_DEF", "BALL_PLAYING_DEFENDER"),
            Map.entry("HOLDING_MID", "HOLDING"),
            Map.entry("BOX_TO_BOX", "BOX_TO_BOX"),
            Map.entry("PLAYMAKER", "PLAYMAKER"),
            Map.entry("POACHER", "POACHER"),
            Map.entry("TARGET_MAN", "TARGET_FORWARD"),
            Map.entry("WINGER", "WINGER"));

    @Autowired JdbcTemplate jdbc;

    private String probeTable;

    @BeforeEach
    void createProbeTable() {
        probeTable = "role_migration_probe_" + UUID.randomUUID().toString().replace("-", "");
        // The exact pre-V42 lineup_players shape: role still present, archetype/focus freshly added
        // and still nullable (V42's two ADD COLUMNs), slot_index inherited from V40.
        jdbc.execute("CREATE TABLE " + probeTable + " ("
                + "lineup_id UUID NOT NULL, player_id UUID NOT NULL, starting BOOLEAN NOT NULL,"
                + " role VARCHAR(20) NOT NULL, instruction VARCHAR(20), slot_index INT,"
                + " archetype VARCHAR(24), focus VARCHAR(16))");
    }

    @AfterEach
    void dropProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + probeTable);
    }

    /** V42's UPDATE, verbatim apart from the table name. */
    private void applyRoleBackfill() {
        jdbc.update("UPDATE " + probeTable + " SET\n"
                + "    archetype = CASE role\n"
                + "        WHEN 'FULL_BACK' THEN 'FULLBACK'\n"
                + "        WHEN 'TARGET_MAN' THEN 'TARGET_FORWARD'\n"
                + "        WHEN 'HOLDING_MID' THEN 'HOLDING'\n"
                + "        WHEN 'BALL_PLAYING_DEF' THEN 'BALL_PLAYING_DEFENDER'\n"
                + "        ELSE role END,\n"
                + "    focus = CASE role\n"
                + "        WHEN 'GOALKEEPER' THEN 'DEFEND'\n"
                + "        WHEN 'BALL_PLAYING_DEF' THEN 'DEFEND'\n"
                + "        WHEN 'HOLDING_MID' THEN 'DEFEND'\n"
                + "        WHEN 'POACHER' THEN 'ATTACK'\n"
                + "        WHEN 'TARGET_MAN' THEN 'ATTACK'\n"
                + "        ELSE 'BALANCED' END");
    }

    private void insertLegacyRow(String legacyRole) {
        jdbc.update("INSERT INTO " + probeTable
                        + " (lineup_id, player_id, starting, role) VALUES (?, ?, true, ?)",
                UUID.randomUUID(), UUID.randomUUID(), legacyRole);
    }

    @Test
    void everyLegacyRoleMapsToItsArchetypeAndThatArchetypesCanonicalDefaultFocus() {
        EXPECTED_ARCHETYPE.keySet().forEach(this::insertLegacyRow);

        applyRoleBackfill();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, archetype, focus, slot_index FROM " + probeTable);
        assertThat(rows).hasSize(EXPECTED_ARCHETYPE.size());
        for (Map<String, Object> row : rows) {
            String legacyRole = (String) row.get("role");
            String archetype = (String) row.get("archetype");
            String focus = (String) row.get("focus");

            assertThat(archetype).as("archetype for legacy %s", legacyRole)
                    .isEqualTo(EXPECTED_ARCHETYPE.get(legacyRole));
            // The migration's focus CASE must equal the mapped archetype's canonical default focus —
            // that pairing is what makes a migrated lineup score byte-identically.
            assertThat(focus).as("focus for legacy %s", legacyRole)
                    .isEqualTo(RoleArchetype.valueOf(archetype).canonicalDefaultFocus().name());
            // V42 never touches slot_index.
            assertThat(row.get("slot_index")).as("slot_index for legacy %s", legacyRole).isNull();
        }
    }

    @Test
    void migratedLineupPlayersHasArchetypeAndFocusNotNullAndNoRoleColumn() {
        Map<String, String> nullability = jdbc.queryForList("""
                        SELECT column_name, is_nullable FROM information_schema.columns
                        WHERE table_name = 'lineup_players'
                        """).stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("column_name"), r -> (String) r.get("is_nullable")));

        assertThat(nullability).containsEntry("archetype", "NO").containsEntry("focus", "NO");
        assertThat(nullability).doesNotContainKey("role");
        // V40's slot_index survived V42 untouched.
        assertThat(nullability).containsEntry("slot_index", "YES");
    }
}
