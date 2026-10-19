package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Vec3d;

/** Saved player intent and the currently installed physical reference are different state. */
public final class PlayerBodyReference {
    private static final double EPSILON = 1.0e-10;

    private PlayerBodyReference() { }

    public enum Source { ENVIRONMENT, MANUAL, NATURAL, SAFE_NATIVE }

    public enum Transition {
        STABLE,
        ENVIRONMENT_OVERRIDE,
        MANUAL_DEFERRED,
        ENVIRONMENT_ADOPTION_BLOCKED,
        MANUAL_ADOPTION_BLOCKED,
        NATURAL_ADOPTION_BLOCKED,
        SAFE_NATIVE_ADOPTION_BLOCKED,
        MANUAL_RESUMED,
        MANUAL_CLEARED,
        NATIVE_RESTORE_PENDING
    }

    /** Immutable user preference. Environment transfers never rewrite its original frame or heading. */
    public record ManualIntent(long requestId, CardinalFrame frame, Vec3d tangentHeading) {
        public ManualIntent {
            if (requestId <= 0) throw new IllegalArgumentException("Manual body reference request id must be positive");
            Objects.requireNonNull(frame, "frame");
            requireTangent(tangentHeading, frame, "manual heading");
        }
    }

    /** Accepted physical presentation reference, including its frozen transported tangent. */
    public record Effective(Source source, CardinalFrame frame, Vec3d tangentHeading) {
        public Effective {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(frame, "frame");
            requireTangent(tangentHeading, frame, "effective heading");
        }
    }

    /** The installed mode is independent of the saved manual preference and its pending request. */
    public sealed interface OwnerReference permits CardinalReference, FreeReference {
        Optional<ManualIntent> manualIntent();
        Optional<State> cardinalState();
    }

    public record CardinalReference(State state) implements OwnerReference {
        public CardinalReference { Objects.requireNonNull(state, "state"); }
        public Optional<ManualIntent> manualIntent() { return state.manualIntent(); }
        public Optional<State> cardinalState() { return Optional.of(state); }
    }

    public record PendingCardinalTarget(long requestId, Source source, CardinalFrame frame, Vec3d tangentHeading,
                                        PlayerPhysicsProtocol.BodyReferenceReason reason) {
        public PendingCardinalTarget {
            Objects.requireNonNull(source); Objects.requireNonNull(frame); Objects.requireNonNull(reason);
            if (requestId < 0) throw new IllegalArgumentException("Pending Cardinal request id cannot be negative");
            requireTangent(tangentHeading, frame, "pending heading");
        }

        public PendingCardinalTarget(Source source, CardinalFrame frame, Vec3d tangentHeading,
                                     PlayerPhysicsProtocol.BodyReferenceReason reason) {
            this(0, source, frame, tangentHeading, reason);
        }
    }

    /** No effective Cardinal reference is invented for Free; prior intent and its retry remain explicit. */
    public record FreeReference(Optional<ManualIntent> manualIntent,
                                Optional<PendingCardinalTarget> pendingTarget,
                                Optional<Effective> previousEffective) implements OwnerReference {
        public FreeReference {
            Objects.requireNonNull(manualIntent);
            Objects.requireNonNull(pendingTarget);
            Objects.requireNonNull(previousEffective);
        }
        public FreeReference(Optional<ManualIntent> manualIntent,
                             Optional<PendingCardinalTarget> pendingTarget) {
            this(manualIntent, pendingTarget, Optional.empty());
        }
        public Optional<State> cardinalState() { return Optional.empty(); }
    }

    /** Persisted protocol state. A pending target never substitutes for the accepted geometry. */
    public record State(Optional<ManualIntent> manualIntent, Effective effective, Transition transition,
                        Optional<CardinalFrame> pendingFrame,
                        Optional<PlayerPhysicsProtocol.BodyReferenceReason> reason) {
        public State {
            Objects.requireNonNull(manualIntent, "manualIntent");
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(pendingFrame, "pendingFrame");
            Objects.requireNonNull(reason, "reason");
            if (pendingFrame.isPresent() != reason.isPresent())
                throw new IllegalArgumentException("Pending body reference requires both target and reason");
            boolean blocked = transition == Transition.ENVIRONMENT_ADOPTION_BLOCKED
                    || transition == Transition.MANUAL_ADOPTION_BLOCKED
                    || transition == Transition.NATURAL_ADOPTION_BLOCKED
                    || transition == Transition.SAFE_NATIVE_ADOPTION_BLOCKED;
            if (blocked != pendingFrame.isPresent())
                throw new IllegalArgumentException("Only a blocked body reference carries a pending target and reason");
            if (transition == Transition.ENVIRONMENT_OVERRIDE
                    && (manualIntent.isEmpty() || effective.source() != Source.ENVIRONMENT))
                throw new IllegalArgumentException("Environment override requires a saved manual preference and accepted environment reference");
            if (transition == Transition.MANUAL_DEFERRED
                    && manualIntent.isEmpty())
                throw new IllegalArgumentException("Deferred manual preference must be saved");
        }

        public static State initial(CardinalFrame frame, Vec3d heading) {
            return new State(Optional.empty(), new Effective(Source.SAFE_NATIVE, frame, heading),
                    Transition.STABLE, Optional.empty(), Optional.empty());
        }

        public State withManual(Optional<ManualIntent> nextManual, Transition nextTransition) {
            return new State(nextManual, effective, nextTransition, Optional.empty(), Optional.empty());
        }

        public State blocked(Transition blockedTransition, CardinalFrame target,
                             PlayerPhysicsProtocol.BodyReferenceReason blockedReason) {
            return new State(manualIntent, effective, blockedTransition, Optional.of(target), Optional.of(blockedReason));
        }

        public State accepted(Effective nextEffective, Transition nextTransition) {
            return new State(manualIntent, nextEffective, nextTransition, Optional.empty(), Optional.empty());
        }
    }

    /** Sample initial yaw once when the owner is created; subsequent transfers use accepted heading only. */
    public static Vec3d initialHeading(CardinalFrame frame, float yawDegrees) {
        if (!Float.isFinite(yawDegrees)) throw new IllegalArgumentException("Non-finite initial yaw");
        double radians = Math.toRadians(yawDegrees);
        return tangentOrFallback(new Vec3d(-Math.sin(radians), 0, Math.cos(radians)), frame);
    }

    /** Carry the last accepted forward by the minimum rotation between cardinal up vectors. */
    public static Vec3d transportHeading(CardinalFrame from, CardinalFrame to, Vec3d acceptedHeading) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireTangent(acceptedHeading, from, "accepted heading");
        Vec3d a = from.up();
        Vec3d b = to.up();
        double cosine = dot(a, b);
        Vec3d transported;
        if (cosine < -1.0 + EPSILON) {
            // At 180 degrees the accepted tangent is the deterministic rotation axis.
            transported = acceptedHeading;
        } else {
            Vec3d cross = cross(a, b);
            Vec3d first = cross(cross, acceptedHeading);
            Vec3d second = cross(cross, first);
            transported = add(acceptedHeading, add(first, scale(second, 1.0 / (1.0 + cosine))));
        }
        return tangentOrFallback(transported, to);
    }

    public static Vec3d tangentOrFallback(Vec3d candidate, CardinalFrame frame) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(frame, "frame");
        Vec3d up = frame.up();
        Vec3d tangent = subtract(candidate, scale(up, dot(candidate, up)));
        double magnitude = length(tangent);
        if (!Double.isFinite(magnitude) || magnitude < EPSILON) {
            Vec3d axis = Math.abs(up.x()) < 0.8 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            tangent = subtract(axis, scale(up, dot(axis, up)));
            magnitude = length(tangent);
        }
        return scale(tangent, 1.0 / magnitude);
    }

    private static void requireTangent(Vec3d heading, CardinalFrame frame, String name) {
        Objects.requireNonNull(heading, name);
        double magnitude = length(heading);
        if (!Double.isFinite(magnitude) || Math.abs(magnitude - 1.0) > 1.0e-9
                || Math.abs(dot(heading, frame.up())) > 1.0e-9) {
            throw new IllegalArgumentException(name + " must be a unit tangent of its frame");
        }
    }

    private static Vec3d add(Vec3d a, Vec3d b) {
        return new Vec3d(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }
    private static Vec3d subtract(Vec3d a, Vec3d b) {
        return new Vec3d(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }
    private static Vec3d scale(Vec3d a, double factor) {
        return new Vec3d(a.x() * factor, a.y() * factor, a.z() * factor);
    }
    private static Vec3d cross(Vec3d a, Vec3d b) {
        return new Vec3d(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }
    private static double dot(Vec3d a, Vec3d b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
    private static double length(Vec3d a) { return Math.hypot(Math.hypot(a.x(), a.y()), a.z()); }
}
