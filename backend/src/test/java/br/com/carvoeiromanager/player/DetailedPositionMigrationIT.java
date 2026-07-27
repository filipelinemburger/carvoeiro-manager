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
 * Covers the V39 detailed_position migration end to end:
 * <ul>
 *   <li>Every player row in a fresh career has a non-null detailed_position (column is NOT NULL and
 *       the application supplies a value at construction).</li>
 *   <li>Both LATERAL_ESQUERDO and LATERAL_DIREITO appear among DEF players (the backfill's %5 split
 *       and the construction-time draw both produce the full set across a real roster).</li>
 *   <li>Coarse-consistency: for EVERY player, DetailedPosition.valueOf(detailed_position).coarse()
 *       equals its coarse position.</li>
 *   <li>The backfill CASE, applied directly to legacy-shaped rows, is coarse-consistent and never
 *       yields FALSO_9.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class DetailedPositionMigrationIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("detpos_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void everyPlayerHasANonNullDetailedPosition() {
        SaveGameResponse save = createCareer();
        Integer nulls = jdbc.queryForObject(
                "SELECT count(*) FROM players WHERE save_game_id = ? AND detailed_position IS NULL",
                Integer.class, save.id());
        assertThat(nulls).isZero();
    }

    @Test
    void bothLateraisAppearAmongDefenders() {
        SaveGameResponse save = createCareer();
        List<String> defDetailed = jdbc.queryForList(
                "SELECT DISTINCT detailed_position FROM players WHERE save_game_id = ? AND position = 'DEF'",
                String.class, save.id());
        assertThat(defDetailed).contains("LATERAL_ESQUERDO", "LATERAL_DIREITO");
    }

    @Test
    void everyPlayerRowIsCoarseConsistent() {
        SaveGameResponse save = createCareer();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT position, detailed_position FROM players WHERE save_game_id = ?", save.id());
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            String position = (String) row.get("position");
            String detailed = (String) row.get("detailed_position");
            assertThat(detailed).as("detailed_position for a %s player", position).isNotNull();
            assertThat(DetailedPosition.valueOf(detailed).coarse().name())
                    .as("%s must be coarse-consistent with %s", detailed, position)
                    .isEqualTo(position);
        }
    }

    @Test
    void backfillCaseIsCoarseConsistentForLegacyRows() {
        SaveGameResponse save = createCareer();
        // Insert legacy-shaped rows across the four positions, then reproduce the V39 backfill CASE
        // and assert every result maps back to its coarse position and never yields FALSO_9.
        for (String position : List.of("GK", "DEF", "MID", "ATT")) {
            for (int i = 0; i < 40; i++) {
                insertLegacyPlayer(save.id(), position);
            }
        }

        jdbc.update("""
                UPDATE players SET detailed_position = CASE position
                    WHEN 'GK' THEN 'GOLEIRO'
                    WHEN 'DEF' THEN CASE abs(hashtext(id::text)) % 5
                        WHEN 3 THEN 'LATERAL_ESQUERDO'
                        WHEN 4 THEN 'LATERAL_DIREITO'
                        ELSE 'ZAGUEIRO'
                    END
                    WHEN 'MID' THEN CASE abs(hashtext(id::text)) % 2
                        WHEN 1 THEN 'MEIA_ARMADOR'
                        ELSE 'VOLANTE'
                    END
                    WHEN 'ATT' THEN CASE
                        WHEN abs(hashtext(id::text)) % 10 < 3 THEN 'PONTA'
                        ELSE 'ATACANTE'
                    END
                END
                WHERE save_game_id = ? AND name LIKE 'Legacy %'
                """, save.id());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT position, detailed_position FROM players WHERE save_game_id = ? AND name LIKE 'Legacy %'",
                save.id());
        assertThat(rows).hasSize(160);
        for (Map<String, Object> row : rows) {
            String position = (String) row.get("position");
            String detailed = (String) row.get("detailed_position");
            assertThat(detailed).isNotEqualTo("FALSO_9");
            // V39's backfill CASE above is frozen, historical SQL and can still legitimately produce
            // 'PONTA' — a DetailedPosition name V41 retired (split into PONTA_DIREITA/PONTA_ESQUERDA).
            // Its coarse band (ATT) is asserted directly for that one legacy value instead of
            // round-tripping through the current enum, which no longer has that constant.
            String coarse = detailed.equals("PONTA") ? "ATT" : DetailedPosition.valueOf(detailed).coarse().name();
            assertThat(coarse).isEqualTo(position);
        }
    }

    /** Inserts a minimal legacy-shaped player row for the given coarse position. */
    private void insertLegacyPlayer(UUID saveGameId, String position) {
        jdbc.update("""
                INSERT INTO players (id, save_game_id, club_id, name, position, detailed_position,
                                     age, nationality, pace, finishing, passing, tackling, goalkeeping,
                                     stamina, overall, form, morale, yellow_cards,
                                     suspension_remaining, injury_matches, fitness, youth_prospect,
                                     potential, injury_severity, wage, contract_years, transfer_listed,
                                     training_intensity, training_progress, season_appearances)
                VALUES (?, ?, NULL, ?, ?, 'ZAGUEIRO', 25, 'Brasil', 50, 50, 50, 50, 50,
                        50, 60, 0, 0, 0,
                        0, 0, 100, false, 60, 'NONE', 0, 2, false, 'NORMAL', 0, 0)
                """, UUID.randomUUID(), saveGameId, "Legacy " + UUID.randomUUID(), position);
    }
}
