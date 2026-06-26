package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsServiceIT {

    @Autowired TacticsService tacticsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("tacsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Synthetic XI for the read-only default test (no persistence -> no FK needed). */
    private Player mk(Position pos, int finishing, int passing) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, finishing, passing, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void defaultProfileWhenNoLineupRow() {
        List<Player> xi = new ArrayList<>();
        xi.add(mk(Position.GK, 5, 50));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.DEF, 30, 60));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.MID, 50, 80));
        xi.add(mk(Position.ATT, 90, 40));
        xi.add(mk(Position.ATT, 70, 40));

        // No lineup row for these ids -> default profile (no DB write happens).
        TacticalProfile profile = tacticsService.profileFor(UUID.randomUUID(), UUID.randomUUID(), xi);

        assertThat(profile.mentality()).isEqualTo(Mentality.BALANCED);
        assertThat(profile.formation()).isEqualTo("4-4-2");
        UUID bestFinisher = xi.stream().max(java.util.Comparator.comparingInt(Player::getFinishing))
                .orElseThrow().getId();
        assertThat(profile.penaltyTakerId()).isEqualTo(bestFinisher);
        assertThat(profile.roles().values()).contains(PlayerRole.GOALKEEPER, PlayerRole.POACHER);
    }

    @Test
    void loadsPersistedManagedProfile() {
        // Real career so the lineup/lineup_player FKs are satisfied.
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> xi = playerRepository.findByClubId(club);
        UUID firstId = xi.get(0).getId();

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineup.setMentality(Mentality.ATTACKING);
        lineupRepository.save(lineup);
        lineupPlayerRepository.save(LineupPlayer.create(lineup.getId(), firstId, true, PlayerRole.GOALKEEPER));

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);
        assertThat(profile.mentality()).isEqualTo(Mentality.ATTACKING);
        assertThat(profile.formation()).isEqualTo("4-3-3");
        assertThat(profile.roles().get(firstId)).isEqualTo(PlayerRole.GOALKEEPER);

        // band-default fill: a roster member with no persisted lineup_player row
        // still gets a role — its position-band default — not a missing entry.
        Player other = xi.get(1);
        assertThat(profile.roles()).containsKey(other.getId());
        assertThat(profile.roles().get(other.getId()))
                .isEqualTo(PlayerRole.defaultFor(other.getPosition()));
    }
}
