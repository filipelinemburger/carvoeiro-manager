package br.com.carvoeiromanager.match;

import java.util.UUID;

/** One substitution: {@code inId} replaces {@code outId} at {@code minute} for the {@code home} side. */
public record LiveSub(int minute, boolean home, UUID outId, UUID inId) {
}
