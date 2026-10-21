package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class BoardMigrationIT {

    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private SaveGameResponse createCareer() {
        User user = User.create("board_mig_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }

    @Test
    void boardTablesExist() {
        for (String table : new String[]{"board_states", "board_objectives", "board_confidence_changes"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    @Test
    void backfillMarksTheCatarinenseAsStateAndGivesEachSerieItsTier() {
        SaveGameResponse save = createCareer();
        // Put the save's competitions back into the shape a pre-V57 save has after the ALTERs ran,
        // then replay the backfill statements exactly as the migration writes them.
        jdbc.update("UPDATE competitions SET scope = 'NATIONAL', tier = NULL WHERE save_game_id = ?", save.id());
        jdbc.update("UPDATE competitions SET scope = 'STATE' WHERE name = 'Campeonato Catarinense'");
        jdbc.update("UPDATE competitions SET tier = 1 WHERE name = 'Série A'");
        jdbc.update("UPDATE competitions SET tier = 2 WHERE name = 'Série B'");
        jdbc.update("UPDATE competitions SET tier = 3 WHERE name = 'Série C'");

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT scope, tier FROM competitions WHERE save_game_id = ? AND name = 'Campeonato Catarinense'",
                save.id());
        assertThat(state.get("scope")).isEqualTo("STATE");
        assertThat(state.get("tier")).isNull();

        Map<String, Object> serieB = jdbc.queryForMap(
                "SELECT scope, tier FROM competitions WHERE save_game_id = ? AND name = 'Série B'", save.id());
        assertThat(serieB.get("scope")).isEqualTo("NATIONAL");
        assertThat(serieB.get("tier")).isEqualTo(2);
    }

    @Test
    void aSaveCanBeSacked() {
        SaveGameResponse created = createCareer();
        SaveGame save = saveGameRepository.findById(created.id()).orElseThrow();
        save.setStatus(SaveGameStatus.SACKED);
        saveGameRepository.save(save);

        assertThat(saveGameRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.SACKED);
    }
}
