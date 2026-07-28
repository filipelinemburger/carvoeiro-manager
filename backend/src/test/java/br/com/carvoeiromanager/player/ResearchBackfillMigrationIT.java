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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the V43 research-backfill migration end to end (Testcontainers). Flyway applies V43 once,
 * against a fresh and still-empty {@code players} table, when the shared container's schema is
 * created, so it is a no-op there. Exactly like {@link MultiPositionMigrationIT} and
 * {@link DetailedPositionMigrationIT}, this test inserts legacy-shaped rows directly via JDBC and
 * re-applies the migration's UPDATE shape itself, scoped to this test's own save so it never
 * collides with a real seeded career or another IT class sharing the same container.
 *
 * <p>Two things are load-bearing about that reproduction:
 * <ul>
 *   <li>V43's real statements carry no {@code save_game_id} filter at all — by design, since the
 *       researched data applies identically to every save that has a club or free agent with a
 *       matching name. The reproductions below add an explicit {@code AND p.save_game_id = ?} /
 *       {@code AND save_game_id = ?} purely to keep this test hermetic: a real career created by
 *       {@link #createCareer()} already has its own "Criciúma Esporte Clube" club and its own free
 *       agent named "Neto", both already carrying the correct researched values straight from
 *       {@code SaveGameService.applyResearchedPositionsAndRoles} — without the extra filter the
 *       reproduced UPDATE would touch those rows too (harmlessly, since the values are the same,
 *       but needlessly for a scoped test).</li>
 *   <li>The expected target values below (César Martins / Neto) are copied verbatim from
 *       {@code src/main/resources/seed/clubs.json} and {@code seed/free-agents.json} as of this
 *       migration's generation; if the seed research data for these specific players changes, the
 *       expectations here must be updated to match.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ResearchBackfillMigrationIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("backfill_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void clubPlayerMatchingSeedClubAndNameGetsTheResearchedTriple() {
        SaveGameResponse save = createCareer();
        UUID clubId = insertScratchClub(save.id(), "Criciúma Esporte Clube");
        // Pre-migration state: a legacy, randomly-derived triple different from the researched one.
        UUID matchedId = insertPlayer(save.id(), clubId, "César Martins", "DEF",
                "LATERAL_ESQUERDO", null, "FULLBACK++");

        applyClubBackfill(save.id(), "Criciúma Esporte Clube", "César Martins",
                "ZAGUEIRO", "LATERAL_DIREITO,VOLANTE", "STOPPER++");

        assertThat(detailedPositionOf(matchedId)).isEqualTo("ZAGUEIRO");
        assertThat(altPositionsOf(matchedId)).isEqualTo("LATERAL_DIREITO,VOLANTE");
        assertThat(familiarRolesOf(matchedId)).isEqualTo("STOPPER++");
    }

    @Test
    void playerWithNoNameMatchKeepsItsPriorValuesUntouched() {
        SaveGameResponse save = createCareer();
        UUID clubId = insertScratchClub(save.id(), "Criciúma Esporte Clube");
        // A newgen-named player: no seed data anywhere shares this name, so it must never match.
        UUID newgenId = insertPlayer(save.id(), clubId, "Newgen Nobody", "DEF",
                "ZAGUEIRO", null, "DEFENDER++");

        applyClubBackfill(save.id(), "Criciúma Esporte Clube", "César Martins",
                "ZAGUEIRO", "LATERAL_DIREITO,VOLANTE", "STOPPER++");

        assertThat(detailedPositionOf(newgenId)).isEqualTo("ZAGUEIRO");
        assertThat(altPositionsOf(newgenId)).isNull();
        assertThat(familiarRolesOf(newgenId)).isEqualTo("DEFENDER++");
    }

    @Test
    void freeAgentWithAUniqueSeedNameIsUpdated() {
        SaveGameResponse save = createCareer();
        // "Neto" appears exactly once across free-agents.json, so it is not skipped as ambiguous.
        UUID freeAgentId = insertPlayer(save.id(), null, "Neto", "ATT", "ATACANTE", null, "POACHER++");

        applyFreeAgentBackfill(save.id(), "Neto", "GOLEIRO", null, "GOALKEEPER++");

        assertThat(detailedPositionOf(freeAgentId)).isEqualTo("GOLEIRO");
        assertThat(altPositionsOf(freeAgentId)).isNull();
        assertThat(familiarRolesOf(freeAgentId)).isEqualTo("GOALKEEPER++");
    }

    // --- helpers reproducing V43's UPDATE shape, scoped to this test's own save_game_id ---

    /** Reproduces a V43 club-player statement (spec §3.1), scoped to this test's own save. */
    private void applyClubBackfill(UUID saveGameId, String clubName, String playerName,
            String detailedPosition, String altPositions, String familiarRoles) {
        jdbc.update("""
                UPDATE players p SET detailed_position = ?, alt_positions = ?, familiar_roles = ?
                FROM clubs c
                WHERE p.club_id = c.id AND c.name = ? AND p.name = ? AND p.save_game_id = ?
                """, detailedPosition, altPositions, familiarRoles, clubName, playerName, saveGameId);
    }

    /** Reproduces a V43 free-agent statement (spec §3.1), scoped to this test's own save. */
    private void applyFreeAgentBackfill(UUID saveGameId, String playerName,
            String detailedPosition, String altPositions, String familiarRoles) {
        jdbc.update("""
                UPDATE players SET detailed_position = ?, alt_positions = ?, familiar_roles = ?
                WHERE club_id IS NULL AND name = ? AND save_game_id = ?
                """, detailedPosition, altPositions, familiarRoles, playerName, saveGameId);
    }

    private String detailedPositionOf(UUID playerId) {
        return jdbc.queryForObject("SELECT detailed_position FROM players WHERE id = ?", String.class, playerId);
    }

    private String altPositionsOf(UUID playerId) {
        return jdbc.queryForObject("SELECT alt_positions FROM players WHERE id = ?", String.class, playerId);
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

    /**
     * Inserts a minimal player row with an explicit pre-migration position/alt/roles triple.
     * {@code clubId} may be {@code null} (free agent) — a null club_id is written as a SQL literal
     * rather than bound, matching {@link MultiPositionMigrationIT}'s {@code insertLegacyPlayer},
     * since the uuid column doesn't reliably accept a type-less bound null.
     */
    private UUID insertPlayer(UUID saveGameId, UUID clubId, String name, String position,
            String detailedPosition, String altPositions, String familiarRoles) {
        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO players (id, save_game_id, club_id, name, position, detailed_position,
                                     alt_positions, familiar_roles,
                                     age, nationality, pace, finishing, passing, tackling, goalkeeping,
                                     stamina, overall, form, morale, yellow_cards,
                                     suspension_remaining, injury_matches, fitness, youth_prospect,
                                     potential, injury_severity, wage, contract_years, transfer_listed,
                                     training_intensity, training_progress, season_appearances)
                VALUES (?, ?, %s, ?, ?, ?, ?, ?, 25, 'Brasil', 50, 50, 50, 50, 50,
                        50, 60, 0, 0, 0,
                        0, 0, 100, false, 60, 'NONE', 0, 2, false, 'NORMAL', 0, 0)
                """.formatted(clubId == null ? "NULL" : "?");
        if (clubId == null) {
            jdbc.update(sql, id, saveGameId, name, position, detailedPosition, altPositions, familiarRoles);
        } else {
            jdbc.update(sql, id, saveGameId, clubId, name, position, detailedPosition, altPositions, familiarRoles);
        }
        return id;
    }
}
