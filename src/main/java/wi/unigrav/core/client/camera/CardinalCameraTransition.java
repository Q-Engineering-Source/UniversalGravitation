package wi.unigrav.core.client.camera;

import java.util.Objects;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightVector;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** Pure render-time correction for accepted Cardinal reference changes. */
final class CardinalCameraTransition {

    private static final double CORRECTION_DURATION_TICKS = 5.0;
    private static final FlightVector ZERO = new FlightVector(0.0, 0.0, 0.0);

    record RenderSample(FlightAttitude attitude, FlightVector eyeReferenceCorrection,
                        FlightAttitude referenceCorrection) {
        FlightAttitude presentBody(FlightAttitude targetBody) {
            return Quaternion.fromAttitude(referenceCorrection)
                    .multiply(Quaternion.fromAttitude(targetBody)).normalizedCanonical().toAttitude();
        }

        FlightVector presentReferenceVector(FlightVector targetVector) {
            Quaternion rotation = Quaternion.fromAttitude(referenceCorrection);
            Quaternion vector = rotation.multiply(new Quaternion(0.0, targetVector.x, targetVector.y, targetVector.z))
                    .multiply(rotation.conjugate());
            return checkedVector(vector.x, vector.y, vector.z, "Cardinal model offset became non-finite");
        }
    }

    private Object actor;
    private Object world;
    private PlayerPhysicalSnapshot.Scope scope;
    private PlayerBodyReference.Effective reference;
    private Quaternion correction = Quaternion.IDENTITY;
    private FlightVector eyeReferenceOffset = ZERO;
    private FlightVector eyeCorrection = ZERO;
    private double correctionStartTick;
    private boolean initialized;

    /** A new actor/world/scope starts a fresh Cardinal camera lifecycle. */
    boolean needsCardinalSeed(Object actor, Object world, PlayerPhysicalSnapshot.Scope scope) {
        return !initialized || actor != this.actor || world != this.world || !scope.equals(this.scope);
    }

    /**
     * Keeps mouse input immediate in targetQ while only the accepted reference rebase decays
     * from the prior renderQ. Render time is the existing world tick plus its partial tick.
     */
    RenderSample sample(Object actor, Object world, PlayerPhysicalSnapshot.Scope scope,
            PlayerBodyReference.Effective nextReference, FlightVector nextEyeReferenceOffset,
            double renderTick,
            SixDofCameraOrientation orientation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(nextReference, "nextReference");
        requireFinite(nextEyeReferenceOffset, "nextEyeReferenceOffset");
        Objects.requireNonNull(orientation, "orientation");
        if (!Double.isFinite(renderTick)) {
            throw new IllegalArgumentException("renderTick must be finite");
        }

        if (needsCardinalSeed(actor, world, scope)) {
            this.actor = actor;
            this.world = world;
            this.scope = scope;
            reference = nextReference;
            correction = Quaternion.IDENTITY;
            eyeReferenceOffset = nextEyeReferenceOffset;
            eyeCorrection = ZERO;
            correctionStartTick = renderTick;
            initialized = true;
            return new RenderSample(orientation.attitude(), ZERO, Quaternion.IDENTITY.toAttitude());
        }

        if (!sameBasis(reference, nextReference)) {
            FlightAttitude oldRender = render(orientation.attitude(), renderTick);
            FlightVector oldRenderedEyeOffset = add(eyeReferenceOffset, eyeCorrectionAt(renderTick));
            orientation.rebaseCardinal(reference.frame(), reference.tangentHeading(),
                    nextReference.frame(), nextReference.tangentHeading());
            FlightAttitude nextTarget = orientation.attitude();
            correction = Quaternion.fromAttitude(oldRender)
                    .multiply(Quaternion.fromAttitude(nextTarget).conjugate()).normalizedCanonical();
            eyeCorrection = subtract(oldRenderedEyeOffset, nextEyeReferenceOffset);
            eyeReferenceOffset = nextEyeReferenceOffset;
            correctionStartTick = renderTick;
            reference = nextReference;
        } else {
            // The accepted eye offset may vary by a few world-coordinate ULPs as the body
            // moves. Keep the active reference correction; only the authoritative offset
            // itself follows that snapshot immediately.
            eyeReferenceOffset = nextEyeReferenceOffset;
        }
        Quaternion renderedCorrection = correctionAt(renderTick);
        return new RenderSample(renderedCorrection.multiply(Quaternion.fromAttitude(orientation.attitude()))
                .normalizedCanonical().toAttitude(), eyeCorrectionAt(renderTick), renderedCorrection.toAttitude());
    }

    void clear() {
        actor = null;
        world = null;
        scope = null;
        reference = null;
        correction = Quaternion.IDENTITY;
        eyeReferenceOffset = ZERO;
        eyeCorrection = ZERO;
        correctionStartTick = 0.0;
        initialized = false;
    }

    private FlightAttitude render(FlightAttitude target, double renderTick) {
        return correctionAt(renderTick).multiply(Quaternion.fromAttitude(target))
                .normalizedCanonical().toAttitude();
    }

    private Quaternion correctionAt(double renderTick) {
        return Quaternion.slerp(correction, Quaternion.IDENTITY, easedProgress(renderTick));
    }

    private FlightVector eyeCorrectionAt(double renderTick) {
        return scale(eyeCorrection, 1.0 - easedProgress(renderTick));
    }

    private double easedProgress(double renderTick) {
        double progress = Math.max(0.0, Math.min(1.0,
                (renderTick - correctionStartTick) / CORRECTION_DURATION_TICKS));
        return progress * progress * (3.0 - 2.0 * progress);
    }

    private static FlightVector add(FlightVector first, FlightVector second) {
        return checkedVector(first.x + second.x, first.y + second.y, first.z + second.z,
                "Cardinal eye reference offset overflowed");
    }

    private static FlightVector subtract(FlightVector first, FlightVector second) {
        return checkedVector(first.x - second.x, first.y - second.y, first.z - second.z,
                "Cardinal eye reference correction overflowed");
    }

    private static FlightVector scale(FlightVector vector, double amount) {
        return checkedVector(vector.x * amount, vector.y * amount, vector.z * amount,
                "Cardinal eye reference correction became non-finite");
    }

    private static FlightVector checkedVector(double x, double y, double z, String message) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalStateException(message);
        }
        return new FlightVector(x, y, z);
    }

    private static void requireFinite(FlightVector vector, String name) {
        Objects.requireNonNull(vector, name);
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static boolean sameBasis(PlayerBodyReference.Effective first,
            PlayerBodyReference.Effective second) {
        if (first.frame() != second.frame()) {
            return false;
        }
        double dx = first.tangentHeading().x() - second.tangentHeading().x();
        double dy = first.tangentHeading().y() - second.tangentHeading().y();
        double dz = first.tangentHeading().z() - second.tangentHeading().z();
        return Math.fma(dx, dx, Math.fma(dy, dy, dz * dz)) <= 1.0e-18;
    }

    private record Quaternion(double w, double x, double y, double z) {
        private static final Quaternion IDENTITY = new Quaternion(1.0, 0.0, 0.0, 0.0);

        private static Quaternion fromAttitude(FlightAttitude attitude) {
            return new Quaternion(attitude.w, attitude.x, attitude.y, attitude.z).normalizedCanonical();
        }

        private FlightAttitude toAttitude() {
            return new FlightAttitude(x, y, z, w);
        }

        private Quaternion multiply(Quaternion other) {
            return new Quaternion(
                    Math.fma(w, other.w, -x * other.x - y * other.y - z * other.z),
                    Math.fma(w, other.x, Math.fma(x, other.w, y * other.z - z * other.y)),
                    Math.fma(w, other.y, Math.fma(-x, other.z, y * other.w + z * other.x)),
                    Math.fma(w, other.z, Math.fma(x, other.y, -y * other.x + z * other.w)));
        }

        private Quaternion conjugate() {
            return new Quaternion(w, -x, -y, -z);
        }

        private Quaternion normalizedCanonical() {
            double length = Math.hypot(Math.hypot(w, x), Math.hypot(y, z));
            if (!Double.isFinite(length) || length == 0.0) {
                throw new IllegalStateException("Camera transition produced an invalid quaternion");
            }
            double inverseLength = 1.0 / length;
            Quaternion normalized = new Quaternion(w * inverseLength, x * inverseLength,
                    y * inverseLength, z * inverseLength);
            return normalized.w > 0.0 || normalized.w == 0.0 && (normalized.x > 0.0
                    || normalized.x == 0.0 && (normalized.y > 0.0
                    || normalized.y == 0.0 && normalized.z >= 0.0))
                    ? normalized : new Quaternion(-normalized.w, -normalized.x,
                            -normalized.y, -normalized.z);
        }

        private static Quaternion slerp(Quaternion from, Quaternion to, double amount) {
            Quaternion first = from.normalizedCanonical();
            Quaternion second = to.normalizedCanonical();
            double dot = Math.fma(first.w, second.w,
                    Math.fma(first.x, second.x, Math.fma(first.y, second.y, first.z * second.z)));
            if (dot < 0.0) {
                second = new Quaternion(-second.w, -second.x, -second.y, -second.z);
                dot = -dot;
            }
            dot = Math.max(-1.0, Math.min(1.0, dot));
            if (dot > 0.9995) {
                return new Quaternion(first.w + amount * (second.w - first.w),
                        first.x + amount * (second.x - first.x),
                        first.y + amount * (second.y - first.y),
                        first.z + amount * (second.z - first.z)).normalizedCanonical();
            }
            double angle = Math.acos(dot);
            double sine = Math.sin(angle);
            double firstWeight = Math.sin((1.0 - amount) * angle) / sine;
            double secondWeight = Math.sin(amount * angle) / sine;
            return new Quaternion(firstWeight * first.w + secondWeight * second.w,
                    firstWeight * first.x + secondWeight * second.x,
                    firstWeight * first.y + secondWeight * second.y,
                    firstWeight * first.z + secondWeight * second.z).normalizedCanonical();
        }
    }
}
