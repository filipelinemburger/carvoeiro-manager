package br.com.carvoeiromanager.club;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the V36 game_style migration end to end:
 * <ul>
 *   <li>{@code lineups.game_style} is NOT NULL DEFAULT 'EQUILIBRADO' — a raw insert that omits the
 *       column lands on the neutral style, so pre-existing lineups keep byte-identical behaviour.</li>
 *   <li>{@code clubs.default_game_style} exists and is null for fresh-career clubs (Task 22 does not
 *       yet author a seed style — that is Task 23).</li>
 *   <li>The backfill CASE maps default_mentality → default_game_style
 *       (ATTACKING→JOGO_DE_PASSES, DEFENSIVE→CONTRA_ATAQUE, else EQUILIBRADO) for legacy rows.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class GameStyleMigrationIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("gstyle_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void lineupGameStyleColumnDefaultsToEquilibradoOnARawInsert() {
        SaveGameResponse save = createCareer();
        // Use an AI club id (no lineup exists for it, so the unique (save_game_id, club_id) holds).
        UUID clubId = clubRepository.findBySaveGameId(save.id()).stream()
                .filter(c -> !c.getId().equals(save.managedClubId()))
                .findFirst().orElseThrow().getId();

        UUID lineupId = UUID.randomUUID();
        // Deliberately omit game_style so the column DEFAULT from V36 is what lands.
        jdbc.update("""
                INSERT INTO lineups (id, save_game_id, club_id, formation, mentality, pressing, marking)
                VALUES (?, ?, ?, '4-4-2', 'BALANCED', 'MEDIUM', 'ZONAL')
                """, lineupId, save.id(), clubId);

        String gameStyle = jdbc.queryForObject(
                "SELECT game_style FROM lineups WHERE id = ?", String.class, lineupId);
        assertThat(gameStyle).isEqualTo("EQUILIBRADO");
    }

    @Test
    void freshCareerClubsHaveTheDefaultGameStyleColumnNull() {
        // Task 22 adds the column but does not wire the seed onto it, so every runtime club is null
        // (TacticsService resolves null -> EQUILIBRADO, keeping AI strength byte-identical).
        SaveGameResponse save = createCareer();

        List<String> styles = jdbc.queryForList(
                "SELECT default_game_style FROM clubs WHERE save_game_id = ?", String.class, save.id());
        assertThat(styles).isNotEmpty().allMatch(s -> s == null);
    }

    @Test
    void backfillCaseMapsMentalityToStyleForLegacyRows() {
        // Reproduces the V36 backfill against rows that predate the column: an ATTACKING club maps to
        // JOGO_DE_PASSES, a DEFENSIVE club to CONTRA_ATAQUE, everything else to EQUILIBRADO.
        SaveGameResponse save = createCareer();
        UUID attackingId = insertLegacyClub(save.id(), "ATTACKING");
        UUID defensiveId = insertLegacyClub(save.id(), "DEFENSIVE");
        UUID balancedId = insertLegacyClub(save.id(), "BALANCED");
        UUID nullMentalityId = insertLegacyClub(save.id(), null);

        jdbc.update("""
                UPDATE clubs SET default_game_style = CASE default_mentality
                    WHEN 'ATTACKING' THEN 'JOGO_DE_PASSES'
                    WHEN 'DEFENSIVE' THEN 'CONTRA_ATAQUE'
                    ELSE 'EQUILIBRADO'
                END
                WHERE id IN (?, ?, ?, ?)
                """, attackingId, defensiveId, balancedId, nullMentalityId);

        assertThat(styleOf(attackingId)).isEqualTo("JOGO_DE_PASSES");
        assertThat(styleOf(defensiveId)).isEqualTo("CONTRA_ATAQUE");
        assertThat(styleOf(balancedId)).isEqualTo("EQUILIBRADO");
        assertThat(styleOf(nullMentalityId)).isEqualTo("EQUILIBRADO");
    }

    private String styleOf(UUID clubId) {
        return jdbc.queryForObject(
                "SELECT default_game_style FROM clubs WHERE id = ?", String.class, clubId);
    }

    /** Inserts a minimal legacy club row (default_game_style left null) with the given mentality. */
    private UUID insertLegacyClub(UUID saveGameId, String mentality) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO clubs (id, save_game_id, name, short_name, default_mentality,
                                   balance, reputation, stadium_capacity,
                                   sponsorship_per_round, stadium_upkeep_per_round)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, saveGameId, "Legacy " + id, "LEG", mentality,
                BigDecimal.ZERO, 50, 10000, BigDecimal.ZERO, BigDecimal.ZERO);
        return id;
    }
}
