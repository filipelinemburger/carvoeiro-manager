package br.com.carvoeiromanager.season;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPreviewInsightsTest {

    @Test
    void composesExactlyThreeSentences() {
        List<String> insights = MatchPreviewInsights.compose(42L, "BALANCED", "MEDIUM", "ZONAL", 0, true);
        assertThat(insights).hasSize(3);
        assertThat(insights).allSatisfy(s -> assertThat(s).isNotBlank());
    }

    @Test
    void isDeterministicForTheSameSeed() {
        List<String> a = MatchPreviewInsights.compose(7L, "ATTACKING", "HIGH", "MAN_TIGHT", 4, false);
        List<String> b = MatchPreviewInsights.compose(7L, "ATTACKING", "HIGH", "MAN_TIGHT", 4, false);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void highPressingOpponentYieldsCounterAdvice() {
        List<String> insights = MatchPreviewInsights.compose(1L, "BALANCED", "HIGH", "ZONAL", 0, true);
        assertThat(insights.get(0)).containsAnyOf("costas da", "atrás da zaga");
    }

    @Test
    void strongerOpponentYieldsUnderdogTone() {
        List<String> insights = MatchPreviewInsights.compose(1L, "BALANCED", "MEDIUM", "ZONAL", 5, false);
        assertThat(insights.get(2)).containsAnyOf("favoritos", "superior");
    }

    @Test
    void unknownTacticalValuesFallBackToBalancedAdvice() {
        List<String> insights = MatchPreviewInsights.compose(3L, null, null, null, 0, false);
        assertThat(insights).hasSize(3);
    }
}
