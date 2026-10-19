package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Interval;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Witness;
import java.util.Objects;
import java.util.Optional;
import wi.unigrav.core.floor.BootSurfaceIdentity;
import wi.unigrav.core.floor.BootSurfaceQuery;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.player.StandingConstraints.Surface;

/** Arbitrary-up support witnesses and separate six-axis reference observations, without owner permission. */
final class FreeStandingObservation implements FreeReferenceModePrepared.SourceObservation {
    private final FreeOwnerTransaction transaction;
    private final CapsulePlacementMath.Pose pose;
    private final WorldCollisionCandidates.Snapshot capture;
    private final Aabb window;
    private final CollisionQueryFootprint.Limits limits;
    private final CapsuleSupportObservation.Complete support;
    private final CapsuleEnvironmentObservation.Prepared environment;
    private final FreeBootObservation.Observed boots;
    private final Runnable requireBaseline;
    private final Runnable requireSources;
    private final Vec3d physicalPf;
    private final CertifiedReferenceEndpoint endpoint;
    private final CapsulePlacementMath.Pose environmentPose;
    private FreeStandingObservation(FreeOwnerTransaction tx, CapsulePlacementMath.Pose pose,
            WorldCollisionCandidates.Snapshot capture, Aabb window, CollisionQueryFootprint.Limits limits,
            CapsuleSupportObservation.Complete support, CapsuleEnvironmentObservation.Prepared environment,
            FreeBootObservation.Observed boots, Runnable requireBaseline, Runnable requireSources, BootQueryArithmetic arithmetic) {
        this(tx, pose, capture, window, limits, support, environment, boots, requireBaseline, requireSources, arithmetic, null, pose);
    }
    private FreeStandingObservation(FreeOwnerTransaction tx, CapsulePlacementMath.Pose pose,
            WorldCollisionCandidates.Snapshot capture, Aabb window, CollisionQueryFootprint.Limits limits,
            CapsuleSupportObservation.Complete support, CapsuleEnvironmentObservation.Prepared environment,
            FreeBootObservation.Observed boots, Runnable requireBaseline, Runnable requireSources, BootQueryArithmetic arithmetic,
            CertifiedReferenceEndpoint endpoint, CapsulePlacementMath.Pose environmentPose) {
        transaction = tx; this.pose = pose; this.capture = capture; this.window = window; this.limits = limits;
        this.support = support; this.environment = environment; this.boots = boots; this.requireBaseline = requireBaseline;
        this.requireSources = requireSources;
        physicalPf = physicalFoot(pose.placement(), arithmetic);
        this.endpoint = endpoint; this.environmentPose = environmentPose;
    }
    static FreeStandingObservation fromComplete(FreeOwnerTransaction tx, CapsulePlacementMath.Pose pose,
            WorldCollisionCandidates.Snapshot capture, Aabb window, CollisionQueryFootprint.Limits limits,
            CapsuleSupportObservation.Complete support, CapsuleEnvironmentObservation.Prepared environment,
            FreeBootObservation.Observed boots, Runnable requireBaseline, Runnable requireSources, BootQueryArithmetic arithmetic) {
        var result = new FreeStandingObservation(tx, pose, capture, window, limits, support, environment, boots, requireBaseline, requireSources, arithmetic);
        if (!result.matches(tx)) throw new IllegalStateException("Free standing observations differ from current transaction");
        return result;
    }
    static FreeStandingObservation fromEndpoint(CertifiedReferenceEndpoint endpoint,
            WorldCollisionCandidates.Snapshot capture, Aabb window, CollisionQueryFootprint.Limits limits,
            CapsuleSupportObservation.Complete support, FreeStandingObservation original,
            FreeBootObservation.Observed boots, Runnable requireBaseline, Runnable requireSources, BootQueryArithmetic arithmetic) {
        endpoint.requireCurrent();
        if (original.transaction != endpoint.transaction() || original.endpoint != null)
            throw new IllegalArgumentException("endpoint standing requires the original force observation");
        var result = new FreeStandingObservation(endpoint.transaction(), endpoint.pose(), capture, window, limits,
                support, original.environment, boots, requireBaseline, requireSources, arithmetic, endpoint, original.environmentPose);
        if (!result.matches(endpoint.transaction())) throw new IllegalStateException("endpoint reference observation changed");
        return result;
    }
    void requireForceCurrent() { transaction.requireCurrent(); requireBaseline.run(); }
    void requireSourcesCurrentAfterInstall() {
        transaction.requireCurrent();
        requireSources.run();
        boots.requireSourcesCurrentAfterInstall(transaction);
        requireSources.run();
        transaction.requireCurrent();
    }
    PlayerPhysicalSnapshot accepted() { return transaction.accepted(); }
    CapsulePlacementMath.Pose pose() { return pose; }
    Vec3d physicalCenter() { return pose.placement().center(); }
    /** Rounded physical point observation, not a surface or path certificate. */
    Vec3d physicalPf() { return physicalPf; }
    Vec3d bodyUp() { return pose.placement().bodyUp(); }
    boolean isEndpointObservation() { return endpoint != null; }
    CapsuleSupportObservation.Complete support() { return support; }
    CapsuleEnvironmentObservation.Prepared environment() { return environment; }
    FreeBootObservation.Observed boots() { return boots; }

    /**
     * Resolves ordinary support from the same Free foot witnesses and qualified boot support
     * from a fresh, directly queried producer primitive in that same owner transaction.
     */
    Optional<Surface> actualSupport(BootSurfaceQuery.Measurements measurements) {
        if (measurements != null && boots.measurements().filter(value -> value == measurements).isEmpty())
            throw new IllegalArgumentException("Free support query is not the exact same-read qualified boot measurement");
        if (measurements != null && !measurements.source().physical().equals(accepted()))
            throw new IllegalArgumentException("Free support query source differs from its accepted owner snapshot");
        if (measurements != null && (endpoint == null ? !measurements.source().isAcceptedSensor()
                : !measurements.source().endpointCurrentFor(accepted())))
            throw new IllegalArgumentException("Free support sensor provenance differs from its query geometry");
        if (!support.matches(accepted(), pose, capture, window, transaction.queries(), transaction.numeric()))
            throw new IllegalStateException("Free support witnesses changed before reference resolution");

        if (measurements != null) {
            var qualified = measurements.qualifiedCapture().orElseThrow(() ->
                    new IllegalStateException("Free boot measurements lost their qualified producer capture"));
            if (qualified.snapshot().captureTick() != transaction.tick())
                throw new IllegalStateException("Free boot producer capture belongs to a different owner tick");
            var footSphere = support.foot().footSphere();
            for (BootSurfaceQuery.Candidate candidate : measurements.candidates()) {
                transaction.postprocessing().charge(1);
                var proximity = candidate.proximity();
                if (proximity.gap().lowerMetres() != 0.0 || proximity.gap().upperMetres() != 0.0) continue;
                var fragment = candidate.fragment();
                var face = fragment.geometry();
                for (Primitive producer : fragment.contributors()) {
                    transaction.postprocessing().charge(1);
                    transaction.postprocessing().charge(6);
                    if (!boundsIntersect(footSphere.bounds(), producer.rawBounds())) continue;
                    var touch = CapsuleFootContacts.queryQualifiedProducerFoot(pose, footSphere,
                            producer.rawBounds(), bodyUp(),
                            FreeOwnerTransaction.ACCURACY, transaction.queries());
                    if (touch.isEmpty()) continue;
                    var witness = touch.orElseThrow().footContact().witness().orElseThrow();
                    transaction.postprocessing().charge(1);
                    if (CapsuleSupportSlope.evaluate(witness, bodyUp(), transaction.numeric())
                            != CapsuleSupportSlope.SlopeEvidence.SUFFICIENT) continue;
                    if (!isExposedQualifiedTouch(witness, producer.rawBounds(), face.bounds(), face.outward(),
                            transaction.postprocessing())) continue;
                    // Identity is minted only after the actual qualified producer box proves a
                    // stored-foot contact on the fragment's exposed closed rectangle.
                    transaction.postprocessing().charge(fragment.contributors().size());
                    var identity = BootSurfaceIdentity.from(measurements, candidate,
                            transaction.postprocessing()::charge);
                    return Optional.of(Surface.fromBootIdentity(identity));
                }
            }
        }
        return nativeCardinalSupport();
    }
    @Override public boolean matches(FreeOwnerTransaction tx) {
        if (transaction != tx) return false;
        tx.requireCurrent(); requireBaseline.run();
        boolean matches = (endpoint == null || endpoint.current())
                && support.matches(tx.accepted(), pose, capture, window, tx.queries(), tx.numeric())
                && environment.matches(tx, environmentPose) && boots.matches(tx)
                && (endpoint == null || endpoint.current());
        tx.requireCurrent();
        return matches;
    }
    @Override public boolean recaptureMatches(FreeOwnerTransaction tx) {
        if (!matches(tx)) return false;
        var next = WorldCollisionCandidates.capture(tx, window,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, limits, tx.resources().environmentObservationWork());
        if (next instanceof WorldCollisionCandidates.Unavailable unavailable) {
            if (unavailable.cause().isPresent()) throw unavailable.cause().orElseThrow();
            throw new IllegalStateException("Free support recapture unavailable: " + unavailable.reason());
        }
        return capture.sameObservation((WorldCollisionCandidates.Snapshot) next)
                && boots.recaptureMatches(tx) && matches(tx);
    }

    /** The same stored-endpoint foot recipe used by standing observation, charged to the caller's Work. */
    static Vec3d physicalFoot(PlayerBodyPlacement placement, BootQueryArithmetic arithmetic) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(arithmetic, "arithmetic");
        Vec3d measured = PlayerBodyPlacement.physicalFoot(placement, arithmetic);
        if (!StoredRealizationLimits.sameRaw(measured, placement.freePhysicalFoot()))
            throw new IllegalStateException("cached Free foot differs from its stored-endpoint recipe");
        return measured;
    }

    private Optional<Surface> nativeCardinalSupport() {
        FreePostprocessingWork work = transaction.postprocessing();
        Vec3d up = bodyUp();
        CardinalFrame frame = null;
        for (CardinalFrame candidate : CardinalFrame.values()) {
            work.charge(1);
            if (candidate.up().equals(up)) { frame = candidate; break; }
        }
        if (frame == null) return Optional.empty();
        work.charge(1);
        int normal = axis(frame);
        work.charge(1);
        double plane = component(physicalPf, normal);
        for (CapsuleSupportObservation.Support witness : support.supports()) {
            work.charge(1);
            Aabb raw = witness.rawAabb();
            if (!isNativeSupportFace(raw, frame, plane, work)
                    || !witnessOnNativeFace(witness.witness(), raw, frame, plane, work)) continue;
            work.charge(1);
            Interval x = witness.witness().boxX();
            work.charge(1);
            Interval y = witness.witness().boxY();
            work.charge(1);
            Interval z = witness.witness().boxZ();
            work.charge(1);
            Vec3d point = new Vec3d(midpoint(x), midpoint(y), midpoint(z));
            int[] cell = new int[3];
            work.charge(1);
            boolean outwardPositive = positive(frame);
            work.charge(1);
            cell[normal] = floorCell(outwardPositive ? Math.nextDown(plane) : Math.nextUp(plane));
            for (int tangent = 0; tangent < 3; tangent++) {
                work.charge(1);
                if (tangent != normal) {
                    work.charge(1);
                    cell[tangent] = floorCell(component(point, tangent));
                }
            }
            work.charge(1);
            Face face = floorFace(frame);
            return Optional.of(new Surface(cell[0], cell[1], cell[2], face));
        }
        return Optional.empty();
    }

    private static boolean isExposedQualifiedTouch(Witness witness, Aabb raw, Aabb face,
            CardinalFrame outward, FreePostprocessingWork work) {
        work.charge(1);
        int normal = axis(outward);
        work.charge(1);
        double plane = component(face.min(), normal);
        work.charge(1);
        if (component(face.max(), normal) != plane) return false;
        work.charge(1);
        double exposedRawPlane = component(positive(outward) ? raw.max() : raw.min(), normal);
        work.charge(1);
        if (plane != exposedRawPlane) return false;
        work.charge(1);
        Interval normalContact = witnessInterval(witness, normal);
        work.charge(2);
        if (normalContact.lower() != plane || normalContact.upper() != plane) return false;
        for (int tangent = 0; tangent < 3; tangent++) {
            work.charge(1);
            if (tangent == normal) continue;
            work.charge(1);
            double faceMin = component(face.min(), tangent);
            work.charge(1);
            double faceMax = component(face.max(), tangent);
            work.charge(1);
            double rawMin = component(raw.min(), tangent);
            work.charge(1);
            double rawMax = component(raw.max(), tangent);
            work.charge(1);
            Interval contact = witnessInterval(witness, tangent);
            work.charge(2);
            if (faceMin >= faceMax || rawMin >= rawMax) return false;
            work.charge(2);
            if (faceMin < rawMin || faceMax > rawMax) return false;
            work.charge(2);
            if (contact.lower() < faceMin || contact.upper() > faceMax) return false;
            work.charge(2);
            if (contact.lower() < rawMin || contact.upper() > rawMax) return false;
        }
        return true;
    }
    private static boolean boundsIntersect(Aabb first, Aabb second) {
        return first.min().x() <= second.max().x() && first.max().x() >= second.min().x()
                && first.min().y() <= second.max().y() && first.max().y() >= second.min().y()
                && first.min().z() <= second.max().z() && first.max().z() >= second.min().z();
    }

    private static boolean isNativeSupportFace(Aabb raw, CardinalFrame outward, double plane, FreePostprocessingWork work) {
        work.charge(1);
        int normal = axis(outward);
        work.charge(1);
        double exposedPlane = component(positive(outward) ? raw.max() : raw.min(), normal);
        work.charge(1);
        if (plane != exposedPlane) return false;
        work.charge(1);
        double oppositePlane = component(positive(outward) ? raw.min() : raw.max(), normal);
        work.charge(1);
        if (positive(outward) ? oppositePlane >= plane : oppositePlane <= plane) return false;
        for (int tangent = 0; tangent < 3; tangent++) {
            work.charge(1);
            if (tangent == normal) continue;
            work.charge(2);
            if (component(raw.min(), tangent) >= component(raw.max(), tangent)) return false;
        }
        return true;
    }

    private static boolean witnessOnNativeFace(Witness witness, Aabb raw, CardinalFrame outward, double plane,
            FreePostprocessingWork work) {
        work.charge(1);
        int normal = axis(outward);
        work.charge(1);
        Interval normalContact = witnessInterval(witness, normal);
        work.charge(2);
        if (normalContact.lower() > plane || normalContact.upper() < plane) return false;
        for (int tangent = 0; tangent < 3; tangent++) {
            work.charge(1);
            if (tangent == normal) continue;
            work.charge(1);
            Interval interval = witnessInterval(witness, tangent);
            work.charge(1);
            double rawMin = component(raw.min(), tangent);
            work.charge(1);
            double rawMax = component(raw.max(), tangent);
            work.charge(2);
            if (interval.lower() <= rawMin || interval.upper() >= rawMax) return false;
        }
        return true;
    }

    private static Interval witnessInterval(Witness witness, int axis) {
        return switch (axis) {
            case 0 -> witness.boxX();
            case 1 -> witness.boxY();
            case 2 -> witness.boxZ();
            default -> throw new IllegalArgumentException("axis must be 0, 1, or 2");
        };
    }

    private static boolean contains(Interval interval, double value) {
        return interval.lower() <= value && interval.upper() >= value;
    }

    private static double midpoint(Interval interval) {
        return interval.lower() + (interval.upper() - interval.lower()) / 2.0;
    }

    private static int axis(CardinalFrame frame) {
        return frame == CardinalFrame.X_POS || frame == CardinalFrame.X_NEG ? 0
                : frame == CardinalFrame.Y_POS || frame == CardinalFrame.Y_NEG ? 1 : 2;
    }

    private static boolean positive(CardinalFrame frame) {
        return frame == CardinalFrame.X_POS || frame == CardinalFrame.Y_POS || frame == CardinalFrame.Z_POS;
    }

    private static Face floorFace(CardinalFrame frame) {
        return switch (frame) {
            case Y_POS -> Face.UP;
            case Y_NEG -> Face.DOWN;
            case X_POS -> Face.EAST;
            case X_NEG -> Face.WEST;
            case Z_POS -> Face.SOUTH;
            case Z_NEG -> Face.NORTH;
        };
    }

    private static double component(Vec3d value, int axis) {
        return switch (axis) {
            case 0 -> value.x();
            case 1 -> value.y();
            case 2 -> value.z();
            default -> throw new IllegalArgumentException("axis must be 0, 1, or 2");
        };
    }

    private static int floorCell(double value) {
        double floor = Math.floor(value);
        if (!Double.isFinite(value) || floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE)
            throw new ArithmeticException("Free support coordinate is outside block position range");
        return Math.toIntExact((long) floor);
    }
}
