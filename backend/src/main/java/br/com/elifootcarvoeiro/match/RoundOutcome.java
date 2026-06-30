package br.com.elifootcarvoeiro.match;

import java.util.List;

public record RoundOutcome(List<FixtureResultResponse> results,
                           List<BookingResponse> bookings,
                           List<SuspensionResponse> newSuspensions,
                           List<InjuryResponse> newInjuries) {
}
