package wi.unigrav.core.player;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared per-owner-tick telemetry for candidate, contributor, face and interval classification work.
 * One unit pays for an actual classification, scan comparison, face/interval predicate or coordinate
 * conversion, or one actual producer/filter/material callback dispatch attempt. This records
 * attempts, not elapsed time or work performed inside third-party callbacks. No count rejects movement.
 */
final class FreePostprocessingWork {
    static final long MAX_UNITS = StoredDyadic.MAX_WORK_UNITS;

    static final class Exhausted extends IllegalStateException {
        Exhausted(long used, long requested) {
            super("Free postprocessing work limit exceeded: used=" + used + " requested=" + requested
                    + " limit=" + MAX_UNITS);
        }
    }

    private long units;
    private final Map<EnvironmentObservationWork.Unit, Long> classifiedUnits =
            new EnumMap<>(EnvironmentObservationWork.Unit.class);
    private long otherUnits;

    void charge(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("postprocessing charge must be positive");
        units = Math.addExact(units, amount);
        otherUnits = Math.addExact(otherUnits, amount);
    }

    void charge(EnvironmentObservationWork.Unit unit) {
        Objects.requireNonNull(unit);
        units = Math.incrementExact(units);
        classifiedUnits.merge(unit, 1L, Math::addExact);
    }

    record Usage(long totalUnits, Map<EnvironmentObservationWork.Unit, Long> classifiedUnits, long otherUnits) { }

    Usage usage() { return new Usage(units, Map.copyOf(classifiedUnits), otherUnits); }

    long units() {
        return units;
    }

    boolean terminal() {
        return false;
    }
}
