package br.com.carvoeiromanager.club;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Month;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, lazily-loaded climate normals read from {@code seed/climate.json}.
 * Static because the table is game-wide reference data, not per-save state — the same
 * shape as the other static match utilities. Loads on first access and re-attempts
 * on failure, so a missing or malformed table remains diagnosable in running apps
 * rather than poisoning the class forever.
 */
public final class ClimateTable {

    private static Map<ClimateZone, List<MonthProfile>> cachedZones;

    private ClimateTable() {
    }

    public static MonthProfile profileFor(ClimateZone zone, Month month) {
        return getZones().get(zone).get(month.getValue() - 1);
    }

    private static synchronized Map<ClimateZone, List<MonthProfile>> getZones() {
        if (cachedZones == null) {
            cachedZones = load();
        }
        return cachedZones;
    }

    private record ClimateFile(Map<String, List<MonthProfile>> zones) {
    }

    private static Map<ClimateZone, List<MonthProfile>> load() {
        try (InputStream in = ClimateTable.class.getResourceAsStream("/seed/climate.json")) {
            if (in == null) {
                throw new IllegalStateException("seed/climate.json not found on the classpath");
            }
            ClimateFile file = new ObjectMapper().readValue(in, ClimateFile.class);
            Map<ClimateZone, List<MonthProfile>> byZone = new EnumMap<>(ClimateZone.class);
            for (ClimateZone zone : ClimateZone.values()) {
                List<MonthProfile> months = file.zones().get(zone.name());
                if (months == null || months.size() != 12) {
                    throw new IllegalStateException(
                            "climate.json must define 12 months for zone " + zone);
                }
                List<MonthProfile> sorted = months.stream()
                        .sorted((a, b) -> Integer.compare(a.month(), b.month()))
                        .toList();
                for (int i = 0; i < 12; i++) {
                    if (sorted.get(i).month() != i + 1) {
                        throw new IllegalStateException(
                                "climate.json zone " + zone + " is missing month " + (i + 1));
                    }
                }
                byZone.put(zone, sorted);
            }
            return Map.copyOf(byZone);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed/climate.json", e);
        }
    }
}
