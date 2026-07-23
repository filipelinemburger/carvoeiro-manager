package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LineupService}'s per-player instruction validation helpers. These methods do
 * not touch any injected dependency, so the service is constructed with null collaborators.
 */
class LineupServiceInstructionValidationTest {

    private final LineupService service =
            new LineupService(null, null, null, null, null, null, null, null);

    private static Player player(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                50, 50, 50, 50, pos == Position.GK ? 50 : 10, 70, 50);
    }

    @Test
    void parseInstructionReturnsNullForNullOrBlank() {
        assertThat(service.parseInstruction(null)).isNull();
        assertThat(service.parseInstruction("")).isNull();
        assertThat(service.parseInstruction("   ")).isNull();
    }

    @Test
    void parseInstructionResolvesValidEnumNames() {
        assertThat(service.parseInstruction("INFILTRAR_NA_AREA")).isEqualTo(PlayerInstruction.INFILTRAR_NA_AREA);
        assertThat(service.parseInstruction("MARCACAO_DURA")).isEqualTo(PlayerInstruction.MARCACAO_DURA);
    }

    @Test
    void parseInstructionRejectsAnUnknownValue() {
        assertThatThrownBy(() -> service.parseInstruction("NOT_A_REAL_ONE"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Instrução pessoal inválida: NOT_A_REAL_ONE")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TACTICS);
    }

    @Test
    void aGoalkeeperCannotReceiveAnInstruction() {
        assertThatThrownBy(() -> service.validateInstructionForPlayer(
                player(Position.GK), PlayerInstruction.MARCACAO_DURA))
                .isInstanceOf(ApiException.class)
                .hasMessage("Goleiros não recebem instruções pessoais");
    }

    @Test
    void anOutfieldPlayerMayReceiveAnyInstruction() {
        for (Position pos : new Position[]{Position.DEF, Position.MID, Position.ATT}) {
            for (PlayerInstruction instr : PlayerInstruction.values()) {
                service.validateInstructionForPlayer(player(pos), instr); // must not throw
            }
        }
    }

    @Test
    void aNullInstructionIsAlwaysAcceptedEvenForAGoalkeeper() {
        service.validateInstructionForPlayer(player(Position.GK), null); // must not throw
    }
}
