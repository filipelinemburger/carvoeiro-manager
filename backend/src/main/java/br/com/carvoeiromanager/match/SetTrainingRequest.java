package br.com.carvoeiromanager.match;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetTrainingRequest(@NotEmpty @Valid List<TrainingAssignment> assignments) {
}
