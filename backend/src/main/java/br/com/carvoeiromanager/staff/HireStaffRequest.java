package br.com.carvoeiromanager.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A hire, identified by BOTH the slot the client clicked and the person it was showing there.
 *
 * <p>The index alone is not an identity. The shortlist is re-rolled from scratch on every call —
 * its seed folds in the save's current date, and its contents shrink as the club's own staff grows
 * — so an index resolves to a different person the moment the date advances or anyone is hired.
 * The name is what pins the request to the list the manager actually read; {@code
 * StaffCandidateGenerator} guarantees names are unique within one offer.
 */
public record HireStaffRequest(@NotNull Integer candidateIndex, @NotBlank String candidateName) {
}
