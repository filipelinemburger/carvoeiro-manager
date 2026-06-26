package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.player.Pressing;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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

        LineupPlayer lp = LineupPlayer.create(lineup.getId(), anyPlayer.getId(), true, PlayerRole.POACHER);
        lineupPlayerRepository.save(lp);
        assertThat(lineupPlayerRepository.findByLineupId(lineup.getId()).get(0).getRole())
                .isEqualTo(PlayerRole.POACHER);
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
