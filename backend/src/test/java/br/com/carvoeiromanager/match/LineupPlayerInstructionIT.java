package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
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
 * Persistence roundtrip for {@link LineupPlayer#getInstruction()} plus proof that
 * {@link TacticsService} surfaces a persisted instruction in the built {@link TacticalProfile}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupPlayerInstructionIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;
    @Autowired TacticsService tacticsService;

    @Test
    void anInstructionSurvivesSaveAndReload() {
        SaveGameResponse save = newCareer();
        UUID club = save.managedClubId();
        Player anyPlayer = playerRepository.findByClubId(club).get(0);

        Lineup lineup = Lineup.create(save.id(), club, "4-3-3");
        lineupRepository.save(lineup);

        LineupPlayer lp = LineupPlayer.create(lineup.getId(), anyPlayer.getId(), true, PlayerRole.POACHER);
        assertThat(lp.getInstruction()).as("factory never sets an instruction").isNull();
        lp.setInstruction(PlayerInstruction.INFILTRAR_NA_AREA);
        lineupPlayerRepository.save(lp);

        LineupPlayer reloaded = lineupPlayerRepository.findByLineupId(lineup.getId()).get(0);
        assertThat(reloaded.getInstruction()).isEqualTo(PlayerInstruction.INFILTRAR_NA_AREA);
    }

    @Test
    void aStarterWithoutAnInstructionReloadsAsNull() {
        SaveGameResponse save = newCareer();
        UUID club = save.managedClubId();
        Player anyPlayer = playerRepository.findByClubId(club).get(0);

        Lineup lineup = Lineup.create(save.id(), club, "4-3-3");
        lineupRepository.save(lineup);
        lineupPlayerRepository.save(LineupPlayer.create(lineup.getId(), anyPlayer.getId(), true, PlayerRole.POACHER));

        assertThat(lineupPlayerRepository.findByLineupId(lineup.getId()).get(0).getInstruction()).isNull();
    }

    @Test
    void tacticsServiceSurfacesThePersistedInstructionInTheProfile() {
        SaveGameResponse save = newCareer();
        UUID club = save.managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player instructed = squad.get(0);
        Player plain = squad.get(1);

        Lineup lineup = Lineup.create(save.id(), club, "4-3-3");
        lineupRepository.save(lineup);
        LineupPlayer withInstr = LineupPlayer.create(lineup.getId(), instructed.getId(), true, PlayerRole.POACHER);
        withInstr.setInstruction(PlayerInstruction.CRUZAR_MAIS);
        lineupPlayerRepository.save(withInstr);
        lineupPlayerRepository.save(LineupPlayer.create(lineup.getId(), plain.getId(), true, PlayerRole.POACHER));

        TacticalProfile profile = tacticsService.profileFor(save.id(), club, List.of(instructed, plain));

        assertThat(profile.instructionFor(instructed.getId())).isEqualTo(PlayerInstruction.CRUZAR_MAIS);
        assertThat(profile.instructionFor(plain.getId())).as("a starter with no instruction stays null").isNull();
    }

    private SaveGameResponse newCareer() {
        User user = User.create("instr_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId());
    }
}
