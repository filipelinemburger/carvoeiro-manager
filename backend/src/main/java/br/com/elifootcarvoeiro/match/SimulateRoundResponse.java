package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;

import java.util.List;

public record SimulateRoundResponse(
        int round,
        List<FixtureResultResponse> results,
        boolean seasonFinished,
        List<BookingResponse> bookings,
        List<SuspensionResponse> newSuspensions,
        FinanceRoundResponse finance
) {
}
