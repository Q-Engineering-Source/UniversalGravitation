package wi.unigrav.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-thread-only, constant-memory aggregation for requested impact attacks.
 * A window deliberately retains only counters, running speed summaries, and one
 * peak identity; it never creates an entity-indexed diagnostic map.
 */
public final class ImpactDiagnosticWindow {
    public static final String ALL_SERVER_WORLDS = "all-server-worlds";

    public record Identity(String uuid, int entityId, String type, int dimension,
                           double x, double y, double z) {
        public Identity {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * {@code impactSpeedMps} is the speed passed to damage calculation; it may
     * be a relative ram speed. {@code cachedMotionMps} is separately observed
     * raw entity motion from the previous tracker sample, never inferred from
     * the impact speed.
     */
    public record Observation(Identity identity, boolean impactSpeedKnown, double impactSpeedMps,
                              boolean cachedMotionKnown, double cachedMotionMps,
                              boolean positionDeltaKnown, double positionDeltaMps,
                              boolean tickProgressKnown,
                              int previousTicksExisted, int currentTicksExisted,
                              boolean onGround, boolean collidedHorizontally,
                              boolean collidedVertically, boolean attackAccepted) {
        public Observation {
            Objects.requireNonNull(identity, "identity");
            if ((impactSpeedKnown && (!Double.isFinite(impactSpeedMps) || impactSpeedMps < 0.0))
                    || (cachedMotionKnown && (!Double.isFinite(cachedMotionMps) || cachedMotionMps < 0.0))
                    || (positionDeltaKnown && (!Double.isFinite(positionDeltaMps) || positionDeltaMps < 0.0))) {
                throw new IllegalArgumentException("invalid speed observation");
            }
        }

        public Observation withAttackAccepted(boolean accepted) {
            return new Observation(identity, impactSpeedKnown, impactSpeedMps, cachedMotionKnown, cachedMotionMps,
                    positionDeltaKnown, positionDeltaMps, tickProgressKnown, previousTicksExisted,
                    currentTicksExisted, onGround, collidedHorizontally, collidedVertically, accepted);
        }
    }

    public record Speed(long count, double maximumMps, double meanMps) { }

    public record Peak(Identity identity, boolean impactSpeedKnown, double impactSpeedMps,
                       boolean cachedMotionKnown, double cachedMotionMps,
                       boolean positionDeltaKnown, double positionDeltaMps,
                       boolean tickProgressKnown,
                       int previousTicksExisted, int currentTicksExisted,
                       boolean onGround, boolean collidedHorizontally,
                       boolean collidedVertically) { }

    public record Report(String scope, long requestedHits, long acceptedHits, long rejectedHits,
                         Speed impactSpeed, long impactSpeedUnknown, Speed cachedMotion, long cachedMotionUnknown,
                         Speed positionDelta, long positionDeltaUnknown,
                         long ticksAdvanced, long ticksStalled, long ticksDiscontinuous, long ticksUnknown,
                         long onGround, long collidedHorizontally, long collidedVertically,
                         Peak peak) { }

    private long requestedHits;
    private long acceptedHits;
    private long rejectedHits;
    private final RunningSpeed impactSpeed = new RunningSpeed();
    private long impactSpeedUnknown;
    private final RunningSpeed cachedMotion = new RunningSpeed();
    private long cachedMotionUnknown;
    private final RunningSpeed positionDelta = new RunningSpeed();
    private long positionDeltaUnknown;
    private long ticksAdvanced;
    private long ticksStalled;
    private long ticksDiscontinuous;
    private long ticksUnknown;
    private long onGround;
    private long collidedHorizontally;
    private long collidedVertically;
    private Peak peak;

    /** Records exactly one already-invoked {@code attackEntityFrom} call. */
    public void record(Observation observation) {
        requestedHits++;
        if (observation.attackAccepted()) acceptedHits++;
        else rejectedHits++;
        if (observation.impactSpeedKnown()) impactSpeed.add(observation.impactSpeedMps());
        else impactSpeedUnknown++;
        if (observation.cachedMotionKnown()) cachedMotion.add(observation.cachedMotionMps());
        else cachedMotionUnknown++;
        if (observation.positionDeltaKnown()) positionDelta.add(observation.positionDeltaMps());
        else positionDeltaUnknown++;
        if (observation.tickProgressKnown()) {
            int tickDelta = observation.currentTicksExisted() - observation.previousTicksExisted();
            if (tickDelta == 1) ticksAdvanced++;
            else if (tickDelta == 0) ticksStalled++;
            else ticksDiscontinuous++;
        } else {
            ticksUnknown++;
        }
        if (observation.onGround()) onGround++;
        if (observation.collidedHorizontally()) collidedHorizontally++;
        if (observation.collidedVertically()) collidedVertically++;
        if (peak == null || (observation.impactSpeedKnown()
                && (!peak.impactSpeedKnown() || observation.impactSpeedMps() > peak.impactSpeedMps()))) {
            peak = new Peak(observation.identity(), observation.impactSpeedKnown(), observation.impactSpeedMps(),
                    observation.cachedMotionKnown(), observation.cachedMotionMps(),
                    observation.positionDeltaKnown(), observation.positionDeltaMps(),
                    observation.tickProgressKnown(),
                    observation.previousTicksExisted(), observation.currentTicksExisted(),
                    observation.onGround(), observation.collidedHorizontally(),
                    observation.collidedVertically());
        }
    }

    /** Returns and clears the non-empty aggregate; empty windows remain silent. */
    public Optional<Report> drain() {
        if (requestedHits == 0) return Optional.empty();
        Report report = new Report(ALL_SERVER_WORLDS, requestedHits, acceptedHits, rejectedHits,
                impactSpeed.snapshot(), impactSpeedUnknown, cachedMotion.snapshot(), cachedMotionUnknown,
                positionDelta.snapshot(), positionDeltaUnknown, ticksAdvanced, ticksStalled,
                ticksDiscontinuous, ticksUnknown, onGround, collidedHorizontally, collidedVertically, peak);
        reset();
        return Optional.of(report);
    }

    public void reset() {
        requestedHits = acceptedHits = rejectedHits = 0;
        impactSpeed.reset();
        impactSpeedUnknown = 0;
        cachedMotion.reset();
        cachedMotionUnknown = 0;
        positionDelta.reset();
        positionDeltaUnknown = 0;
        ticksAdvanced = ticksStalled = ticksDiscontinuous = ticksUnknown = 0;
        onGround = collidedHorizontally = collidedVertically = 0;
        peak = null;
    }

    private static final class RunningSpeed {
        private long count;
        private double sum;
        private double maximum;

        void add(double value) {
            count++;
            sum += value;
            maximum = Math.max(maximum, value);
        }

        Speed snapshot() { return new Speed(count, maximum, count == 0 ? 0.0 : sum / count); }

        void reset() { count = 0; sum = 0.0; maximum = 0.0; }
    }
}
