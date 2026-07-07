package br.com.carvoeiromanager.match;

/**
 * One attribute as exposed to the client: the enum key, its display label, its group, and the
 * value. Sent in canonical order so the frontend can render the attribute board without knowing
 * the label/grouping mapping.
 */
public record AttributeView(String key, String label, String group, int value) {
}
