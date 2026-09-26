package wi.unigrav.core.player;

import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Versioned, immutable player placement and world velocity (metres and m/s).
 *
 * <p>The movement owner installs snapshots only after its complete movement or
 * transition validation. Constructing a snapshot is not a collision proof, a
 * propulsion permit or a network handshake. Client prediction and server state
 * must own separate instances even in integrated play.
 */
public record PlayerPhysicalSnapshot(Scope scope, long revision, PlayerBodyPlacement placement, Vec3d worldVelocity,
                                     Optional<Contacts> contacts) {
    public PlayerPhysicalSnapshot(Scope scope, long revision, PlayerBodyPlacement placement, Vec3d worldVelocity) {
        this(scope, revision, placement, worldVelocity, Optional.empty());
    }
    /** Observed native presentation, not standing/support or movement authority. */
    public record Contacts(boolean onGround, boolean horizontal, boolean vertical, float fallDistance,
                           boolean nearFootLocomotion) {
        public Contacts(boolean onGround, boolean horizontal, boolean vertical, float fallDistance) {
            this(onGround, horizontal, vertical, fallDistance, false);
        }
        public Contacts {
            if (!Float.isFinite(fallDistance) || fallDistance < 0)
                throw new IllegalArgumentException("Invalid fall distance");
        }
    }
    public PlayerPhysicalSnapshot {
        Objects.requireNonNull(scope, "scope");
        requireRevision(revision);
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(worldVelocity, "worldVelocity");
        Objects.requireNonNull(contacts, "contacts");
        // An installed snapshot must at least have finite representable geometry.
        // This does not test overlap or a path through the world.
        placement.bounds();
        placement.eye();
    }

    /** Independent physics lifetime; neither session nor epoch is borrowed from Personal G. */
    public record Scope(UUID playerId, UUID sessionId, int dimension, long worldEpoch) {
        public Scope {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(sessionId, "sessionId");
            if (worldEpoch < 0) {
                throw new IllegalArgumentException("worldEpoch must be nonnegative");
            }
        }
    }

    /** A compatibility coordinate paired with the exact state that encoded it; not a wire schema. */
    public record TransportSample(Scope scope, long revision, Vec3d position) {
        public TransportSample {
            Objects.requireNonNull(scope, "scope");
            requireRevision(revision);
            Objects.requireNonNull(position, "position");
        }
    }

    public enum Mismatch { PLAYER, SESSION, WORLD, REVISION }

    /** A decoded proposal is geometry only, and still needs admission, budget and collision checks. */
    public sealed interface Decode {
        record Candidate(PlayerBodyPlacement placement, Vec3d centerDelta) implements Decode {
            public Candidate {
                Objects.requireNonNull(placement, "placement");
                Objects.requireNonNull(centerDelta, "centerDelta");
            }
        }

        record Rejected(Mismatch reason) implements Decode {
            public Rejected {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** Retains the original representability failure for the owning network diagnostic boundary. */
        record Invalid(IllegalArgumentException cause) implements Decode {
            public Invalid {
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    public TransportSample transportSample() {
        return new TransportSample(scope, revision, placement.transport());
    }

    /**
     * Rejects mismatched lifetimes/revisions before interpreting any shape offset.
     * A stale packet is never decoded using the newest body and cannot turn an
     * anchor re-encoding into locomotion. No state or motion is written here.
     */
    public Decode decodeTransport(TransportSample sample) {
        Objects.requireNonNull(sample, "sample");
        Scope incoming = sample.scope();
        if (!scope.playerId().equals(incoming.playerId())) return new Decode.Rejected(Mismatch.PLAYER);
        if (!scope.sessionId().equals(incoming.sessionId())) return new Decode.Rejected(Mismatch.SESSION);
        if (scope.dimension() != incoming.dimension() || scope.worldEpoch() != incoming.worldEpoch()) {
            return new Decode.Rejected(Mismatch.WORLD);
        }
        if (revision != sample.revision()) return new Decode.Rejected(Mismatch.REVISION);
        try {
            PlayerBodyPlacement candidate = placement.proposalFromTransport(sample.position());
            candidate.bounds();
            candidate.eye();
            return new Decode.Candidate(candidate, placement.translationTo(candidate));
        } catch (IllegalArgumentException cause) {
            return new Decode.Invalid(cause);
        }
    }

    /**
     * Publishes an already resolved placement/velocity under a new revision.
     * Caller must have reserved real centre translation and validated the entire
     * path. Failed/pending transitions keep this complete snapshot unchanged.
     * A geometry-only successor has no fresh contact observation; it must not inherit stale support flags.
     */
    public PlayerPhysicalSnapshot successor(PlayerBodyPlacement resolvedPlacement, Vec3d resolvedWorldVelocity) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("physics revision exhausted; a new physics lifetime is required");
        }
        return new PlayerPhysicalSnapshot(scope, revision + 1, resolvedPlacement, resolvedWorldVelocity);
    }

    private static void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("physical revision must be nonnegative");
    }
}
