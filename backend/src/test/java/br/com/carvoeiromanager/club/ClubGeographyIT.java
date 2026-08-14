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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers V45 end to end: a freshly created career gives every club a real city, state,
 * climate zone, and researched stadium capacity, replacing the derived 8000 + reputation*500.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
// Runs on the FULL seed, Série A included: it checks every seeded club has real geography, so it must see every seeded club.
// The test profile otherwise trims the seed to the player's own competitions to keep
// the suite fast — see carvoeiro.seed.competitions in application-test.yml.
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class ClubGeographyIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;

    private List<Club> createCareerClubs() {
        User user = User.create("geo_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return clubRepository.findBySaveGameId(save.id());
    }

    @Test
    void everySeededClubHasGeographyAndARealCapacity() {
        List<Club> clubs = createCareerClubs();

        assertThat(clubs).hasSize(67);
        assertThat(clubs).allSatisfy(club -> {
            assertThat(club.getCity()).as("city of %s", club.getName()).isNotBlank();
            assertThat(club.getState()).as("state of %s", club.getName()).hasSize(2);
            assertThat(club.getClimateZone()).as("zone of %s", club.getName()).isNotNull();
            assertThat(club.getStadiumCapacity()).as("capacity of %s", club.getName())
                    .isBetween(2000, 80000);
        });
    }

    @Test
    void capacitiesAreRealRatherThanDerived() {
        List<Club> clubs = createCareerClubs();

        assertThat(byShortName(clubs, "FOR").getStadiumCapacity()).isEqualTo(63903);
        // Arena Barra FC opened in January 2025 at 5,500 (5,000 covered home + 500 away). The
        // seeded 3,000 predated the ground and was inherited from the Catarinense seed.
        assertThat(byShortName(clubs, "BAR").getStadiumCapacity()).isEqualTo(5500);
        // The old derived formula (8000 + reputation*500) could never produce a sub-8000 capacity.
        assertThat(byShortName(clubs, "BAR").getStadiumCapacity()).isLessThan(8000);
    }

    @Test
    void resolvedLocationFormatsCityAndState() {
        assertThat(byShortName(createCareerClubs(), "CRI").resolvedLocation())
                .isEqualTo("Criciúma/SC");
    }

    private static Club byShortName(List<Club> clubs, String shortName) {
        return clubs.stream()
                .filter(c -> c.getShortName().equals(shortName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no club " + shortName));
    }
}
