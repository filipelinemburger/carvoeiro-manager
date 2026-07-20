package br.com.carvoeiromanager.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void everyConstantHasItsExpectedStatus() {
        assertThat(ErrorCode.CANNOT_WATCH_OWN_PLAYER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.CLUB_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.COMPETITION_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.INJURED_PLAYER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INSUFFICIENT_FUNDS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_CONTRACT.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_CREDENTIALS.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.INVALID_LINEUP.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_ROLE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_SUBSTITUTION.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_TACTICS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_TOKEN.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.INVALID_TRAINING_FOCUS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INVALID_TRANSFER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.LINEUP_REQUIRED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.LIVE_MATCH_IN_PROGRESS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MALFORMED_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.MANAGED_CLUB_NOT_PLAYING.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MATCH_NOT_FINISHED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.MATCH_OVER.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_GK_FOUND.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.NO_LIVE_MATCH.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_MATCH_TO_PLAY.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NO_SUBS_REMAINING.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PLAYER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.PLAYER_NOT_PRE_CONTRACT_ELIGIBLE.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.PLAYER_REJECTS_TERMS.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.PRE_CONTRACT_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PRE_CONTRACT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.PRE_CONTRACT_WINDOW_CLOSED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.PROSPECT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.RENEWAL_BLOCKED_BY_PRE_CONTRACT.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SAVE_GAME_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SEASON_FINISHED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SEASON_NOT_FINISHED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SEASON_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.SQUAD_FULL.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SQUAD_TOO_SMALL.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.SUSPENDED_PLAYER.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.TRANSFER_OFFER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.TRANSFER_WINDOW_CLOSED.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.UNAUTHENTICATED.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.USERNAME_TAKEN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.VALIDATION_ERROR.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hasExactlyFortyFourConstants() {
        assertThat(ErrorCode.values()).hasSize(44);
    }
}
