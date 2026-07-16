package br.com.carvoeiromanager.season;

import java.util.List;

/**
 * The Preparador Físico's weekly training digest carried by a {@code TRAINING_REPORT}
 * news item. {@code quality} is the physio's StaffQuality name (REGULAR/BOM/EXCELENTE).
 */
public record TrainingReportResponse(String staffName, String quality, List<TrainingReportEntry> entries) {
}
