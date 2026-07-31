package br.com.carvoeiromanager.club;

/**
 * One zone's climate normals for one calendar month. Temperatures are whole degrees Celsius;
 * chances are probabilities in [0, 1] evaluated in {@code WeatherGenerator}.
 */
public record MonthProfile(int month, int minC, int maxC, double rainChance, double windChance) {
}
