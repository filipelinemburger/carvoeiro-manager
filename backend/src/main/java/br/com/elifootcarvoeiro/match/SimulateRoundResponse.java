package br.com.elifootcarvoeiro.match;

import java.util.List;

public record SimulateRoundResponse(
        int round,
        List<FixtureResultResponse> results,
        boolean seasonFinished
) {
}
