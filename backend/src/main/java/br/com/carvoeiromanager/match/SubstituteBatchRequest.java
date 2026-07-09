package br.com.carvoeiromanager.match;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** All substitutions from one dialog apply — validated and persisted atomically. */
public record SubstituteBatchRequest(@NotEmpty List<@Valid SubstituteRequest> subs) {
}
