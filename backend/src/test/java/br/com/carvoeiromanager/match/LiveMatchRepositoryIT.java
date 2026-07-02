package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchRepositoryIT {

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LiveMatchRepository liveMatchRepository;

    @Test
    void persistsAndFindsBySaveGameId() {
        // Given a real career to satisfy the save_games FK
        User user = User.create("live_match_it_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();

        UUID fixtureId = UUID.randomUUID();
        UUID competitionId = UUID.randomUUID();

        // When a LiveMatch is created and persisted
        LiveMatch lm = LiveMatch.create(saveGameId, fixtureId, competitionId, 3,
                LocalDate.of(2026, 3, 1), 123L, true, "[]", "[]", "[]", "[]", "[]");
        liveMatchRepository.save(lm);

        // Then it can be found by saveGameId
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isPresent();

        // And after deletion it is gone
        liveMatchRepository.deleteBySaveGameId(saveGameId);
        assertThat(liveMatchRepository.findBySaveGameId(saveGameId)).isEmpty();
    }
}
