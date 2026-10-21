package br.com.carvoeiromanager.board;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClubStrengthTest {

    @Test
    void averagesTheSixteenBestOverallsAndIgnoresTheRest() {
        // 20 players rated 41..60: the best 16 are 45..60, mean 52.5
        List<Integer> overalls = IntStream.rangeClosed(41, 60).boxed().toList();
        assertThat(ClubStrength.ofOveralls(overalls)).isCloseTo(52.5, within(1e-9));
    }

    @Test
    void averagesEveryoneWhenTheSquadIsShorterThanSixteen() {
        assertThat(ClubStrength.ofOveralls(List.of(50, 60, 70))).isCloseTo(60.0, within(1e-9));
    }

    @Test
    void anEmptySquadHasZeroStrength() {
        assertThat(ClubStrength.ofOveralls(List.of())).isZero();
    }
}
