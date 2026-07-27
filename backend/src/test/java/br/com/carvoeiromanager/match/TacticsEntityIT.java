package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsEntityIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    @Test
    void newColumnsPersistWithDefaultsAndMutators() {
        // Given a real career (lineups/lineup_players have FKs to save_games/clubs/players)
        User user = User.create("ent_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID club = save.managedClubId();
        Player anyPlayer = playerRepository.findByClubId(club).get(0);

        Lineup lineup = Lineup.create(save.id(), club, "4-3-3");
        assertThat(lineup.getMentality()).isEqualTo(Mentality.BALANCED);
        assertThat(lineup.getPressing()).isEqualTo(Pressing.MEDIUM);
        assertThat(lineup.getMarking()).isEqualTo(Marking.ZONAL);

        lineup.setMentality(Mentality.ATTACKING);
        lineupRepository.save(lineup);
        Lineup reloaded = lineupRepository.findById(lineup.getId()).orElseThrow();
        assertThat(reloaded.getMentality()).isEqualTo(Mentality.ATTACKING);

        LineupPlayer lp = LineupPlayer.create(lineup.getId(), anyPlayer.getId(), true, RoleArchetype.POACHER);
        lineupPlayerRepository.save(lp);
        LineupPlayer persisted = lineupPlayerRepository.findByLineupId(lineup.getId()).get(0);
        assertThat(persisted.getArchetype()).isEqualTo(RoleArchetype.POACHER);
        assertThat(persisted.getFocus()).isEqualTo(RoleArchetype.POACHER.canonicalDefaultFocus());
    }

    @Test
    void playerFormClampsAndSuspensionDecrements() {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID,
                25, "BRA", 70, 70, 70, 70, 10, 70, 72);
        assertThat(p.getForm()).isZero();
        assertThat(p.isEligible()).isTrue();

        for (int i = 0; i < 10; i++) p.applyForm(1);
        assertThat(p.getForm()).isEqualTo(5); // clamped

        p.applyForm(-20);
        assertThat(p.getForm()).isEqualTo(-5); // clamped

        p.addSuspension(2);
        assertThat(p.isEligible()).isFalse();
        p.decrementSuspension();
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
    }
}
