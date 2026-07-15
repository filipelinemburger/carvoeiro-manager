package br.com.carvoeiromanager.staff;

import jakarta.validation.constraints.NotNull;

public record HireStaffRequest(@NotNull Integer candidateIndex) {
}
