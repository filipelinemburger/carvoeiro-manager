package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the advance-to stop predicate — deterministic, no engine randomness
 * involved. Proves PAUSED_INJURY/PAUSED_RED never halt the loop for either target, while
 * HALF_TIME only halts an HALF_TIME-targeted skip and FULL_TIME always halts.
 */
class LiveMatchAdvanceToStopStatusTest {

    @Test
    void halfTimeTargetStopsAtHalfTime() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.HALF_TIME, "HALF_TIME")).isTrue();
    }

    @Test
    void fullTimeTargetStopsAtFullTime() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.FULL_TIME, "FULL_TIME")).isTrue();
    }

    @Test
    void fullTimeAlwaysStopsTheLoopRegardlessOfTarget() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.HALF_TIME, "FULL_TIME")).isTrue();
    }

    @Test
    void halfTimeDoesNotStopAFullTimeTargetedSkip() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.FULL_TIME, "HALF_TIME")).isFalse();
    }

    @Test
    void pausedInjuryNeverStopsEitherTarget() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.HALF_TIME, "PAUSED_INJURY")).isFalse();
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.FULL_TIME, "PAUSED_INJURY")).isFalse();
    }

    @Test
    void pausedRedNeverStopsEitherTarget() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.HALF_TIME, "PAUSED_RED")).isFalse();
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.FULL_TIME, "PAUSED_RED")).isFalse();
    }

    @Test
    void inProgressNeverStopsEitherTarget() {
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.HALF_TIME, "IN_PROGRESS")).isFalse();
        assertThat(LiveMatchService.isAdvanceToStopStatus(AdvanceTarget.FULL_TIME, "IN_PROGRESS")).isFalse();
    }
}
