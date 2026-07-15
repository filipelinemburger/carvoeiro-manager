package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** One rolled, not-yet-hired staff candidate — never persisted. */
public record StaffCandidate(String name, StaffQuality quality, BigDecimal wage) {
}
