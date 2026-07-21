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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the V33 stadium_name column end to end: a freshly created career copies each club's
 * seed stadium into {@code clubs.stadium_name}, so every club in the pool ends up with a real,
 * non-null stadium rather than falling back to the generic "Estádio " + name pattern.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class StadiumNameIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;

    private List<Club> createCareerClubs() {
        User user = User.create("stadium_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return clubRepository.findBySaveGameId(save.id());
    }

    @Test
    void everyClubInANewSaveHasANonNullStadiumName() {
        List<Club> clubs = createCareerClubs();

        assertThat(clubs).isNotEmpty();
        assertThat(clubs).allSatisfy(club ->
                assertThat(club.getStadiumName()).isNotNull().isNotBlank());
    }

    @Test
    void criciumaHasItsRealStadiumName() {
        List<Club> clubs = createCareerClubs();

        Club criciuma = clubs.stream()
                .filter(c -> c.getName().equals("Criciúma Esporte Clube"))
                .findFirst()
                .orElseThrow();

        assertThat(criciuma.getStadiumName()).isEqualTo("Estádio Heriberto Hülse");
    }

    @Test
    void everySeededClubGetsARealStadiumNotTheGenericFallback() {
        List<Club> clubs = createCareerClubs();

        assertThat(clubs).allSatisfy(club ->
                assertThat(club.getStadiumName()).isNotEqualTo("Estádio " + club.getName()));
    }
}
