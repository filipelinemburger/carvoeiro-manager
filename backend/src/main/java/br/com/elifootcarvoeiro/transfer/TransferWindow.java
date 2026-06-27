package br.com.elifootcarvoeiro.transfer;

import org.springframework.stereotype.Component;

/** Pure round-based transfer-window gating: a pre-season block and a mid-season block. */
@Component
public class TransferWindow {

    private static final int PRE_SEASON_ROUNDS = 3;
    private static final int MID_LENGTH = 3;

    public boolean isOpen(int currentRound, int totalRounds) {
        if (currentRound < PRE_SEASON_ROUNDS) {
            return true;
        }
        int midStart = totalRounds / 2;
        return currentRound >= midStart && currentRound < midStart + MID_LENGTH;
    }
}
