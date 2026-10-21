package br.com.carvoeiromanager.competition;

/**
 * Whether a competition is a national division or a state championship. The board finds the
 * managed club's league and state objectives by this, never by name or by whether the competition
 * has phases (every seed entry is {@code type: "LEAGUE"}, and Série C is phased).
 */
public enum CompetitionScope {
    NATIONAL,
    STATE
}
