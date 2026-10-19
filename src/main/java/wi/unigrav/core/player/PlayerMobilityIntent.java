package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;
import wi.unigrav.api.math.Vec3;

/** Explicit locomotion intent. Neither personal G nor a physical reference grants control authority. */
public final class PlayerMobilityIntent {
    private PlayerMobilityIntent() { }
    public enum Mode { WALK, FLIGHT }
    public enum Hover { OFF, ON }
    public enum EffectiveMode { WALK, FREE }
    public enum HoverStatus { OFF, DISSIPATIVE, POWERED, REJECTED }
    public enum Reason { NONE, CONTROL_UNAVAILABLE, RELEASE_REQUIRED, CAPTURE_UNAVAILABLE }
    public record Request(long id, Mode mode, Hover hover) {
        public Request {
            if (id <= 0) throw new IllegalArgumentException("Mobility request id must be positive");
            Objects.requireNonNull(mode); Objects.requireNonNull(hover);
        }
    }
    public record Input(Optional<Request> request, long pressId, boolean held, boolean focused) {
        public Input {
            Objects.requireNonNull(request);
            if (pressId < 0 || held && (pressId == 0 || !focused))
                throw new IllegalArgumentException("Invalid mobility press sample");
        }
        public static Input idle() { return new Input(Optional.empty(), 0, false, false); }
    }
    public record State(long acceptedRequest, Mode mode, Hover hover, EffectiveMode effectiveMode,
                        HoverStatus hoverStatus, Reason reason, boolean detached, long detachPress) {
        public State {
            if (acceptedRequest < 0 || detachPress < 0 || detached && detachPress == 0
                    || !detached && detachPress != 0 || detached && mode != Mode.FLIGHT)
                throw new IllegalArgumentException("Invalid accepted mobility identity");
            Objects.requireNonNull(mode); Objects.requireNonNull(hover); Objects.requireNonNull(effectiveMode);
            Objects.requireNonNull(hoverStatus); Objects.requireNonNull(reason);
        }
    }
    public record Tick(boolean shortJump, boolean detachedNow) {
        public static final Tick NONE = new Tick(false, false);
    }

    /** One actual server owner owns this latch. Repeated packets cannot create simulation time. */
    static final class Owner {
        private Request accepted;
        private long acceptedRequestId;
        private long highestRequestId;
        private Mode mode = Mode.WALK;
        private Hover hover = Hover.ON;
        private Reason requestReason = Reason.NONE;
        private long press, consumedPress, lastSequence = -1, lastTick = Long.MIN_VALUE;
        private long heldSampleTick = Long.MIN_VALUE;
        private long heldSamplePress;
        private Request releaseRetryRequest;
        private long releaseRetryEpoch = Long.MIN_VALUE;
        private long controlEpoch;
        private int detachHeldTicks;
        private boolean released = true, detached;
        private boolean detachArmed, detachPressActive;
        private boolean controlEligible, eligibilityInitialized;
        private long detachedPress, generation;

        boolean accept(Request request) {
            return accept(request, controlEligible);
        }

        boolean accept(Request request, boolean eligible) {
            Objects.requireNonNull(request);
            reconcileControl(eligible);
            // The wire has no reject id: retain only this exact transient release refusal for same-epoch retry.
            boolean releaseRetry = request.id() == highestRequestId && controlEligible
                    && releaseRetryEpoch == controlEpoch && request.equals(releaseRetryRequest);
            if (request.id() <= highestRequestId && !releaseRetry) {
                if (request.id() == acceptedRequestId && request.equals(accepted)
                        && mode == request.mode() && hover == request.hover()) return true;
                requestReason = controlEligible ? Reason.RELEASE_REQUIRED : Reason.CONTROL_UNAVAILABLE;
                return false;
            }
            if (request.id() > highestRequestId) highestRequestId = request.id();
            boolean safeRevocation = request.mode() == Mode.WALK && request.hover() == Hover.OFF;
            if (!controlEligible && !safeRevocation) {
                clearReleaseRetry();
                requestReason = Reason.CONTROL_UNAVAILABLE;
                return false;
            }
            if (request.mode() == Mode.WALK && !released && controlEligible) {
                releaseRetryRequest = request;
                releaseRetryEpoch = controlEpoch;
                requestReason = Reason.RELEASE_REQUIRED; return false;
            }
            long nextGeneration = Math.incrementExact(generation);
            accepted = request;
            acceptedRequestId = request.id();
            mode = request.mode(); hover = request.hover(); generation = nextGeneration;
            clearReleaseRetry();
            requestReason = Reason.NONE;
            if (mode == Mode.WALK) {
                detached = false;
                detachedPress = 0;
                clearDetachProgress();
            }
            return true;
        }

        /** Reconciles the current server-observed Creative + personal-G permission edge. */
        boolean reconcileControl(boolean eligible) {
            if (eligibilityInitialized && controlEligible == eligible) {
                if (eligible) return false;
                boolean dirty = mode != Mode.WALK || hover != Hover.OFF || detached || detachedPress != 0
                        || detachHeldTicks != 0 || detachArmed || detachPressActive;
                if (!dirty) return false;
                mode = Mode.WALK;
                hover = Hover.OFF;
                detached = false;
                detachedPress = 0;
                clearDetachProgress();
                requestReason = Reason.CONTROL_UNAVAILABLE;
                generation = Math.incrementExact(generation);
                return true;
            }
            boolean firstObservation = !eligibilityInitialized;
            eligibilityInitialized = true;
            controlEligible = eligible;
            clearReleaseRetry();
            if (firstObservation) controlEpoch = 1;
            else controlEpoch = Math.incrementExact(controlEpoch);
            mode = Mode.WALK;
            hover = eligible ? Hover.ON : Hover.OFF;
            detached = false;
            detachedPress = 0;
            clearDetachProgress();
            requestReason = eligible ? Reason.NONE : Reason.CONTROL_UNAVAILABLE;
            if (!firstObservation) generation = Math.incrementExact(generation);
            return true;
        }

        Tick advance(long tick, long sequence, Input input, boolean fresh, int threshold) {
            Objects.requireNonNull(input);
            if (threshold <= 0) throw new IllegalArgumentException("Invalid long-Space threshold");
            if (tick <= lastTick) return Tick.NONE;
            lastTick = tick;
            heldSampleTick = Long.MIN_VALUE;
            heldSamplePress = 0;
            if (!fresh || !input.focused()) {
                release();
                return Tick.NONE;
            }
            if (!input.held()) {
                releasePress();
                detachHeldTicks = 0;
                detachPressActive = false;
                detachArmed = controlEligible;
                return Tick.NONE;
            }
            // A fresh sample remains held across simulation ticks; retransmission count grants no time.
            if (sequence < lastSequence) return Tick.NONE;
            lastSequence = sequence;
            if (press != input.pressId()) {
                if (!released || input.pressId() <= consumedPress) return Tick.NONE;
                press = input.pressId(); detachHeldTicks = 0; released = false;
                detachPressActive = controlEligible && detachArmed;
                if (detachPressActive) detachArmed = false;
            }
            if (released || press != input.pressId()) return Tick.NONE;
            heldSampleTick = tick;
            heldSamplePress = press;
            boolean jump = consumedPress != press;
            if (jump) consumedPress = press;
            if (controlEligible && detachPressActive) detachHeldTicks = Math.incrementExact(detachHeldTicks);
            if (controlEligible && detachPressActive && detachHeldTicks == threshold) {
                generation = Math.incrementExact(generation);
                mode = Mode.FLIGHT; detached = true; detachedPress = press;
                return new Tick(jump, true);
            }
            return new Tick(jump, false);
        }
        /** A read-only receipt for this tick's admitted held press; correction cannot revive a consumed press. */
        boolean heldCurrent(Input input, long tick) {
            return lastTick == tick && heldSampleTick == tick && heldSamplePress == input.pressId()
                    && input.focused() && input.held();
        }
        private void releasePress() { press = 0; detachHeldTicks = 0; released = true; }
        private void clearDetachProgress() {
            detachHeldTicks = 0;
            detachArmed = false;
            detachPressActive = false;
        }
        private void clearReleaseRetry() {
            releaseRetryRequest = null;
            releaseRetryEpoch = Long.MIN_VALUE;
        }
        void release() {
            releasePress();
            clearDetachProgress();
            heldSampleTick = Long.MIN_VALUE;
            heldSamplePress = 0;
        }
        void correction() {
            generation = Math.incrementExact(generation);
            release(); detached = false; detachedPress = 0;
        }
        long nextRequestId() { return Math.incrementExact(highestRequestId); }
        long generation() { return generation; }
        long inputSequence() { return lastSequence; }
        int detachHeldTicks() { return detachHeldTicks; }
        boolean blocksCapture() { return mode == Mode.FLIGHT || detached; }
        Mode mode() { return mode; }
        Hover hover() { return hover; }
        State state(EffectiveMode effective, HoverStatus status, Reason reason) {
            return new State(acceptedRequestId, mode, hover, effective, status,
                    requestReason == Reason.NONE ? reason : requestReason, detached, detachedPress);
        }
    }

    public record HoverResult(Vec3 velocity, HoverStatus status, Reason reason) { }

    /** One bounded force controller; its result still travels through the real collision/budget owner. */
    static HoverResult hover(Vec3 velocityAfterForces, Vec3 effectiveAcceleration, Vec3 rawAcceleration,
                             boolean hasReference, Hover intent, boolean creativeGControl,
                             double brakingMps2, double dt) {
        return hover(velocityAfterForces, effectiveAcceleration, rawAcceleration, hasReference, intent,
                creativeGControl, brakingMps2, dt, false);
    }
    static HoverResult hover(Vec3 velocityAfterForces, Vec3 effectiveAcceleration, Vec3 rawAcceleration,
                             boolean hasReference, Hover intent, boolean creativeGControl,
                             double brakingMps2, double dt, boolean activeDrive) {
        finite(velocityAfterForces); finite(effectiveAcceleration); finite(rawAcceleration);
        if (!Double.isFinite(brakingMps2) || brakingMps2 < 0 || !Double.isFinite(dt) || dt <= 0)
            throw new IllegalArgumentException("Invalid hover controller tuning");
        if (intent == Hover.OFF) return new HoverResult(velocityAfterForces, HoverStatus.OFF, Reason.NONE);
        boolean dissipative = !hasReference && rawAcceleration.normSquared() == 0 && effectiveAcceleration.normSquared() == 0;
        if (!dissipative && !creativeGControl)
            return new HoverResult(velocityAfterForces, HoverStatus.REJECTED, Reason.CONTROL_UNAVAILABLE);
        Vec3 velocity = creativeGControl ? velocityAfterForces.sub(effectiveAcceleration.scale(dt)) : velocityAfterForces;
        double speed = velocity.norm();
        if (speed > 0 && !activeDrive) velocity = velocity.add(velocity.scale(-Math.min(speed, brakingMps2 * dt) / speed));
        return new HoverResult(velocity, creativeGControl ? HoverStatus.POWERED : HoverStatus.DISSIPATIVE, Reason.NONE);
    }
    private static void finite(Vec3 value) {
        Objects.requireNonNull(value);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z()))
            throw new IllegalArgumentException("Non-finite hover vector");
    }
}
