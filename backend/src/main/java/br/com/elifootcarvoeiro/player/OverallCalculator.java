package br.com.elifootcarvoeiro.player;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for a player's overall as a position-weighted average
 * of the 6 attributes. Extracted from SaveGameService so career creation, season
 * aging, and newgen generation all compute overall identically.
 *
 * Weights (sum = 1.0):
 *   GK:  goalkeeping .60, stamina .15, passing .15, pace .10
 *   DEF: tackling .40, pace .20, stamina .15, passing .15, finishing .10
 *   MID: passing .35, stamina .20, tackling .20, pace .15, finishing .10
 *   ATT: finishing .40, pace .25, passing .15, stamina .10, tackling .10
 */
@Component
public class OverallCalculator {

    public int compute(Position position, int pace, int finishing, int passing,
                       int tackling, int goalkeeping, int stamina) {
        double weighted = switch (position) {
            case GK -> goalkeeping * 0.60 + stamina * 0.15 + passing * 0.15 + pace * 0.10;
            case DEF -> tackling * 0.40 + pace * 0.20 + stamina * 0.15 + passing * 0.15 + finishing * 0.10;
            case MID -> passing * 0.35 + stamina * 0.20 + tackling * 0.20 + pace * 0.15 + finishing * 0.10;
            case ATT -> finishing * 0.40 + pace * 0.25 + passing * 0.15 + stamina * 0.10 + tackling * 0.10;
        };
        return (int) Math.round(weighted);
    }
}
