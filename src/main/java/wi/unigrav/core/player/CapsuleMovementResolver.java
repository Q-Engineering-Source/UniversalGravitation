package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ElongatedOriginalPrefixReceipt;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.InstalledPoseReceipt;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashMap;

/** Fixed-attitude padded paths. Initial-contact inheritance is a separate mathematical prerequisite. */
final class CapsuleMovementResolver {
    /** tube is the segment proof source; observationTube and scale retain a secondary query's own parameter domain. */
    enum BoundaryInput { PADDED_TRANSLATION, ACTUAL_TRANSLATION }
    record Query(int ordinal, Aabb obstacle, Optional<BoundaryResult> boundary, Optional<ContactResult> actualFinal,
                 Optional<ElongatedOriginalPrefixReceipt> bodyPrefix, WorldCollisionCandidates.Snapshot capture,
                 CapsulePlacementMath.Translation tube, CapsulePlacementMath.Translation observationTube,
                 double sourceParameterScale, BoundaryInput boundaryInput) {
        Query(int ordinal, Aabb obstacle, Optional<BoundaryResult> boundary, Optional<ContactResult> actualFinal,
              Optional<ElongatedOriginalPrefixReceipt> bodyPrefix, WorldCollisionCandidates.Snapshot capture,
              CapsulePlacementMath.Translation tube) {
            this(ordinal, obstacle, boundary, actualFinal, bodyPrefix, capture, tube, tube, 1,
                    BoundaryInput.PADDED_TRANSLATION);
        }
        Query(int ordinal, Aabb obstacle, Optional<BoundaryResult> boundary, Optional<ContactResult> actualFinal,
              Optional<ElongatedOriginalPrefixReceipt> bodyPrefix, WorldCollisionCandidates.Snapshot capture,
              CapsulePlacementMath.Translation tube, CapsulePlacementMath.Translation observationTube,
              double sourceParameterScale) {
            this(ordinal, obstacle, boundary, actualFinal, bodyPrefix, capture, tube, observationTube,
                    sourceParameterScale, BoundaryInput.PADDED_TRANSLATION);
        }
        Query(int ordinal, Aabb obstacle, Optional<BoundaryResult> boundary, Optional<ContactResult> actualFinal,
              WorldCollisionCandidates.Snapshot capture, CapsulePlacementMath.Translation tube) {
            this(ordinal, obstacle, boundary, actualFinal, Optional.empty(), capture, tube, tube, 1);
        }
        Query {
            Objects.requireNonNull(obstacle, "obstacle");
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(actualFinal, "actualFinal");
            Objects.requireNonNull(bodyPrefix, "bodyPrefix");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(tube, "tube");
            Objects.requireNonNull(observationTube, "observationTube");
            Objects.requireNonNull(boundaryInput, "boundaryInput");
            if (!Double.isFinite(sourceParameterScale) || sourceParameterScale < 0 || sourceParameterScale > 1)
                throw new IllegalArgumentException("invalid observation-to-source parameter scale");
        }
        boolean limitsAt(double fraction) {
            return boundary.filter(b -> b.kind() == BoundaryKind.BLOCKED_AT_START && fraction == 0
                    || b.kind() == BoundaryKind.CERTIFIED_BOUNDARY && b.prefix().isPresent()
                    && b.prefix().orElseThrow().endFraction() * sourceParameterScale == fraction).isPresent()
                    || bodyPrefix.filter(p -> p.endFraction() == fraction
                    && p.installation().storedContact().relation() == Relation.TOUCHING).isPresent();
        }
        Optional<Double> sourcePrefixEndFraction() {
            return boundary.flatMap(value -> value.kind() == BoundaryKind.BLOCKED_AT_START
                    ? Optional.of(0.0)
                    : value.prefix().map(prefix -> prefix.endFraction() * sourceParameterScale));
        }
        Query(int ordinal, Aabb obstacle, BoundaryResult boundary, WorldCollisionCandidates.Snapshot capture,
              CapsulePlacementMath.Translation tube) {
            this(ordinal, obstacle, Optional.of(boundary), Optional.empty(), Optional.empty(), capture, tube, tube, 1);
        }
        Query(int ordinal, Aabb obstacle, BoundaryResult boundary, WorldCollisionCandidates.Snapshot capture,
              CapsulePlacementMath.Translation tube, BoundaryInput boundaryInput) {
            this(ordinal, obstacle, Optional.of(boundary), Optional.empty(), Optional.empty(), capture, tube, tube, 1,
                    boundaryInput);
        }
        Query(int ordinal, Aabb obstacle, BoundaryResult boundary, WorldCollisionCandidates.Snapshot capture,
              CapsulePlacementMath.Translation tube, CapsulePlacementMath.Translation observationTube,
              double sourceParameterScale) {
            this(ordinal, obstacle, Optional.of(boundary), Optional.empty(), Optional.empty(), capture,
                    tube, observationTube, sourceParameterScale);
        }
    }
    /** A certified movement prefix stopped because no further resolver proposal was proved. */
    record Stop(String reason, Query limitingQuery, boolean fullyBlocked, boolean conservativeCut) {
        Stop(String reason, Query limitingQuery, boolean fullyBlocked) {
            this(reason, limitingQuery, fullyBlocked, false);
        }
        Stop {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(limitingQuery, "limitingQuery");
            if (fullyBlocked && conservativeCut)
                throw new IllegalArgumentException("a conservative prefix is not a proven blocked contact");
        }
    }
    record Plane(int ordinal, Aabb obstacle, CapsuleTranslationPlaneProof.Certificate certificate) { }
    private record SupportCandidate(int ordinal, Aabb obstacle, FreeMovementExactGeometry.Support support) { }
    /** A real limiting sweep query tied to its segment, exact near-gap support, and shared numeric work. */
    static final class SweepFacet {
        enum Kind { SAFE_GAP_SWEEP, ACTUAL_TOUCH }
        private final FreeOwnerTransaction transaction;
        private final Segment segment;
        private final Query limiter;
        private final Kind kind;
        private final FreeMovementExactGeometry.Arithmetic arithmetic;
        private final FreeMovementExactGeometry.Support sourceSupport;
        private final FreeMovementExactGeometry.Support responseSupport;
        private final FreeMovementExactGeometry.Vector originalDirection;
        private final ClipEvent touchEvent;
        private final FreeMovementExactGeometry.Rational expansion;
        private final FreeMovementExactGeometry.Rational radiusIncrement;
        private final FreeMovementExactGeometry.Rational cutDeviation;
        private final FreeMovementExactGeometry.Rational bracketTravel;
        private final double sourceFraction;
        private final double bracketLower;
        private final double bracketUpper;
        private final double sourceScale;
        private volatile ImmutableGeometryReceipt immutableGeometryReceipt;
        private record ImmutableGeometryReceipt(FreeOwnerTransaction transaction, StoredDyadic.Work work,
                List<Segment> segments, List<Query> queries, List<ClipEvent> clipEvents,
                CapsulePlacementMath.Pose finalPose) {
            private boolean matches(SweepFacet facet, FreeOwnerTransaction exactTransaction,
                    List<Segment> exactSegments, List<Query> exactQueries,
                    List<ClipEvent> exactClipEvents, CapsulePlacementMath.Pose exactFinalPose) {
                return transaction == exactTransaction && transaction == facet.transaction
                        && work == facet.arithmetic.workIdentity()
                        && segments == exactSegments && queries == exactQueries
                        && clipEvents == exactClipEvents && finalPose == exactFinalPose
                        && exactSegments.stream().anyMatch(value -> value == facet.segment)
                        && exactQueries.stream().anyMatch(value -> value == facet.limiter)
                        && (facet.kind != Kind.ACTUAL_TOUCH
                                || exactClipEvents.stream().anyMatch(value -> value == facet.touchEvent));
            }
        }
        private SweepFacet(FreeOwnerTransaction transaction, Segment segment, Query limiter, Kind kind,
                FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Support support,
                FreeMovementExactGeometry.Rational expansion,
                FreeMovementExactGeometry.Vector originalDirection, ClipEvent touchEvent) {
            this(transaction, segment, limiter, kind, arithmetic, support, support, expansion,
                    arithmetic.exact(0), arithmetic.exact(0), arithmetic.exact(0), 0, 0, 0, 1,
                    originalDirection, touchEvent);
        }
        private SweepFacet(FreeOwnerTransaction transaction, Segment segment, Query limiter, Kind kind,
                FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Support sourceSupport,
                FreeMovementExactGeometry.Support responseSupport, FreeMovementExactGeometry.Rational expansion,
                FreeMovementExactGeometry.Rational radiusIncrement, FreeMovementExactGeometry.Rational cutDeviation,
                FreeMovementExactGeometry.Rational bracketTravel, double sourceFraction,
                double bracketLower, double bracketUpper, double sourceScale,
                FreeMovementExactGeometry.Vector originalDirection, ClipEvent touchEvent) {
            this.transaction = transaction; this.segment = segment; this.limiter = limiter; this.kind = kind;
            this.arithmetic = arithmetic; this.sourceSupport = sourceSupport; this.responseSupport = responseSupport;
            this.originalDirection = originalDirection; this.touchEvent = touchEvent;
            this.expansion = expansion; this.radiusIncrement = radiusIncrement; this.cutDeviation = cutDeviation;
            this.bracketTravel = bracketTravel; this.sourceFraction = sourceFraction;
            this.bracketLower = bracketLower; this.bracketUpper = bracketUpper; this.sourceScale = sourceScale;
        }
        Segment segment() { return segment; }
        Query limiter() { return limiter; }
        Kind kind() { return kind; }
        FreeMovementExactGeometry.Facet facet() { return responseSupport.facet(); }
        FreeMovementExactGeometry.Support support() { return responseSupport; }
        FreeMovementExactGeometry.Arithmetic arithmetic() { return arithmetic; }
        FreeMovementExactGeometry.Rational expansion() { return expansion; }
        Optional<SweepFacet> atFinalPose(FreeOwnerTransaction exactTransaction, CapsulePlacementMath.Pose finalPose) {
            if (transaction != exactTransaction) return Optional.empty();
            transaction.requireCurrent();
            var finalSupport = FreeMovementExactGeometry.support(arithmetic, limiter.ordinal(),
                    finalPose.capsule().centerline(), limiter.obstacle(), finalPose.capsule().radius());
            if (finalSupport.isEmpty())
                throw new IllegalStateException("sweep facet has no exact final support witness for geometric retirement");
            FreeMovementExactGeometry.Support exactFinal = finalSupport.orElseThrow();
            // Retirement is permitted only by an exact final-geometry reason: actual contact is
            // gone, the certified safe-gap envelope is outside its exact radius, or the current
            // support normal no longer opposes that facet's own unprojected query direction.
            if (kind == Kind.SAFE_GAP_SWEEP && !matchesOriginalDirection())
                throw new IllegalStateException("safe-gap facet lost its exact original-query direction identity");
            boolean stillApplicable = kind == Kind.ACTUAL_TOUCH ? exactFinal.exactlyTouching(arithmetic)
                    : exactFinal.near(arithmetic, expansion) && inwardForOriginalDirection(exactFinal);
            if (!stillApplicable) return Optional.empty();
            return Optional.of(new SweepFacet(transaction, segment, limiter, kind, arithmetic, sourceSupport,
                    exactFinal, expansion, radiusIncrement, cutDeviation, bracketTravel,
                    sourceFraction, bracketLower, bracketUpper, sourceScale, originalDirection, touchEvent));
        }
        boolean matches(FreeOwnerTransaction exactTransaction, List<Segment> exactSegments,
                List<Query> exactQueries, List<ClipEvent> exactClipEvents, CapsulePlacementMath.Pose finalPose) {
            ImmutableGeometryReceipt cached = immutableGeometryReceipt;
            if (cached != null && cached.matches(this, exactTransaction, exactSegments, exactQueries,
                    exactClipEvents, finalPose)) return true;
            if (!matchesUncached(exactTransaction, exactSegments, exactQueries, exactClipEvents, finalPose))
                return false;
            immutableGeometryReceipt = new ImmutableGeometryReceipt(exactTransaction, arithmetic.workIdentity(),
                    exactSegments, exactQueries, exactClipEvents, finalPose);
            return true;
        }
        private boolean matchesUncached(FreeOwnerTransaction exactTransaction, List<Segment> exactSegments,
                List<Query> exactQueries, List<ClipEvent> exactClipEvents, CapsulePlacementMath.Pose finalPose) {
            boolean identity = transaction == exactTransaction && exactSegments.stream().anyMatch(value -> value == segment)
                    && exactQueries.stream().anyMatch(value -> value == limiter)
                    && limiter.capture() == segment.capture() && limiter.tube() == segment.tube()
                    && limiter.obstacle() == facet().obstacle() && limiter.ordinal() == facet().ordinal()
                    && facet().ordinal() >= 0 && facet().ordinal() < segment.capture().boxes().size()
                    && segment.capture().boxes().get(facet().ordinal()) == facet().obstacle();
            if (!identity) return false;
            if (kind == Kind.ACTUAL_TOUCH) {
                return touchEvent != null && exactClipEvents.stream().anyMatch(value -> value == touchEvent)
                        && touchEvent.limiting() == limiter && touchEvent.segment() == segment
                        && touchEvent.actualTouchEvidenceValid()
                        && sourceSupport.exactlyTouching(arithmetic)
                        && sameSupport(responseSupport, finalPose)
                        && responseSupport.exactlyTouching(arithmetic);
            }
            if (originalDirection == null || !matchesOriginalDirection()
                    || !inwardForOriginalDirection(sourceSupport)
                    || !inwardForOriginalDirection(responseSupport)) return false;
            BoundaryResult boundary = limiter.boundary().orElse(null);
            if (boundary == null || boundary.kind() != BoundaryKind.CERTIFIED_BOUNDARY
                    && boundary.kind() != BoundaryKind.BLOCKED_AT_START) return false;
            if (limiter.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION) {
                if (limiter.observationTube() != limiter.tube() || limiter.sourceParameterScale() != 1
                        || sourceScale != 1) return false;
                if (boundary.kind() == BoundaryKind.BLOCKED_AT_START) {
                    if (segment.fraction() != 0 || sourceFraction != 0 || bracketLower != 0 || bracketUpper != 0
                            || arithmetic.sign(expansion) != 0 || arithmetic.sign(radiusIncrement) != 0
                            || arithmetic.sign(cutDeviation) != 0 || arithmetic.sign(bracketTravel) != 0
                            || boundary.installation().isEmpty()
                            || boundary.installation().orElseThrow().fraction() != 0
                            || boundary.installation().orElseThrow().storedCapsule() != limiter.tube().start().capsule()
                            || boundary.startRelation() != Relation.TOUCHING
                            || !supportMatchesCapsule(sourceSupport, limiter.tube().start().capsule())
                            || !sourceSupport.exactlyTouching(arithmetic)) return false;
                } else if (boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY) {
                    var prefix = boundary.prefix().orElse(null);
                    var bracket = boundary.firstObstructionBracket().orElse(null);
                    var installed = boundary.installation().orElse(null);
                    if (prefix == null || bracket == null || installed == null
                            || !safePrefix(prefix)
                            || Double.doubleToRawLongBits(prefix.endFraction()) != Double.doubleToRawLongBits(sourceFraction)
                            || Double.doubleToRawLongBits(bracket.lower()) != Double.doubleToRawLongBits(sourceFraction)
                            || Double.doubleToRawLongBits(bracket.lower()) != Double.doubleToRawLongBits(bracketLower)
                            || Double.doubleToRawLongBits(bracket.upper()) != Double.doubleToRawLongBits(bracketUpper)
                            || !installationMatchesSource(transaction, limiter, installed, sourceFraction)
                            || !supportMatchesCapsule(sourceSupport, segment.end().capsule())
                            || !FreeMovementExactGeometry.bracketTravelWithinAccuracy(arithmetic,
                                    limiter.tube().delta(), bracket.lower(), bracket.upper(), 1,
                                    FreeOwnerTransaction.ACCURACY.maxPathWidthMeters())) return false;
                    var expectedEta = FreeMovementExactGeometry.cutDeviationUpper(arithmetic,
                            limiter.tube().start().capsule().centerline(), segment.end().capsule().centerline(),
                            limiter.tube().delta(), sourceFraction, 1);
                    var expectedW = FreeMovementExactGeometry.bracketTravelUpper(arithmetic, limiter.tube().delta(),
                            bracket.lower(), bracket.upper(), 1);
                    if (arithmetic.compare(arithmetic.exact(0), radiusIncrement) != 0
                            || arithmetic.compare(expectedEta, cutDeviation) != 0
                            || arithmetic.compare(expectedW, bracketTravel) != 0
                            || arithmetic.compare(arithmetic.add(expectedEta, expectedW), expansion) != 0) return false;
                } else return false;
            } else {
                if (boundary.kind() != BoundaryKind.CERTIFIED_BOUNDARY || limiter.observationTube() != limiter.tube()
                        || limiter.sourceParameterScale() != 1 || sourceScale != 1
                        || Double.doubleToRawLongBits(sourceFraction) != Double.doubleToRawLongBits(segment.fraction()))
                    return false;
                var prefix = boundary.prefix().orElse(null);
                var bracket = boundary.firstObstructionBracket().orElse(null);
                var installed = boundary.installation().orElse(null);
                if (limiter.boundaryInput() != BoundaryInput.PADDED_TRANSLATION || prefix == null || bracket == null || installed == null
                        || Double.doubleToRawLongBits(prefix.endFraction()) != Double.doubleToRawLongBits(sourceFraction)
                        || Double.doubleToRawLongBits(bracket.lower()) != Double.doubleToRawLongBits(sourceFraction)
                        || Double.doubleToRawLongBits(bracket.lower()) != Double.doubleToRawLongBits(bracketLower)
                        || Double.doubleToRawLongBits(bracket.upper()) != Double.doubleToRawLongBits(bracketUpper)
                        || !installationMatchesSource(transaction, limiter, installed, sourceFraction)
                        || !supportMatchesCapsule(sourceSupport, segment.end().capsule())) return false;
                if (!FreeMovementExactGeometry.bracketTravelWithinAccuracy(arithmetic, limiter.tube().delta(),
                        bracket.lower(), bracket.upper(), limiter.sourceParameterScale(),
                        FreeOwnerTransaction.ACCURACY.maxPathWidthMeters())) return false;
                var rho = FreeMovementExactGeometry.radiusIncrementUpper(arithmetic,
                        limiter.tube().start().capsule().radius(), limiter.observationTube().queryCapsule().radius());
                var eta = FreeMovementExactGeometry.cutDeviationUpper(arithmetic,
                        limiter.tube().start().capsule().centerline(), segment.end().capsule().centerline(),
                        limiter.tube().delta(), sourceFraction, limiter.sourceParameterScale());
                var w = FreeMovementExactGeometry.bracketTravelUpper(arithmetic, limiter.tube().delta(),
                        bracket.lower(), bracket.upper(), limiter.sourceParameterScale());
                var expectedExpansion = arithmetic.add(arithmetic.add(rho, eta), w);
                if (arithmetic.compare(rho, radiusIncrement) != 0
                        || arithmetic.compare(eta, cutDeviation) != 0
                        || arithmetic.compare(w, bracketTravel) != 0
                        || arithmetic.compare(expectedExpansion, expansion) != 0) return false;
            }
            return limiter.limitsAt(segment.fraction()) && sourceSupport.supportValid(arithmetic)
                    && sameSupport(responseSupport, finalPose) && responseSupport.near(arithmetic, expansion);
        }
        private boolean inwardForOriginalDirection(FreeMovementExactGeometry.Support support) {
            return originalDirection != null && arithmetic.sign(FreeMovementExactGeometry.dot(
                    arithmetic, support.facet().normal(), originalDirection)) < 0;
        }
        private boolean matchesOriginalDirection() {
            if (kind != Kind.SAFE_GAP_SWEEP || originalDirection == null) return false;
            var queryDirection = FreeMovementExactGeometry.vector(arithmetic, limiter.observationTube().delta());
            return vectorEquals(originalDirection, queryDirection);
        }
        private boolean sameSupport(FreeMovementExactGeometry.Support candidate, CapsulePlacementMath.Pose pose) {
            var actual = FreeMovementExactGeometry.support(arithmetic, limiter.ordinal(), pose.capsule().centerline(),
                    limiter.obstacle(), pose.capsule().radius());
            return actual.isPresent() && supportEquals(candidate, actual.orElseThrow());
        }
        private boolean supportMatchesCapsule(FreeMovementExactGeometry.Support candidate, Capsule capsule) {
            var actual = FreeMovementExactGeometry.support(arithmetic, limiter.ordinal(), capsule.centerline(),
                    limiter.obstacle(), capsule.radius());
            return actual.isPresent() && supportEquals(candidate, actual.orElseThrow());
        }
        private boolean supportEquals(FreeMovementExactGeometry.Support first, FreeMovementExactGeometry.Support second) {
            return first.facet().ordinal() == second.facet().ordinal()
                    && first.facet().obstacle() == second.facet().obstacle()
                    && arithmetic.compare(first.facet().normal().x(), second.facet().normal().x()) == 0
                    && arithmetic.compare(first.facet().normal().y(), second.facet().normal().y()) == 0
                    && arithmetic.compare(first.facet().normal().z(), second.facet().normal().z()) == 0
                    && arithmetic.compare(first.closest().parameter(), second.closest().parameter()) == 0
                    && vectorEquals(first.closest().segmentPoint(), second.closest().segmentPoint())
                    && vectorEquals(first.closest().boxPoint(), second.closest().boxPoint())
                    && vectorEquals(first.closest().normal(), second.closest().normal())
                    && arithmetic.compare(first.closest().distanceSquared(), second.closest().distanceSquared()) == 0
                    && arithmetic.compare(first.supportGap(), second.supportGap()) == 0
                    && arithmetic.compare(first.normalSquared(), second.normalSquared()) == 0
                    && arithmetic.compare(first.radius(), second.radius()) == 0;
        }
        private boolean vectorEquals(FreeMovementExactGeometry.Vector first, FreeMovementExactGeometry.Vector second) {
            return arithmetic.compare(first.x(), second.x()) == 0
                    && arithmetic.compare(first.y(), second.y()) == 0
                    && arithmetic.compare(first.z(), second.z()) == 0;
        }
    }
    private record FacetScan(List<SweepFacet> facets, Optional<String> terminalFailure) {
        private FacetScan {
            facets = List.copyOf(facets);
            Objects.requireNonNull(terminalFailure);
            if (terminalFailure.isPresent() && !facets.isEmpty())
                throw new IllegalArgumentException("facet scan cannot be both terminal and usable");
        }
    }
    record Segment(CapsulePlacementMath.Translation tube, WorldCollisionCandidates.Snapshot capture,
                   double fraction, double pathUpper, CapsulePlacementMath.Pose end) { }
    /** Exact one-coordinate support proof for one captured obstacle and one actual stored phase. */
    static final class SupportReceipt {
        private final FreeOwnerTransaction transaction;
        private final Segment segment;
        private final int ordinal;
        private final Aabb obstacle;
        private final FreeMovementExactGeometry.Support support;
        private SupportReceipt(FreeOwnerTransaction transaction, Segment segment, int ordinal, Aabb obstacle,
                               FreeMovementExactGeometry.Support support) {
            this.transaction = transaction; this.segment = segment; this.ordinal = ordinal;
            this.obstacle = obstacle; this.support = support;
        }
        Segment segment() { return segment; }
        int ordinal() { return ordinal; }
        Aabb obstacle() { return obstacle; }
        FreeMovementExactGeometry.Support support() { return support; }
        boolean matches(FreeOwnerTransaction tx, Segment exactSegment, int exactOrdinal, Aabb exactObstacle) {
            return transaction == tx && segment == exactSegment && ordinal == exactOrdinal
                    && obstacle == exactObstacle && segment.capture().boxes().get(ordinal) == obstacle
                    && support.facet().ordinal() == ordinal && support.facet().obstacle() == obstacle;
        }
    }
    /** Historical evidence minted only at an actual fraction-limiting resolver operation.
     * A separator alone is deliberately insufficient: a safe prefix can remain separated.
     * This receipt grants no installation or native callback authority. */
    private record ActualEndpointTouchReceipt(FreeOwnerTransaction transaction, Segment segment, int ordinal,
            Aabb obstacle, WorldCollisionCandidates.Snapshot capture, Capsule endpointCapsule,
            Query limiter, Query observation, ContactResult contact) {
        private ActualEndpointTouchReceipt {
            Objects.requireNonNull(transaction); Objects.requireNonNull(segment); Objects.requireNonNull(obstacle);
            Objects.requireNonNull(capture); Objects.requireNonNull(endpointCapsule);
            Objects.requireNonNull(limiter); Objects.requireNonNull(observation); Objects.requireNonNull(contact);
            if (contact.relation() != Relation.TOUCHING || segment.end().capsule() != endpointCapsule
                    || limiter.ordinal() != ordinal || limiter.obstacle() != obstacle
                    || limiter.capture() != capture || limiter.tube() != segment.tube()
                    || observation.ordinal() != ordinal || observation.obstacle() != obstacle
                    || observation.capture() != capture || observation.tube() != segment.tube()
                    || observation.actualFinal().orElse(null) != contact
                    || ordinal < 0 || ordinal >= capture.boxes().size() || capture.boxes().get(ordinal) != obstacle)
                throw new IllegalArgumentException("endpoint-touch receipt is detached from its exact stored query");
        }
        boolean matches(FreeOwnerTransaction exactTransaction, Segment exactSegment, Query exactLimiter,
                Query exactObservation) {
            return transaction == exactTransaction && segment == exactSegment && observation == exactObservation
                    && limiter == exactLimiter
                    && endpointCapsule == exactSegment.end().capsule() && observation.capture() == capture
                    && observation.ordinal() == ordinal && observation.obstacle() == obstacle
                    && observation.actualFinal().orElse(null) == contact && contact.relation() == Relation.TOUCHING
                    && ordinal >= 0 && ordinal < capture.boxes().size() && capture.boxes().get(ordinal) == obstacle;
        }
    }
    static final class ClipEvent {
        private final FreeOwnerTransaction transaction;
        private final Segment segment;
        private final Query limiting;
        private final CapsulePlacementMath.Translation stationaryEndpoint;
        private final CapsuleTranslationPlaneProof.Certificate plane;
        private final StoredDyadic lostAxis;
        private final AxisFootProof footProof;
        private final ActualEndpointTouchReceipt endpointTouch;
        private record AxisFootProof(CapsuleFootContacts.QualifiedProducerFoot contact) { }
        private ClipEvent(FreeOwnerTransaction transaction, Segment segment, Query limiting,
                          CapsulePlacementMath.Translation stationaryEndpoint,
                          CapsuleTranslationPlaneProof.Certificate plane, StoredDyadic lostAxis,
                          AxisFootProof footProof, ActualEndpointTouchReceipt endpointTouch) {
            this.transaction = transaction; this.segment = segment; this.limiting = limiting;
            this.stationaryEndpoint = stationaryEndpoint; this.plane = plane; this.lostAxis = lostAxis;
            this.footProof = footProof; this.endpointTouch = endpointTouch;
        }
        Segment segment() { return segment; }
        Query limiting() { return limiting; }
        int axis() { return plane.axis(); }
        int outwardSign() { return plane.outwardSign(); }
        FreeMovementExactGeometry.Vector responseNormal(FreeMovementExactGeometry.Arithmetic arithmetic) {
            var zero = arithmetic.exact(0);
            var outward = arithmetic.exact(outwardSign());
            return axis() == 0 ? new FreeMovementExactGeometry.Vector(outward, zero, zero)
                    : axis() == 1 ? new FreeMovementExactGeometry.Vector(zero, outward, zero)
                    : new FreeMovementExactGeometry.Vector(zero, zero, outward);
        }
        boolean actualTouchAtSegmentEnd(Resolved exactMovement) {
            if (exactMovement == null || transaction != exactMovement.transaction
                    || exactMovement.segments.stream().noneMatch(value -> value == segment)
                    || limiting.capture() != segment.capture() || limiting.tube() != segment.tube()
                    || limiting.ordinal() < 0 || limiting.ordinal() >= segment.capture().boxes().size()
                    || segment.capture().boxes().get(limiting.ordinal()) != limiting.obstacle()) return false;
            return exactMovement.clipEvents.stream().anyMatch(value -> value == this) && actualTouchEvidenceValid();
        }
        boolean actualTouchEvidenceValid() {
            if (stationaryEndpoint.start() != segment.end()
                    || !sameRawVector(stationaryEndpoint.delta(), new Vec3d(0, 0, 0))
                    || !plane.matches(stationaryEndpoint, limiting.obstacle())) return false;
            ElongatedOriginalPrefixReceipt proof = limiting.bodyPrefix().orElse(null);
            boolean originalPrefixTouch = proof != null
                    && limiting.actualFinal().filter(actual -> actual.relation() == Relation.TOUCHING).isPresent()
                    && proof.installation().storedContact().relation() == Relation.TOUCHING
                    && proof.startCapsule() == segment.tube().start().capsule()
                    && proof.originalDelta() == segment.tube().delta() && proof.obstacle() == limiting.obstacle()
                    && proof.endFraction() == segment.fraction()
                    && proof.candidate().actualStoredCapsule() == segment.end().capsule()
                    && proof.candidate().acceptedStoredCenter() == segment.end().placement().center();
            boolean queriedEndpointTouch = endpointTouch != null
                    && endpointTouch.matches(transaction, segment, limiting, endpointTouch.observation());
            return originalPrefixTouch || queriedEndpointTouch;
        }
        private static Optional<ClipEvent> observe(FreeOwnerTransaction tx, Segment segment, Query limiting,
                Query endpointObservation) {
            tx.requireCurrent();
            var work = tx.numeric();
            var pose = segment.end();
            var stationary = CapsulePlacementMath.translationTube(pose, new Vec3d(0, 0, 0), work);
            var separator = CapsuleTranslationPlaneProof.certify(stationary, limiting.obstacle(), work);
            if (separator.isEmpty()) return Optional.empty();
            var plane = separator.orElseThrow();
            int axis = plane.axis(), sign = plane.outwardSign();
            Vec3d requested = segment.tube().delta(), up = pose.placement().bodyUp();
            if (StoredDyadic.of(component(requested, axis), work).signum() * sign >= 0) return Optional.empty();
            var actualAxis = StoredDyadic.of(component(pose.placement().center(), axis), work)
                    .subtract(StoredDyadic.of(component(segment.tube().start().placement().center(), axis), work), work);
            var lost = StoredDyadic.of(component(requested, axis), work).subtract(actualAxis, work);
            if (lost.signum() * sign >= 0) return Optional.empty();
            AxisFootProof footProof = null;
            if (axisFace(pose.placement(), limiting.obstacle(), axis, sign, work)
                    && exactDot(requested, up, work).signum() < 0) {
                var footCenter = pose.placement().storedCapsuleGeometry().negativeEndpoint();
                var sphere = new Capsule(new Segment3d(footCenter, footCenter), pose.capsule().radius());
                var contact = CapsuleFootContacts.queryQualifiedProducerFoot(pose, sphere, limiting.obstacle(), up,
                        FreeOwnerTransaction.ACCURACY, tx.queries());
                if (contact.isPresent() && CapsuleSupportSlope.evaluate(contact.orElseThrow().footContact().witness().orElseThrow(),
                        up, work) == CapsuleSupportSlope.SlopeEvidence.SUFFICIENT)
                    footProof = new AxisFootProof(contact.orElseThrow());
            }
            ActualEndpointTouchReceipt endpointTouch = null;
            if (endpointObservation != null) {
                ContactResult endpointContact = endpointObservation.actualFinal().orElse(null);
                if (endpointContact != null && endpointContact.relation() == Relation.TOUCHING)
                    endpointTouch = new ActualEndpointTouchReceipt(tx, segment, limiting.ordinal(),
                            limiting.obstacle(), segment.capture(), pose.capsule(), limiting,
                            endpointObservation, endpointContact);
            }
            tx.requireCurrent();
            if (limiting.capture().capsuleContextFailure(tx.accepted(),
                    limiting.capture().footprint().requestedBounds()).isPresent())
                throw new IllegalStateException("clip observation capture changed during foot qualification");
            return Optional.of(new ClipEvent(tx, segment, limiting, stationary, plane, lost, footProof, endpointTouch));
        }
        boolean matchesFinal(InstalledFreeEndpoint endpoint, CapsuleSupportObservation.Complete complete,
                             WorldCollisionCandidates.Snapshot capture, CapsuleSupportObservation.Support support) {
            endpoint.requireCurrent();
            var tx = endpoint.transaction();
            if (tx != transaction || footProof == null || !actualTouchEvidenceValid()
                    || lostAxis.signum() * outwardSign() >= 0 || complete.foot().reach() != 0
                    || !complete.supports().contains(support)
                    || !complete.matches(endpoint.owner(), endpoint.pose(), capture, complete.foot().window(),
                            tx.queries(), tx.numeric()) || !limiting.obstacle().equals(support.rawAabb())) return false;
            var previous = segment.end().placement();
            var target = endpoint.pose().placement();
            if (!previous.attitude().equals(target.attitude()) || !previous.shape().equals(target.shape())
                    || !previous.bodyUp().equals(target.bodyUp())
                    || Double.doubleToRawLongBits(previous.capsule().radius()) != Double.doubleToRawLongBits(target.capsule().radius())
                    || !axisFace(target, support.rawAabb(), axis(), outwardSign(), tx.numeric())) return false;
            endpoint.requireCurrent();
            return true;
        }
        private static boolean axisFace(PlayerBodyPlacement placement, Aabb box, int axis, int sign, StoredDyadic.Work work) {
            Vec3d center = placement.storedCapsuleGeometry().negativeEndpoint();
            var coordinate = StoredDyadic.of(component(center, axis), work);
            var radius = StoredDyadic.of(placement.capsule().radius(), work);
            var face = sign > 0 ? coordinate.subtract(radius, work) : coordinate.add(radius, work);
            if (face.compare(StoredDyadic.of(component(sign > 0 ? box.max() : box.min(), axis), work), work) != 0) return false;
            for (int other = 0; other < 3; other++) {
                if (other == axis) continue;
                var value = StoredDyadic.of(component(center, other), work);
                if (value.compare(StoredDyadic.of(component(box.min(), other), work), work) < 0
                        || value.compare(StoredDyadic.of(component(box.max(), other), work), work) > 0) return false;
            }
            return true;
        }
        private static StoredDyadic exactDot(Vec3d a, Vec3d b, StoredDyadic.Work work) {
            StoredDyadic result = StoredDyadic.zero();
            for (int axis = 0; axis < 3; axis++) result = result.add(StoredDyadic.of(component(a, axis), work)
                    .multiply(StoredDyadic.of(component(b, axis), work), work), work);
            return result;
        }
        private static double component(Vec3d value, int axis) {
            return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
        }
    }
    sealed interface Result permits Resolved, Inconclusive { List<Query> queries(); List<Plane> planes(); }
    static final class Resolved implements Result {
        /** Identity-scoped lookup only: every retrieved receipt is still checked on use. */
        private record SegmentEvidence(List<Query> originalQueries, Map<Integer, List<Query>> queries,
                Map<Integer, List<Plane>> planes, Map<Integer, List<SupportReceipt>> supports) {
            private static <T> Map<Integer, List<T>> freeze(Map<Integer, List<T>> buckets) {
                buckets.replaceAll((ordinal, values) -> List.copyOf(values));
                return Map.copyOf(buckets);
            }
            static SegmentEvidence index(Segment segment, List<Query> queries, List<Plane> planes,
                    List<SupportReceipt> supports) {
                var originals = new ArrayList<Query>();
                var queryBuckets = new HashMap<Integer, List<Query>>();
                var planeBuckets = new HashMap<Integer, List<Plane>>();
                var supportBuckets = new HashMap<Integer, List<SupportReceipt>>();
                for (var query : queries) {
                    if (query.tube() != segment.tube()) continue;
                    if (query.bodyPrefix().isPresent()) originals.add(query);
                    if (query.capture() == segment.capture())
                        queryBuckets.computeIfAbsent(query.ordinal(), unused -> new ArrayList<>()).add(query);
                }
                for (var plane : planes)
                    planeBuckets.computeIfAbsent(plane.ordinal(), unused -> new ArrayList<>()).add(plane);
                for (var receipt : supports)
                    if (receipt.segment() == segment)
                        supportBuckets.computeIfAbsent(receipt.ordinal(), unused -> new ArrayList<>()).add(receipt);
                return new SegmentEvidence(List.copyOf(originals), freeze(queryBuckets), freeze(planeBuckets),
                        freeze(supportBuckets));
            }
        }
        private final IdentityHashMap<Segment, SegmentEvidence> evidenceBySegment;
        private final CapsulePlacementMath.Pose end;
        private final double fraction;
        private final List<Query> queries;
        private final List<Plane> planes;
        private final List<SupportReceipt> supportReceipts;
        private final List<SweepFacet> sweepFacets;
        private final FreeOwnerTransaction transaction;
        private final List<Segment> segments;
        private final Set<Integer> blockedAxes;
        private final List<ClipEvent> clipEvents;
        private final CapsulePathSource source;
        private final double priorPathUpper;
        private final Optional<Stop> stop;
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<Segment> segments, Set<Integer> blockedAxes, List<ClipEvent> clipEvents) {
            this(transaction, end, fraction, queries, planes, List.of(), segments, blockedAxes, clipEvents, null, 0);
        }
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<Segment> segments, Set<Integer> blockedAxes,
                         List<ClipEvent> clipEvents, CapsulePathSource source, double priorPathUpper) {
            this(transaction, end, fraction, queries, planes, List.of(), segments, blockedAxes, clipEvents, source,
                    priorPathUpper, Optional.empty());
        }
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<SupportReceipt> supportReceipts,
                         List<Segment> segments, Set<Integer> blockedAxes, List<ClipEvent> clipEvents,
                         CapsulePathSource source, double priorPathUpper) {
            this(transaction, end, fraction, queries, planes, supportReceipts, segments, blockedAxes, clipEvents,
                    source, priorPathUpper, Optional.empty());
        }
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<Segment> segments, Set<Integer> blockedAxes,
                         List<ClipEvent> clipEvents, CapsulePathSource source, double priorPathUpper,
                         Optional<Stop> stop) {
            this(transaction, end, fraction, queries, planes, List.of(), segments, blockedAxes, clipEvents,
                    source, priorPathUpper, stop);
        }
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<SupportReceipt> supportReceipts,
                         List<Segment> segments, Set<Integer> blockedAxes, List<ClipEvent> clipEvents,
                         CapsulePathSource source, double priorPathUpper, Optional<Stop> stop) {
            this(transaction, end, fraction, queries, planes, supportReceipts, segments, blockedAxes, clipEvents,
                    source, priorPathUpper, stop, List.of());
        }
        private Resolved(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose end, double fraction,
                         List<Query> queries, List<Plane> planes, List<SupportReceipt> supportReceipts,
                         List<Segment> segments, Set<Integer> blockedAxes, List<ClipEvent> clipEvents,
                         CapsulePathSource source, double priorPathUpper, Optional<Stop> stop,
                         List<SweepFacet> sweepFacets) {
            this.source = source; this.priorPathUpper = priorPathUpper;
            this.end = end; this.fraction = fraction; this.queries = List.copyOf(queries); this.planes = List.copyOf(planes);
            this.supportReceipts = List.copyOf(supportReceipts);
            this.sweepFacets = List.copyOf(sweepFacets);
            this.transaction = transaction; this.segments = List.copyOf(segments);
            this.blockedAxes = Set.copyOf(blockedAxes);
            this.clipEvents = List.copyOf(clipEvents);
            this.stop = Objects.requireNonNull(stop, "stop");
            evidenceBySegment = new IdentityHashMap<>();
            for (var segment : this.segments)
                evidenceBySegment.put(segment, SegmentEvidence.index(segment, this.queries, this.planes, this.supportReceipts));
        }
        CapsulePlacementMath.Pose end() { return end; }
        double fraction() { return fraction; }
        public List<Query> queries() { return queries; }
        public List<Plane> planes() { return planes; }
        List<Segment> segments() { return segments; }
        List<SupportReceipt> supportReceipts() { return supportReceipts; }
        List<SweepFacet> sweepFacets() { return sweepFacets; }
        Set<Integer> blockedAxes() { return blockedAxes; }
        List<ClipEvent> clipEvents() { return clipEvents; }
        CapsulePathSource source() { return source; }
        Optional<Stop> stop() { return stop; }
        Vec3d requestedDelta() { return segments.getFirst().tube().delta(); }
        boolean belongsTo(FreeOwnerTransaction exact) { return transaction == exact; }
        static final class InstalledEvidence {
            private final Resolved movement;
            private final FreeOwnerTransaction transaction;
            private final FreeOwnerTransaction.PreparedInstallation installation;
            private final PlayerBodyPlacement installedPlacement;
            private final CapsulePlacementMath.Pose endpoint;
            private final List<WorldCollisionCandidates.Snapshot> sourceCaptures;
            private InstalledGeneration installedBaseline;
            private InstalledEvidence(Resolved movement, FreeOwnerTransaction transaction,
                    FreeOwnerTransaction.PreparedInstallation installation,
                    List<WorldCollisionCandidates.Snapshot> sourceCaptures) {
                this.movement = movement; this.transaction = transaction;
                this.installation = installation;
                installedPlacement = movement.end.placement(); endpoint = movement.end;
                this.sourceCaptures = List.copyOf(sourceCaptures);
            }
            boolean matches(FreeOwnerTransaction exactTransaction, PlayerBodyPlacement exactPlacement) {
                if (transaction != exactTransaction || movement.transaction != exactTransaction
                        || installedPlacement != exactPlacement || endpoint != movement.end
                        || exactTransaction.installedPlacement().orElse(null) != exactPlacement
                        || exactPlacement != endpoint.placement()
                        || !exactTransaction.ownsPreparedInstallation(installation)) return false;
                return exactTransaction.installedJournal().map(journal -> journal.placement() == exactPlacement)
                        .orElse(false);
            }
            boolean matches(InstalledFreeEndpoint exactEndpoint) {
                if (exactEndpoint == null || exactEndpoint.transaction() != transaction
                        || exactEndpoint.installationIdentity() != installation
                        || exactEndpoint.owner() != installation.journal()
                        || exactEndpoint.pose() != endpoint
                        || !matches(transaction, exactEndpoint.pose().placement())) return false;
                exactEndpoint.requireCurrent();
                return true;
            }

            void requireBridgeSource(WorldCollisionCandidates.Snapshot source,
                    InstalledFreeEndpoint exactEndpoint, Aabb exactWindow) {
                if (sourceCaptures.stream().noneMatch(value -> value == source)
                        || !source.footprint().requestedBounds().equals(exactWindow)
                        || !matches(exactEndpoint))
                    throw new IllegalStateException("installed transition comparison lacks its exact sealed source and prepared endpoint");
            }

            InstalledGeneration bindInstalled(InstalledFreeEndpoint exactEndpoint) {
                if (!matches(exactEndpoint) || installedBaseline != null)
                    throw new IllegalStateException("sealed movement cannot bind this installed endpoint");
                var captures = new IdentityHashMap<WorldCollisionCandidates.Snapshot,
                        WorldCollisionCandidates.Snapshot>();
                for (var source : sourceCaptures) {
                    exactEndpoint.requireCurrent();
                    Aabb window = source.footprint().requestedBounds();
                    var observed = recaptureInstalledWindow(exactEndpoint, window,
                            "installed movement source window");
                    if (!source.sameInstalledTransition(observed, this, exactEndpoint, window))
                        throw new IllegalStateException("installed source-window bridge changed world collision evidence: "
                                + window);
                    captures.put(source, observed);
                }
                exactEndpoint.requireCurrent();
                installedBaseline = new InstalledGeneration(this, exactEndpoint, captures);
                return installedBaseline;
            }

            InstalledGeneration refreshInstalled(InstalledFreeEndpoint exactEndpoint) {
                if (installedBaseline == null || !installedBaseline.belongsTo(this, exactEndpoint))
                    throw new IllegalStateException("installed movement has no exact installed-world baseline");
                exactEndpoint.requireCurrent();
                var captures = new IdentityHashMap<WorldCollisionCandidates.Snapshot,
                        WorldCollisionCandidates.Snapshot>();
                for (var source : sourceCaptures) {
                    exactEndpoint.requireCurrent();
                    Aabb window = source.footprint().requestedBounds();
                    var observed = recaptureInstalledWindow(exactEndpoint, window,
                            "installed movement refresh window");
                    var baseline = installedBaseline.captureFor(source);
                    if (!baseline.sameObservation(observed))
                        throw new IllegalStateException("installed movement world evidence changed after its baseline: "
                                + window);
                    var context = observed.capsuleContextFailure(exactEndpoint.owner(), window);
                    if (context.isPresent())
                        throw captureFailure(context.orElseThrow(), "installed movement refresh context");
                    captures.put(source, observed);
                }
                exactEndpoint.requireCurrent();
                return new InstalledGeneration(this, exactEndpoint, captures);
            }

            List<FreeMovementExactGeometry.Vector> responseConstraints(InstalledGeneration generation,
                    CapsulePlacementMath.Pose exactPose, WorldCollisionCandidates.Snapshot exactCapture) {
                Objects.requireNonNull(generation); Objects.requireNonNull(exactPose); Objects.requireNonNull(exactCapture);
                generation.requireFor(this);
                if (exactPose != endpoint || movement.segments.isEmpty())
                    throw new IllegalArgumentException("velocity response is detached from the resolved endpoint");
                var exactEndpoint = generation.endpoint();
                exactEndpoint.requireCurrent();
                Aabb exactWindow = exactPose.placement().bounds();
                var context = exactCapture.capsuleContextFailure(exactEndpoint.owner(), exactWindow);
                if (context.isPresent()) throw captureFailure(context.orElseThrow(), "installed response body context");
                var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(), transaction::requireOwnerFence);
                var constraints = new ArrayList<FreeMovementExactGeometry.Vector>();
                for (SweepFacet facet : movement.sweepFacets) {
                    if (!facet.matches(transaction, movement.segments, movement.queries,
                            movement.clipEvents, exactPose))
                        throw new IllegalStateException("velocity response lost a sealed sweep support receipt");
                    WorldCollisionCandidates.Snapshot current = generation.captureFor(facet.limiter().capture());
                    int ordinal = facet.facet().ordinal();
                    if (ordinal < 0 || ordinal >= current.boxes().size()
                            || !current.boxes().get(ordinal).equals(facet.facet().obstacle()))
                        throw new IllegalStateException("installed sweep facet lost its exact current obstacle ordinal");
                    constraints.add(facet.facet().normal());
                }
                for (int ordinal = 0; ordinal < exactCapture.boxes().size(); ordinal++) {
                    Aabb obstacle = exactCapture.boxes().get(ordinal);
                    var support = FreeMovementExactGeometry.support(arithmetic, ordinal, exactPose.capsule().centerline(),
                            obstacle, exactPose.capsule().radius());
                    if (support.isPresent() && support.orElseThrow().exactlyTouching(arithmetic))
                        constraints.add(support.orElseThrow().facet().normal());
                }
                exactEndpoint.requireCurrent();
                return List.copyOf(constraints);
            }

            Vec3d projectResponse(InstalledGeneration generation, List<FreeMovementExactGeometry.Vector> constraints,
                    Vec3d requested, FreeOwnerTransaction.ResponseFence responseFence) {
                Objects.requireNonNull(constraints); Objects.requireNonNull(requested);
                generation.requireFor(this, responseFence);
                var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(),
                        responseFence::requireCurrent);
                return FreeMovementExactGeometry.projectToConeStored(arithmetic, requested, constraints);
            }

            private static WorldCollisionCandidates.Snapshot recaptureInstalledWindow(
                    InstalledFreeEndpoint endpoint, Aabb window, String stage) {
                FreeOwnerTransaction tx = endpoint.transaction();
                var captured = WorldCollisionCandidates.capture(tx, window,
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096),
                        tx.resources().environmentObservationWork());
                endpoint.requireCurrent();
                if (captured instanceof WorldCollisionCandidates.Unavailable unavailable)
                    throw captureFailure(unavailable, stage);
                return (WorldCollisionCandidates.Snapshot) captured;
            }

            private static RuntimeException captureFailure(WorldCollisionCandidates.Unavailable unavailable,
                    String stage) {
                RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                        stage + " unavailable: " + unavailable.reason() + ": " + unavailable.detail()));
                return new IllegalStateException(stage + " unavailable: " + unavailable.reason() + ": "
                        + unavailable.detail(), cause);
            }
        }
        static final class InstalledGeneration {
            private final InstalledEvidence evidence;
            private final InstalledFreeEndpoint endpoint;
            private final IdentityHashMap<WorldCollisionCandidates.Snapshot, WorldCollisionCandidates.Snapshot> captures;
            private InstalledGeneration(InstalledEvidence evidence, InstalledFreeEndpoint endpoint,
                    IdentityHashMap<WorldCollisionCandidates.Snapshot, WorldCollisionCandidates.Snapshot> captures) {
                this.evidence = evidence; this.endpoint = endpoint; this.captures = new IdentityHashMap<>(captures);
            }
            private boolean belongsTo(InstalledEvidence exactEvidence, InstalledFreeEndpoint exactEndpoint) {
                return evidence == exactEvidence && endpoint == exactEndpoint
                        && endpoint.transaction() == exactEvidence.transaction
                        && endpoint.installationIdentity() == exactEvidence.installation;
            }
            void requireFor(InstalledEvidence exactEvidence) {
                if (evidence != exactEvidence || endpoint.transaction() != exactEvidence.transaction
                        || endpoint.installationIdentity() != exactEvidence.installation)
                    throw new IllegalStateException("installed response generation belongs to another movement installation");
                endpoint.transaction().requireOwnerFence();
            }
            void requireFor(InstalledEvidence exactEvidence,
                    FreeOwnerTransaction.ResponseFence responseFence) {
                if (evidence != exactEvidence || endpoint.transaction() != exactEvidence.transaction
                        || endpoint.installationIdentity() != exactEvidence.installation
                        || responseFence == null || !responseFence.belongsTo(endpoint))
                    throw new IllegalStateException("installed response generation lost its sealed response owner");
                responseFence.requireCurrent();
            }
            private InstalledFreeEndpoint endpoint() { return endpoint; }
            WorldCollisionCandidates.Snapshot captureFor(WorldCollisionCandidates.Snapshot source) {
                WorldCollisionCandidates.Snapshot observed = captures.get(source);
                if (observed == null)
                    throw new IllegalStateException("installed generation omitted a sealed source capture");
                return observed;
            }
        }

        InstalledEvidence sealForInstallation(FreeOwnerTransaction exactTransaction,
                FreeOwnerTransaction.PreparedInstallation prepared) {
            if (transaction != exactTransaction || !exactTransaction.isPreInstallationPhase()
                    || !matches(exactTransaction) || prepared == null || prepared.transaction() != exactTransaction
                    || prepared.placement() != end.placement() || prepared.certifiedPose() != end)
                throw new IllegalStateException("Free movement cannot seal its installation response evidence");
            var captures = new IdentityHashMap<WorldCollisionCandidates.Snapshot, Boolean>();
            var sources = new ArrayList<WorldCollisionCandidates.Snapshot>();
            for (Segment segment : segments) {
                if (captures.put(segment.capture(), Boolean.TRUE) != null) continue;
                WorldCollisionCandidates.Snapshot source = segment.capture();
                Aabb window = source.footprint().requestedBounds();
                var context = source.capsuleContextFailure(exactTransaction.accepted(), window);
                if (context.isPresent()) throw InstalledEvidence.captureFailure(context.orElseThrow(),
                        "pre-install movement source context");
                var recaptured = WorldCollisionCandidates.capture(exactTransaction, window,
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096),
                        exactTransaction.resources().environmentObservationWork());
                exactTransaction.requireCurrent();
                if (recaptured instanceof WorldCollisionCandidates.Unavailable unavailable)
                    throw InstalledEvidence.captureFailure(unavailable, "pre-install movement source recapture");
                if (!source.sameObservation((WorldCollisionCandidates.Snapshot) recaptured))
                    throw new IllegalStateException("pre-install movement source recapture changed its sealed evidence: "
                            + window);
                sources.add(source);
            }
            if (!matches(exactTransaction))
                throw new IllegalStateException("Free movement changed while its installation evidence was sealed");
            return new InstalledEvidence(this, exactTransaction, prepared, sources);
        }
        boolean matchesBound(CapsulePathSource exactSource) {
            return source != null && source == exactSource && matches(transaction);
        }
        /** Called only by the root-to-leaf lineage walk after it has checked this exact predecessor. */
        boolean matchesBoundAfterSourceValidation(CapsulePathSource exactSource) {
            return source != null && source == exactSource && matches(transaction, true);
        }
        double pathUpper() {
            double total = 0;
            for (Segment segment : segments) {
                if (segment.pathUpper() != 0) total = total == 0 ? segment.pathUpper()
                        : Math.nextUp(total + segment.pathUpper());
            }
            return total;
        }
        boolean matches(FreeOwnerTransaction exactTransaction) {
            return matches(exactTransaction, false);
        }
        private boolean matches(FreeOwnerTransaction exactTransaction, boolean predecessorValidated) {
            if (transaction != exactTransaction) return false;
            transaction.requireCurrent();
            if (segments.isEmpty() || segments.size() > MAX_SEGMENTS) return false;
            if (stop.isPresent()) {
                Stop selected = stop.orElseThrow();
                Query limiting = selected.limitingQuery();
                if (queries.stream().noneMatch(query -> query == limiting)) return false;
                if (selected.conservativeCut()) {
                    if (!(fraction > 0 && fraction < 1) || !clipEvents.isEmpty() || !sweepFacets.isEmpty()
                            || limiting.bodyPrefix().filter(proof -> proof.endFraction() == fraction
                                && proof.installation().storedContact().relation() == Relation.SEPARATED).isEmpty()
                            || limiting.actualFinal().filter(contact -> contact.relation() == Relation.SEPARATED).isEmpty())
                        return false;
                } else if (!limiting.limitsAt(fraction)) return false;
            }
            CapsulePlacementMath.Pose previous = source == null ? segments.getFirst().tube().start() : source.start();
            if (source == null) {
                if (previous.placement() != transaction.accepted().placement()) return false;
            } else if (source.actualSource() != transaction.accepted() || (!predecessorValidated && !source.valid()) || !source.current()
                    || source.work() != transaction.numeric() || source.queries() != transaction.queries()) return false;
            for (SweepFacet facet : sweepFacets)
                if (!facet.matches(transaction, segments, queries, clipEvents, end)) return false;
            for (Segment segment : segments) {
                var evidence = evidenceBySegment.get(segment);
                if (segment.tube().start() != previous
                        || segment.capture().capsuleContextFailure(transaction.accepted(),
                                segment.capture().footprint().requestedBounds()).isPresent()) return false;
                var originalProofs = new HashSet<Integer>();
                for (Query query : evidence.originalQueries()) {
                    if (query.ordinal() < 0 || query.ordinal() >= segment.capture().boxes().size()
                            || segment.capture().boxes().get(query.ordinal()) != query.obstacle()
                            || !originalProofs.add(query.ordinal())) return false;
                    var proof = query.bodyPrefix().orElseThrow();
                    var candidate = proof.candidate();
                    if (proof.startCapsule() != segment.tube().start().capsule()
                            || proof.originalDelta() != segment.tube().delta()
                            || proof.obstacle() != query.obstacle() || query.capture() != segment.capture()
                            || proof.endFraction() != segment.fraction() || proof.accuracy() != FreeOwnerTransaction.ACCURACY
                            || candidate.acceptedStartCenter() != segment.tube().start().placement().center()
                            || candidate.actualStoredCapsule() != segment.end().capsule()
                            || candidate.acceptedStoredCenter() != segment.end().placement().center()
                            || candidate.acceptedHalfLine() != segment.end().placement().storedCapsuleGeometry().storedHalfLine())
                        return false;
                }
                if (!originalProofs.isEmpty() && originalProofs.size() != segment.capture().boxes().size()) return false;
                // A complete set of original-prefix receipts proves the entire capsule
                // route for this segment. Axis/support/boundary observations retained from
                // the same scan are additional history, not competing path proofs.
                boolean originalPathCertified = !originalProofs.isEmpty()
                        && originalProofs.size() == segment.capture().boxes().size();
                for (SupportReceipt receipt : supportReceipts) {
                    if (receipt.segment() != segment) continue;
                    if (receipt.ordinal() < 0 || receipt.ordinal() >= segment.capture().boxes().size()
                            || !receipt.matches(transaction, segment, receipt.ordinal(), receipt.obstacle())) return false;
                }
                for (int ordinal = 0; ordinal < segment.capture().boxes().size(); ordinal++) {
                    final int exactOrdinal = ordinal;
                    Aabb obstacle = segment.capture().boxes().get(ordinal);
                    var obstacleQueries = evidence.queries().getOrDefault(ordinal, List.of());
                    boolean actualFit = obstacleQueries.stream().anyMatch(query -> query.obstacle() == obstacle
                            && query.actualFinal().isPresent()
                            && query.actualFinal().orElseThrow().relation() != Relation.OVERLAPPING
                            && query.actualFinal().orElseThrow().relation() != Relation.UNRESOLVED);
                    if (!actualFit) return false;
                    boolean pathCertified = originalPathCertified
                            || evidence.planes().getOrDefault(ordinal, List.of()).stream().anyMatch(plane -> plane.ordinal() == exactOrdinal
                            && plane.obstacle() == obstacle && plane.certificate().matches(segment.tube(), obstacle));
                    pathCertified = pathCertified
                            || evidence.supports().getOrDefault(ordinal, List.of()).stream().anyMatch(receipt ->
                                receipt.matches(transaction, segment, exactOrdinal, obstacle))
                            || obstacleQueries.stream().anyMatch(query ->
                            boundaryCoversSegment(transaction, query, segment.capture(), segment.tube(),
                                    segment.fraction(), exactOrdinal, obstacle))
                            || segment.fraction() == 0 && segment.end() == segment.tube().start();
                    if (!pathCertified) return false;
                }
                previous = segment.end();
            }
            if (previous != end) return false;
            transaction.requireReservedPath(totalPath(priorPathUpper, pathUpper(), transaction.numeric()));
            transaction.requireCurrent();
            if (source != null && !source.current()) return false;
            return true;
        }
        Optional<WorldCollisionCandidates.Unavailable> recaptureFailure(FreeOwnerTransaction exactTransaction) {
            if (!matches(exactTransaction)) return Optional.of(changed("movement certificate context changed"));
            for (Segment segment : segments) {
                var observed = WorldCollisionCandidates.capture(transaction,
                        segment.capture().footprint().requestedBounds(), CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096));
                transaction.requireCurrent();
                if (observed instanceof WorldCollisionCandidates.Unavailable unavailable) return Optional.of(unavailable);
                if (!segment.capture().sameObservation((WorldCollisionCandidates.Snapshot) observed))
                    return Optional.of(changed("collision recapture differs from the certified segment"));
            }
            return matches(exactTransaction) ? Optional.empty() : Optional.of(changed("owner changed after collision recapture"));
        }
        private static WorldCollisionCandidates.Unavailable changed(String detail) {
            return new WorldCollisionCandidates.Unavailable(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                    Optional.empty(), detail, Optional.empty());
        }
    }
    record Inconclusive(String reason, int ordinal, List<Query> queries, List<Plane> planes,
                        Optional<RuntimeException> cause, Optional<Query> limitingQuery) implements Result {
        Inconclusive(String reason, int ordinal, List<Query> queries, List<Plane> planes) {
            this(reason, ordinal, queries, planes, Optional.empty(), Optional.empty());
        }
        Inconclusive(String reason, int ordinal, List<Query> queries, List<Plane> planes,
                     Optional<RuntimeException> cause) {
            this(reason, ordinal, queries, planes, cause, Optional.empty());
        }
        Inconclusive(String reason, Query limitingQuery, List<Query> queries, List<Plane> planes) {
            this(reason, limitingQuery.ordinal(), queries, planes, Optional.empty(), Optional.of(limitingQuery));
        }
        Inconclusive(String reason, Query limitingQuery, List<Query> queries, List<Plane> planes,
                     Optional<RuntimeException> cause) {
            this(reason, limitingQuery.ordinal(), queries, planes, cause, Optional.of(limitingQuery));
        }
        Inconclusive {
            Objects.requireNonNull(reason, "reason");
            queries = List.copyOf(queries);
            planes = List.copyOf(planes);
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(limitingQuery, "limitingQuery");
            if (limitingQuery.isPresent()) {
                Query exact = limitingQuery.orElseThrow();
                if (exact.ordinal() != ordinal || queries.stream().noneMatch(query -> query == exact))
                    throw new IllegalArgumentException("inconclusive result lost its exact limiting query identity");
            }
        }
    }

    /** Initial segment plus at most one removal of each world axis. */
    static final int MAX_SEGMENTS = 4;

    private CapsuleMovementResolver() { }

    /** At most three axis removals; each actual segment is certified afresh against every candidate. */
    static Result resolveWithSlide(FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube,
                                  WorldCollisionCandidates.Snapshot capture) {
        return resolveWithSlide(transaction, tube, capture, null, 0, Double.POSITIVE_INFINITY);
    }

    /** Continuation retains the original actual owner and the certified arc/resize source. */
    static Result resolveContinuationWithSlide(FreeOwnerTransaction transaction, CapsulePathSource source,
            CapsulePlacementMath.Translation tube, WorldCollisionCandidates.Snapshot capture, double priorPathUpper) {
        Objects.requireNonNull(source);
        transaction.requireCurrent();
        if (!(transaction.accepted().placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || source.actualSource() != transaction.accepted() || !source.valid() || !source.current()
                || source.start() != tube.start() || source.capture() != capture
                || source.work() != transaction.numeric() || source.queries() != transaction.queries())
            throw new IllegalArgumentException("movement continuation does not match its exact owner lineage");
        return resolveWithSlide(transaction, tube, capture, source, priorPathUpper, Double.POSITIVE_INFINITY);
    }

    /** The movement prefix cannot borrow the conversion's separately reserved correction path.
     * Residual segments retain this exact capture and its original owner context. */
    static Result resolveContinuationWithSlide(FreeOwnerTransaction transaction, CapsulePathSource source,
            CapsulePlacementMath.Translation tube, WorldCollisionCandidates.Snapshot capture,
            double priorPathUpper, double movementAllowance) {
        Objects.requireNonNull(source, "source");
        transaction.requireCurrent();
        if (!Double.isFinite(movementAllowance) || movementAllowance < tube.requestedDisplacementUpper()
                || source.actualSource() != transaction.accepted() || !source.valid() || !source.current()
                || !(transaction.accepted().placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || source.start() != tube.start() || source.capture() != capture
                || source.work() != transaction.numeric() || source.queries() != transaction.queries())
            throw new IllegalArgumentException("bounded movement continuation does not match its owner or allowance");
        return resolveWithSlide(transaction, tube, capture, source, priorPathUpper, movementAllowance);
    }

    /** Only a certified contained-Cardinal root can authorize this mode-conversion continuation. */
    static Result resolveContainedCardinalContinuation(FreeOwnerTransaction transaction, CapsulePathSource source,
            CapsulePlacementMath.Translation tube, WorldCollisionCandidates.Snapshot capture,
            double priorPathUpper, double movementAllowance) {
        Objects.requireNonNull(source, "source");
        transaction.requireCurrent();
        if (!(transaction.accepted().placement().shape() instanceof PlayerBodyShape.Cardinal)
                || !Double.isFinite(movementAllowance) || movementAllowance < tube.requestedDisplacementUpper()
                || source.actualSource() != transaction.accepted() || !source.valid() || !source.current()
                || source.start() != tube.start() || source.capture() != capture
                || source.work() != transaction.numeric() || source.queries() != transaction.queries())
            throw new IllegalArgumentException("contained Cardinal movement continuation does not match its owner or allowance");
        return resolveWithSlide(transaction, tube, capture, source, priorPathUpper, movementAllowance);
    }

    private static Result resolveWithSlide(FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube,
            WorldCollisionCandidates.Snapshot capture, CapsulePathSource source, double priorPathUpper,
            double movementAllowance) {
        transaction.requireReservedPath(totalPath(priorPathUpper, tube.requestedDisplacementUpper(), transaction.numeric()));
        Result first = source == null ? resolve(transaction, tube, capture) : resolveSegment(transaction, tube, capture);
        if (!(first instanceof Resolved current)) return first;
        if (current.fraction() == 1) {
            if (source == null) return current;
            var complete = new Resolved(transaction, current.end(), current.fraction(), current.queries(),
                    current.planes(), current.supportReceipts(), current.segments(), current.blockedAxes(),
                    current.clipEvents(), source, priorPathUpper);
            if (Double.isFinite(movementAllowance) && complete.pathUpper() > movementAllowance)
                return new Inconclusive("complete movement exceeds its separate movement allowance", -1,
                        complete.queries(), complete.planes());
            return complete.matches(transaction) ? complete
                    : new Inconclusive("complete movement lost its source or path identity", -1,
                            complete.queries(), complete.planes());
        }
        if (current.segments().size() != 1)
            throw new IllegalStateException("initial movement resolution unexpectedly returned a multi-segment route");
        Segment initialSegment = current.segments().getFirst();
        if (current.stop().filter(Stop::conservativeCut).isPresent()) {
            // The whole original prefix is proved, but no contact or normal was proved.
            // Publish positive progress without manufacturing a slide/landing response.
            var cut = new Resolved(transaction, current.end(), current.fraction(), current.queries(),
                    current.planes(), current.supportReceipts(), current.segments(), current.blockedAxes(),
                    current.clipEvents(), source, priorPathUpper, current.stop(), List.of());
            if (cut.end().placement().center().equals(initialSegment.tube().start().placement().center()))
                return new Inconclusive("certified conservative prefix has no stored center progress", -1,
                        cut.queries(), cut.planes());
            if (Double.isFinite(movementAllowance) && cut.pathUpper() > movementAllowance)
                return new Inconclusive("certified conservative prefix exceeds movement allowance", -1,
                        cut.queries(), cut.planes());
            return cut.matches(transaction) ? cut
                    : new Inconclusive("certified conservative prefix lost source identity", -1,
                            cut.queries(), cut.planes());
        }
        Query limiting = current.queries().stream().filter(query -> isOriginalLimiter(query, initialSegment)).findFirst().orElse(null);
        if (limiting == null)
            return new Inconclusive("partial movement has no original-domain limiter receipt", -1,
                    current.queries(), current.planes());
        FacetScan facetScan = sweepFacets(transaction, initialSegment, current.queries(),
                current.clipEvents(), current);
        if (facetScan.terminalFailure().isPresent())
            return new Inconclusive(facetScan.terminalFailure().orElseThrow(), limiting,
                    current.queries(), current.planes());
        if (facetScan.facets().isEmpty())
            return stopAtResolvedPrefix(transaction, current, current.queries(), current.planes(), current.segments(),
                    current.supportReceipts(), current.blockedAxes(), current.clipEvents(), source, priorPathUpper,
                    movementAllowance, "limiting observation has no exact near-gap or actual-contact support proof", limiting,
                    List.of());
        List<SweepFacet> facets = facetScan.facets();
        FreeMovementExactGeometry.Arithmetic arithmetic = facets.getFirst().arithmetic();
        var geometryFacets = facets.stream().map(SweepFacet::facet).toList();
        Vec3d residual = tube.delta().multiply(1 - current.fraction());
        var projection = FreeMovementExactGeometry.projectToCone(arithmetic,
                FreeMovementExactGeometry.vector(arithmetic, residual), geometryFacets);
        if (projection.isEmpty())
            return new Inconclusive("finite exact tangent-cone projection has no certified active set",
                    limiting, current.queries(), current.planes());
        var idealTarget = projection.orElseThrow().direction();
        if (arithmetic.sign(idealTarget.x()) == 0 && arithmetic.sign(idealTarget.y()) == 0
                && arithmetic.sign(idealTarget.z()) == 0) {
            var blocked = new Resolved(transaction, current.end(), current.fraction(), current.queries(),
                    current.planes(), current.supportReceipts(), current.segments(), current.blockedAxes(),
                    current.clipEvents(), source, priorPathUpper,
                    Optional.of(new Stop("exact tangent-cone projection of the remaining request is zero", limiting, true)),
                    activeFacetsAt(transaction, current.end(), facets));
            if (Double.isFinite(movementAllowance) && blocked.pathUpper() > movementAllowance)
                return new Inconclusive("fully blocked prefix exceeds its separate movement allowance",
                        limiting, current.queries(), current.planes());
            return blocked.matches(transaction) ? blocked
                    : new Inconclusive("fully blocked tangent-cone result lost its limiter identity",
                            limiting, current.queries(), current.planes());
        }
        double reservedUsed = totalPath(priorPathUpper, current.pathUpper(), transaction.numeric());
        double reservedRemaining = transaction.remainingPathGrant(reservedUsed);
        double movementRemaining = Double.POSITIVE_INFINITY;
        if (Double.isFinite(movementAllowance)) {
            if (current.pathUpper() > movementAllowance)
                return new Inconclusive("resolved prefix already exceeds its separate movement allowance",
                        limiting, current.queries(), current.planes());
            double rawRemaining = movementAllowance - current.pathUpper();
            movementRemaining = rawRemaining == 0 ? 0 : Math.max(0, Math.nextDown(rawRemaining));
        }
        double remainingTangentPath = Math.min(reservedRemaining, movementRemaining);
        if (remainingTangentPath <= 0) {
            String reason = reservedRemaining <= 0 && movementRemaining <= 0
                    ? "remaining reserved path grant and separate movement allowance leave no tangent path"
                    : reservedRemaining <= 0 ? "remaining reserved path grant leaves no tangent path"
                    : "separate movement allowance leaves no tangent path";
            return new Inconclusive(reason, limiting, current.queries(), current.planes());
        }
        List<FreeMovementExactGeometry.Vector> scaledTargets = scaledTangentTargets(
                arithmetic, idealTarget, remainingTangentPath);
        var candidates = new ArrayList<Vec3d>();
        for (var scaledTarget : scaledTargets) {
            for (Vec3d candidate : roundedConeCandidates(arithmetic, scaledTarget, geometryFacets)) {
                if (candidates.stream().noneMatch(existing -> sameRawVector(existing, candidate)))
                    candidates.add(candidate);
            }
        }
        PlannedRoute selected = null;
        boolean[] allowanceRejected = { false };
        boolean[] reservedRejected = { false };
        for (Vec3d candidate : candidates) {
            selected = planAxisRoute(transaction, current.end(), candidate, capture, arithmetic,
                    idealTarget, priorPathUpper, current.pathUpper(), movementAllowance,
                    allowanceRejected, reservedRejected);
            if (selected != null) break;
        }
        if (selected == null && (allowanceRejected[0] || reservedRejected[0])) {
            String reason = allowanceRejected[0] && reservedRejected[0]
                    ? "finite tangent route proposals exceeded the remaining reserved path grant or separate movement allowance"
                    : reservedRejected[0]
                            ? "finite tangent route proposals exceeded the remaining reserved path grant"
                            : "finite tangent route proposals exceeded the separate movement allowance";
            return new Inconclusive(reason, limiting, current.queries(), current.planes());
        }
        if (selected == null)
            return stopAtResolvedPrefix(transaction, current, current.queries(), current.planes(), current.segments(),
                    current.supportReceipts(), current.blockedAxes(), current.clipEvents(), source, priorPathUpper,
                    movementAllowance, "finite tangent target has no fully supported single-coordinate route", limiting,
                    facets);

        var queries = new ArrayList<Query>(current.queries());
        var planes = new ArrayList<Plane>(current.planes());
        var supportReceipts = new ArrayList<SupportReceipt>(current.supportReceipts());
        var segments = new ArrayList<Segment>(current.segments());
        var clipEvents = new ArrayList<ClipEvent>(current.clipEvents());
        CapsulePlacementMath.Pose phaseStart = current.end();
        for (PlannedPhase phase : selected.phases()) {
            transaction.requireCurrent();
            CapsulePlacementMath.Translation actualTube = CapsulePlacementMath.translationTube(
                    phaseStart, phase.delta(), transaction.numeric());
            CapsulePlacementMath.Pose actualTarget = CapsulePlacementMath.atPrefix(actualTube, 1, transaction.numeric());
            if (!sameStoredPose(actualTarget, phase.end())
                    || !phaseHasSupportProof(transaction, actualTube, actualTarget, capture))
                return new Inconclusive("selected tangent phase changed its pre-certified stored route",
                        limiting, queries, planes);
            Result resolvedPhase = resolveSegment(transaction, actualTube, capture);
            queries.addAll(resolvedPhase.queries()); planes.addAll(resolvedPhase.planes());
            if (resolvedPhase instanceof Inconclusive failure)
                return new Inconclusive("selected tangent phase failed: " + failure.reason(), failure.ordinal(),
                        queries, planes, failure.cause(), failure.limitingQuery());
            Resolved phaseResult = (Resolved) resolvedPhase;
            if (phaseResult.fraction() != 1 || !sameStoredPose(phaseResult.end(), actualTarget))
                return new Inconclusive("selected support-certified tangent phase did not reach its exact stored target",
                        phaseResult.queries().stream().filter(query -> query.limitsAt(phaseResult.fraction()))
                                .findFirst().orElse(limiting), queries, planes);
            segments.addAll(phaseResult.segments());
            supportReceipts.addAll(phaseResult.supportReceipts());
            clipEvents.addAll(phaseResult.clipEvents());
            phaseStart = phaseResult.end();
        }
        Vec3d expectedRouteCenter = current.end().placement().center().add(selected.requestedNet());
        if (!sameRawVector(phaseStart.placement().center(), expectedRouteCenter)
                || !sameBodyProfile(current.end(), phaseStart))
            return new Inconclusive("executed tangent phases differ from the selected one-shot stored target",
                    limiting, queries, planes);
        var actualNetDelta = FreeMovementExactGeometry.subtract(arithmetic,
                FreeMovementExactGeometry.vector(arithmetic, phaseStart.placement().center()),
                FreeMovementExactGeometry.vector(arithmetic, current.end().placement().center()));
        if (arithmetic.sign(FreeMovementExactGeometry.dot(arithmetic, idealTarget, actualNetDelta)) <= 0)
            return new Inconclusive("executed stored tangent target has no certified ideal-direction progress",
                    limiting, queries, planes);
        var resolved = new Resolved(transaction, phaseStart, current.fraction(), queries,
                planes, supportReceipts, segments, current.blockedAxes(), clipEvents, source, priorPathUpper,
                Optional.empty(), activeFacetsAt(transaction, phaseStart, facets));
        if (Double.isFinite(movementAllowance) && resolved.pathUpper() > movementAllowance)
            return new Inconclusive("tangent route exceeds its separate movement allowance", limiting, queries, planes);
        transaction.requireReservedPath(totalPath(priorPathUpper, resolved.pathUpper(), transaction.numeric()));
        return resolved.matches(transaction) ? resolved
                : new Inconclusive("certified tangent route no longer matches its exact segment evidence", limiting,
                        queries, planes);
    }

    private record PlannedPhase(Vec3d delta, CapsulePlacementMath.Translation tube, CapsulePlacementMath.Pose end) { }
    private record PlannedRoute(List<PlannedPhase> phases, Vec3d requestedNet) {
        private PlannedRoute { phases = List.copyOf(phases); }
    }

    /** Finite proportional headroom candidates keep the exact cone direction while bounding L1 route cost. */
    private static List<FreeMovementExactGeometry.Vector> scaledTangentTargets(
            FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Vector target,
            double remainingPathUpper) {
        if (!Double.isFinite(remainingPathUpper) || remainingPathUpper <= 0)
            throw new IllegalArgumentException("tangent route needs a positive finite remaining path grant");
        var one = arithmetic.exact(1);
        var targetL1 = absolute(arithmetic, target.x());
        targetL1 = arithmetic.add(targetL1, absolute(arithmetic, target.y()));
        targetL1 = arithmetic.add(targetL1, absolute(arithmetic, target.z()));
        if (arithmetic.sign(targetL1) <= 0)
            throw new IllegalArgumentException("nonzero tangent projection has no positive L1 length");
        var available = arithmetic.exact(remainingPathUpper);
        var capacityScale = arithmetic.min(one, arithmetic.divide(available, targetL1));
        var margins = List.of(arithmetic.exact(1), arithmetic.ratio(7, 8), arithmetic.ratio(3, 4),
                arithmetic.ratio(1, 2), arithmetic.ratio(1, 4));
        var result = new ArrayList<FreeMovementExactGeometry.Vector>(margins.size());
        for (var margin : margins) {
            var factor = arithmetic.multiply(capacityScale, margin);
            if (arithmetic.sign(factor) <= 0) continue;
            var scaled = new FreeMovementExactGeometry.Vector(
                    arithmetic.multiply(target.x(), factor), arithmetic.multiply(target.y(), factor),
                    arithmetic.multiply(target.z(), factor));
            if (result.stream().noneMatch(existing -> sameExactVector(arithmetic, existing, scaled)))
                result.add(scaled);
        }
        if (result.isEmpty())
            throw new IllegalStateException("finite proportional tangent candidates underflowed before stored rounding");
        return List.copyOf(result);
    }

    private static FreeMovementExactGeometry.Rational absolute(
            FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Rational value) {
        return arithmetic.sign(value) < 0 ? arithmetic.negate(value) : value;
    }

    private static boolean sameExactVector(FreeMovementExactGeometry.Arithmetic arithmetic,
            FreeMovementExactGeometry.Vector first, FreeMovementExactGeometry.Vector second) {
        return arithmetic.compare(first.x(), second.x()) == 0
                && arithmetic.compare(first.y(), second.y()) == 0
                && arithmetic.compare(first.z(), second.z()) == 0;
    }

    private static FacetScan sweepFacets(FreeOwnerTransaction transaction, Segment segment,
            List<Query> observations, List<ClipEvent> clipEvents, Resolved exactMovement) {
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(), transaction::requireCurrent);
        var result = new ArrayList<SweepFacet>();
        var seenSweep = new HashSet<Integer>();
        var seenTouch = new HashSet<Integer>();
        for (Query query : observations) {
            transaction.requireCurrent();
            if (!isOriginalLimiter(query, segment)) continue;
            BoundaryResult boundary = query.boundary().orElse(null);
            boolean blockedAtStart = boundary != null && boundary.kind() == BoundaryKind.BLOCKED_AT_START
                    && query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION && segment.fraction() == 0;
            boolean certifiedOriginal = boundary != null && boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY
                    && query.observationTube() == query.tube() && query.sourceParameterScale() == 1
                    && boundary.installation().isPresent() && boundary.prefix().isPresent()
                    && boundary.firstObstructionBracket().isPresent()
                    && safePrefix(boundary.prefix().orElseThrow())
                    && Double.doubleToRawLongBits(boundary.prefix().orElseThrow().endFraction())
                            == Double.doubleToRawLongBits(boundary.firstObstructionBracket().orElseThrow().lower())
                    && Double.doubleToRawLongBits(boundary.prefix().orElseThrow().endFraction())
                            == Double.doubleToRawLongBits(segment.fraction());
            if (boundary != null && (blockedAtStart || certifiedOriginal)) {
                double localFraction = blockedAtStart ? 0 : boundary.prefix().orElseThrow().endFraction();
                var installed = boundary.installation().orElse(null);
                var support = FreeMovementExactGeometry.support(arithmetic, query.ordinal(),
                        segment.end().capsule().centerline(), query.obstacle(), segment.end().capsule().radius());
                if (!blockedAtStart && (installed == null
                        || !installationMatchesSource(transaction, query, installed, localFraction)))
                    return new FacetScan(List.of(), Optional.of("certified limiter installation does not bind the selected stored cut"));
                if (support.isPresent() && !seenSweep.contains(query.ordinal())) {
                    var exactSupport = support.orElseThrow();
                    var rho = query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION ? arithmetic.exact(0)
                            : FreeMovementExactGeometry.radiusIncrementUpper(arithmetic,
                                    query.tube().start().capsule().radius(), query.observationTube().queryCapsule().radius());
                    var eta = blockedAtStart ? arithmetic.exact(0)
                            : FreeMovementExactGeometry.cutDeviationUpper(arithmetic,
                                    query.tube().start().capsule().centerline(), segment.end().capsule().centerline(),
                                    query.tube().delta(), localFraction, query.sourceParameterScale());
                    FreeMovementExactGeometry.Rational bracketTravel = arithmetic.exact(0);
                    if (!blockedAtStart) {
                        var bracket = boundary.firstObstructionBracket().orElseThrow();
                        if (!FreeMovementExactGeometry.bracketTravelWithinAccuracy(arithmetic, query.tube().delta(),
                                bracket.lower(), bracket.upper(), query.sourceParameterScale(),
                                FreeOwnerTransaction.ACCURACY.maxPathWidthMeters()))
                            return new FacetScan(List.of(), Optional.of(
                                    "successful source limiter failed the independent exact path-width bound"));
                        bracketTravel = FreeMovementExactGeometry.bracketTravelUpper(arithmetic, query.tube().delta(),
                                bracket.lower(), bracket.upper(), query.sourceParameterScale());
                    }
                    var expansion = arithmetic.add(arithmetic.add(rho, eta), bracketTravel);
                    if (exactSupport.near(arithmetic, expansion)) {
                        double lower = blockedAtStart ? 0 : boundary.firstObstructionBracket().orElseThrow().lower();
                        double upper = blockedAtStart ? 0 : boundary.firstObstructionBracket().orElseThrow().upper();
                        var originalDirection = FreeMovementExactGeometry.vector(arithmetic,
                                query.observationTube().delta());
                        if (arithmetic.sign(FreeMovementExactGeometry.dot(arithmetic,
                                exactSupport.facet().normal(), originalDirection)) < 0) {
                            result.add(new SweepFacet(transaction, segment, query, SweepFacet.Kind.SAFE_GAP_SWEEP,
                                    arithmetic, exactSupport, exactSupport, expansion, rho, eta, bracketTravel,
                                    localFraction, lower, upper, query.sourceParameterScale(), originalDirection, null));
                            seenSweep.add(query.ordinal());
                        }
                    }
                }
            }
            ClipEvent touchEvent = clipEvents.stream().filter(event -> event.segment() == segment
                    && event.limiting() == query && event.actualTouchAtSegmentEnd(exactMovement))
                    .findFirst().orElse(null);
            if (touchEvent != null && !seenTouch.contains(query.ordinal())) {
                var support = FreeMovementExactGeometry.support(arithmetic, query.ordinal(),
                        segment.end().capsule().centerline(), query.obstacle(), segment.end().capsule().radius());
                if (support.filter(value -> value.exactlyTouching(arithmetic)).isPresent()) {
                    result.add(new SweepFacet(transaction, segment, query, SweepFacet.Kind.ACTUAL_TOUCH,
                            arithmetic, support.orElseThrow(), arithmetic.exact(0), null, touchEvent));
                    seenTouch.add(query.ordinal());
                }
            }
        }
        return new FacetScan(result, Optional.empty());
    }

    private static List<SweepFacet> activeFacetsAt(FreeOwnerTransaction transaction,
            CapsulePlacementMath.Pose finalPose, List<SweepFacet> candidates) {
        var active = new ArrayList<SweepFacet>(candidates.size());
        for (SweepFacet facet : candidates) {
            transaction.requireCurrent();
            facet.atFinalPose(transaction, finalPose).ifPresent(active::add);
        }
        return List.copyOf(active);
    }

    private static boolean isOriginalLimiter(Query query, Segment segment) {
        if (query.tube() != segment.tube() || query.capture() != segment.capture()
                || query.observationTube() != query.tube() || query.sourceParameterScale() != 1
                || !query.limitsAt(segment.fraction())) return false;
        BoundaryResult boundary = query.boundary().orElse(null);
        if (query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION)
            return boundary != null && (boundary.kind() == BoundaryKind.BLOCKED_AT_START
                    && segment.fraction() == 0 || boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY
                    && boundary.prefix().isPresent() && boundary.firstObstructionBracket().isPresent()
                    && boundary.installation().isPresent()
                    && safePrefix(boundary.prefix().orElseThrow())
                    && Double.doubleToRawLongBits(boundary.prefix().orElseThrow().endFraction())
                        == Double.doubleToRawLongBits(segment.fraction())
                    && Double.doubleToRawLongBits(boundary.firstObstructionBracket().orElseThrow().lower())
                        == Double.doubleToRawLongBits(segment.fraction()));
        if (boundary != null && boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY
                && boundary.prefix().isPresent() && boundary.firstObstructionBracket().isPresent()
                && boundary.installation().isPresent()) {
            return safePrefix(boundary.prefix().orElseThrow())
                    && Double.doubleToRawLongBits(boundary.prefix().orElseThrow().endFraction())
                            == Double.doubleToRawLongBits(segment.fraction())
                    && Double.doubleToRawLongBits(boundary.firstObstructionBracket().orElseThrow().lower())
                            == Double.doubleToRawLongBits(segment.fraction());
        }
        return query.bodyPrefix().isPresent() && query.actualFinal()
                .filter(actual -> actual.relation() == Relation.TOUCHING).isPresent()
                && query.bodyPrefix().orElseThrow().installation().storedContact().relation() == Relation.TOUCHING;
    }

    private record ScoredCandidate(Vec3d delta, FreeMovementExactGeometry.Rational error) { }

    private static List<Vec3d> roundedConeCandidates(FreeMovementExactGeometry.Arithmetic arithmetic,
            FreeMovementExactGeometry.Vector projected, List<FreeMovementExactGeometry.Facet> facets) {
        var values = new ArrayList<ScoredCandidate>();
        double[] lows = new double[3], highs = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            var component = FreeMovementExactGeometry.component(projected, axis);
            lows[axis] = arithmetic.floor(component);
            highs[axis] = arithmetic.ceil(component);
        }
        for (int mask = 0; mask < 8; mask++) {
            Vec3d candidate = new Vec3d((mask & 1) == 0 ? lows[0] : highs[0],
                    (mask & 2) == 0 ? lows[1] : highs[1], (mask & 4) == 0 ? lows[2] : highs[2]);
            addConeCandidate(arithmetic, projected, facets, candidate, values);
        }
        values.sort((first, second) -> {
            int exact = arithmetic.compare(first.error(), second.error());
            if (exact != 0) return exact;
            int x = Double.compare(first.delta().x(), second.delta().x());
            if (x != 0) return x;
            int y = Double.compare(first.delta().y(), second.delta().y());
            return y != 0 ? y : Double.compare(first.delta().z(), second.delta().z());
        });
        var candidates = new ArrayList<Vec3d>();
        for (ScoredCandidate value : values) candidates.add(value.delta());
        if (!values.isEmpty()) {
            Vec3d primary = values.getFirst().delta();
            for (int axis = 0; axis < 3; axis++) {
                double component = component(primary, axis);
                if (component > 0) addAdjacent(arithmetic, projected, facets, primary, axis, Math.nextUp(component), candidates);
                else if (component < 0) addAdjacent(arithmetic, projected, facets, primary, axis, Math.nextDown(component), candidates);
                else {
                    addAdjacent(arithmetic, projected, facets, primary, axis, Math.nextUp(component), candidates);
                    addAdjacent(arithmetic, projected, facets, primary, axis, Math.nextDown(component), candidates);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static void addAdjacent(FreeMovementExactGeometry.Arithmetic arithmetic,
            FreeMovementExactGeometry.Vector projected, List<FreeMovementExactGeometry.Facet> facets,
            Vec3d source, int axis, double adjacent, List<Vec3d> candidates) {
        if (!Double.isFinite(adjacent)) return;
        Vec3d candidate = new Vec3d(axis == 0 ? adjacent : source.x(), axis == 1 ? adjacent : source.y(),
                axis == 2 ? adjacent : source.z());
        var values = new ArrayList<ScoredCandidate>(1);
        addConeCandidate(arithmetic, projected, facets, candidate, values);
        if (!values.isEmpty() && candidates.stream().noneMatch(existing -> sameRawVector(existing, candidate)))
            candidates.add(candidate);
    }

    private static void addConeCandidate(FreeMovementExactGeometry.Arithmetic arithmetic,
            FreeMovementExactGeometry.Vector projected, List<FreeMovementExactGeometry.Facet> facets,
            Vec3d candidate, List<ScoredCandidate> values) {
        if (candidate.x() == 0 && candidate.y() == 0 && candidate.z() == 0
                || values.stream().anyMatch(existing -> sameRawVector(existing.delta(), candidate))) return;
        var exact = FreeMovementExactGeometry.vector(arithmetic, candidate);
        if (arithmetic.sign(FreeMovementExactGeometry.dot(arithmetic, projected, exact)) <= 0) return;
        for (var facet : facets)
            if (arithmetic.sign(FreeMovementExactGeometry.dot(arithmetic, facet.normal(), exact)) < 0) return;
        var error = FreeMovementExactGeometry.squaredDistance(arithmetic, exact, projected);
        values.add(new ScoredCandidate(candidate, error));
    }

    private static PlannedRoute planAxisRoute(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose start,
            Vec3d requested, WorldCollisionCandidates.Snapshot capture,
            FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Vector idealTarget,
            double priorPathUpper, double alreadyUsedPath, double movementAllowance,
            boolean[] allowanceRejected, boolean[] reservedRejected) {
        if (requested.x() == 0 && requested.y() == 0 && requested.z() == 0) return null;
        var axes = new ArrayList<Integer>(3);
        for (int axis = 0; axis < 3; axis++) if (component(requested, axis) != 0) axes.add(axis);
        var orders = new ArrayList<List<Integer>>();
        permutations(axes, new boolean[axes.size()], new ArrayList<>(), orders);
        for (List<Integer> order : orders) {
            PlannedRoute route = planAxisOrder(transaction, start, requested, capture, arithmetic,
                    idealTarget, order, priorPathUpper, alreadyUsedPath, movementAllowance,
                    allowanceRejected, reservedRejected);
            if (route != null) return route;
        }
        return null;
    }

    private static PlannedRoute planAxisOrder(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose start,
            Vec3d requested, WorldCollisionCandidates.Snapshot capture,
            FreeMovementExactGeometry.Arithmetic arithmetic, FreeMovementExactGeometry.Vector idealTarget,
            List<Integer> axes, double priorPathUpper,
            double alreadyUsedPath, double movementAllowance, boolean[] allowanceRejected,
            boolean[] reservedRejected) {
        var phases = new ArrayList<PlannedPhase>(axes.size());
        CapsulePlacementMath.Pose phaseStart = start;
        double routePathUpper = 0;
        for (int axis : axes) {
            double component = component(requested, axis);
            Vec3d delta = new Vec3d(axis == 0 ? component : 0, axis == 1 ? component : 0,
                    axis == 2 ? component : 0);
            CapsulePlacementMath.Translation phaseTube = CapsulePlacementMath.translationTube(
                    phaseStart, delta, transaction.numeric());
            CapsulePlacementMath.Pose phaseEnd = CapsulePlacementMath.atPrefix(phaseTube, 1, transaction.numeric());
            if (sameRawVector(phaseEnd.placement().center(), phaseStart.placement().center())
                    || !contains(capture.footprint().requestedBounds(), window(transaction.accepted(), phaseTube))) return null;
            routePathUpper = totalPath(routePathUpper, phaseTube.requestedDisplacementUpper(), transaction.numeric());
            phaseStart = phaseEnd;
            phases.add(new PlannedPhase(delta, phaseTube, phaseEnd));
        }
        Vec3d expectedCenter = start.placement().center().add(requested);
        if (!sameRawVector(phaseStart.placement().center(), expectedCenter)) return null;
        var actualNetDelta = FreeMovementExactGeometry.subtract(arithmetic,
                FreeMovementExactGeometry.vector(arithmetic, phaseStart.placement().center()),
                FreeMovementExactGeometry.vector(arithmetic, start.placement().center()));
        if (arithmetic.sign(FreeMovementExactGeometry.dot(arithmetic, idealTarget, actualNetDelta)) <= 0) return null;
        if (!sameBodyProfile(start, phaseStart)) return null;
        double fullPath = totalPath(alreadyUsedPath, routePathUpper, transaction.numeric());
        if (Double.isFinite(movementAllowance) && fullPath > movementAllowance) {
            allowanceRejected[0] = true;
            return null;
        }
        double reservedPath = totalPath(priorPathUpper, fullPath, transaction.numeric());
        if (!transaction.fitsReservedPath(reservedPath)) {
            reservedRejected[0] = true;
            return null;
        }
        // Admission is an arithmetic prefilter. Do not spend the shared collision/support
        // work proving a route that cannot fit either path contract.
        for (PlannedPhase phase : phases) {
            transaction.requireCurrent();
            if (!phaseHasSupportProof(transaction, phase.tube(), phase.end(), capture)) return null;
        }
        transaction.requireReservedPath(reservedPath);
        return phases.isEmpty() ? null : new PlannedRoute(phases, requested);
    }

    private static void permutations(List<Integer> source, boolean[] used, List<Integer> prefix,
            List<List<Integer>> results) {
        if (prefix.size() == source.size()) {
            results.add(List.copyOf(prefix));
            return;
        }
        for (int index = 0; index < source.size(); index++) {
            if (used[index]) continue;
            used[index] = true;
            prefix.add(source.get(index));
            permutations(source, used, prefix, results);
            prefix.removeLast();
            used[index] = false;
        }
    }

    private static boolean phaseHasSupportProof(FreeOwnerTransaction transaction,
            CapsulePlacementMath.Translation tube, CapsulePlacementMath.Pose end,
            WorldCollisionCandidates.Snapshot capture) {
        if (!contains(capture.footprint().requestedBounds(), window(transaction.accepted(), tube))
                || !contains(capture.footprint().requestedBounds(), end.placement().bounds())) return false;
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            Aabb obstacle = capture.boxes().get(ordinal);
            boolean axisProof = CapsuleTranslationPlaneProof.certify(tube, obstacle,
                    transaction.numeric()).isPresent();
            if (!axisProof && monotoneSupport(transaction, tube, ordinal, obstacle, end).isEmpty()) return false;
        }
        return true;
    }

    private static boolean sameBodyProfile(CapsulePlacementMath.Pose first, CapsulePlacementMath.Pose second) {
        var a = first.placement(); var b = second.placement();
        return a.shape().equals(b.shape()) && StoredRealizationLimits.sameRaw(a.attitude(), b.attitude())
                && StoredRealizationLimits.sameRaw(a.storedCapsuleGeometry().storedHalfLine(),
                        b.storedCapsuleGeometry().storedHalfLine())
                && StoredRealizationLimits.sameRaw(first.capsule().radius(), second.capsule().radius());
    }

    private static boolean sameStoredPose(CapsulePlacementMath.Pose first, CapsulePlacementMath.Pose second) {
        var a = first.placement(); var b = second.placement();
        return sameBodyProfile(first, second) && sameRawVector(a.center(), b.center())
                && sameRawVector(a.transport(), b.transport())
                && sameRawVector(a.bounds().min(), b.bounds().min())
                && sameRawVector(a.bounds().max(), b.bounds().max())
                && sameRawVector(first.capsule().centerline().start(), second.capsule().centerline().start())
                && sameRawVector(first.capsule().centerline().end(), second.capsule().centerline().end());
    }

    private static boolean sameRawVector(Vec3d first, Vec3d second) {
        return StoredRealizationLimits.sameRaw(first, second);
    }

    private static boolean safePrefix(
            dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixReceipt prefix) {
        return prefix.kind() == dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind.CLEAR_SEPARATED
                || prefix.kind() == dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind.NONPENETRATING;
    }

    /** CHB's installed receipt may be at any certified point at or before the prefix lower bound. */
    private static boolean installationMatchesSource(FreeOwnerTransaction transaction, Query query,
            InstalledPoseReceipt installed, double sourceLower) {
        double installedFraction = installed.fraction();
        if (!Double.isFinite(installedFraction) || installedFraction < 0 || installedFraction > sourceLower)
            return false;
        CapsulePlacementMath.Translation source = query.observationTube();
        CapsulePlacementMath.Pose installedPose = CapsulePlacementMath.atPrefix(source, installedFraction,
                transaction.numeric());
        double queryRadius = query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION
                ? source.start().capsule().radius() : source.queryCapsule().radius();
        return sameRawVector(installed.storedCenter(), installedPose.placement().center())
                && sameRawVector(installed.halfLine(),
                        installedPose.placement().storedCapsuleGeometry().storedHalfLine())
                && StoredRealizationLimits.sameRaw(installed.storedCapsule().radius(), queryRadius)
                && sameRawVector(installed.storedCapsule().centerline().start(),
                        installedPose.capsule().centerline().start())
                && sameRawVector(installed.storedCapsule().centerline().end(),
                        installedPose.capsule().centerline().end());
    }

    private static boolean boundaryCoversSegment(FreeOwnerTransaction transaction, Query query,
            WorldCollisionCandidates.Snapshot capture, CapsulePlacementMath.Translation tube,
            double sourceFraction, int ordinal, Aabb obstacle) {
        if (query.tube() != tube || query.capture() != capture || query.ordinal() != ordinal
                || query.obstacle() != obstacle || query.observationTube() == null
                || !Double.isFinite(query.sourceParameterScale()) || query.sourceParameterScale() < 0
                || query.sourceParameterScale() > 1) return false;
        // An unpadded boundary proves CHB's ideal translation only. Nonzero stored-path
        // inheritance requires the separate all-box OriginalPrefixReceipt path below.
        if (query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION && sourceFraction > 0) return false;
        BoundaryResult boundary = query.boundary().orElse(null);
        if (boundary == null) return false;
        if (boundary.kind() == BoundaryKind.BLOCKED_AT_START) {
            InstalledPoseReceipt installed = boundary.installation().orElse(null);
            return sourceFraction == 0 && query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION
                    && boundary.startRelation() == Relation.TOUCHING && installed != null
                    && installationMatchesSource(transaction, query, installed, 0);
        }
        var prefix = boundary.prefix().orElse(null);
        var installed = boundary.installation().orElse(null);
        if (prefix == null || installed == null
                || !installationMatchesSource(transaction, query, installed, prefix.endFraction())) return false;
        double certifiedSourceFraction = prefix.endFraction() * query.sourceParameterScale();
        if (!Double.isFinite(certifiedSourceFraction) || certifiedSourceFraction < sourceFraction) return false;
        if (boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY) {
            var bracket = boundary.firstObstructionBracket().orElse(null);
            return safePrefix(prefix)
                    && bracket != null
                    && Double.doubleToRawLongBits(bracket.lower())
                            == Double.doubleToRawLongBits(prefix.endFraction());
        }
        if (boundary.kind() == BoundaryKind.SEPARATED_FULL)
            return prefix.kind()
                    == dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind.CLEAR_SEPARATED
                    && Double.doubleToRawLongBits(prefix.endFraction()) == Double.doubleToRawLongBits(1.0);
        return boundary.kind() == BoundaryKind.NONPENETRATING_FULL
                && prefix.kind() == dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind.NONPENETRATING
                && Double.doubleToRawLongBits(prefix.endFraction()) == Double.doubleToRawLongBits(1.0);
    }

    private static Result stopAtResolvedPrefix(FreeOwnerTransaction transaction, Resolved current,
            List<Query> queries, List<Plane> planes, List<Segment> segments, List<SupportReceipt> supportReceipts,
            Set<Integer> blockedAxes,
            List<ClipEvent> clipEvents, CapsulePathSource source, double priorPathUpper,
            double movementAllowance, String reason, Query limitingQuery) {
        return stopAtResolvedPrefix(transaction, current, queries, planes, segments, supportReceipts, blockedAxes,
                clipEvents, source, priorPathUpper, movementAllowance, reason, limitingQuery, List.of());
    }

    private static Result stopAtResolvedPrefix(FreeOwnerTransaction transaction, Resolved current,
            List<Query> queries, List<Plane> planes, List<Segment> segments, List<SupportReceipt> supportReceipts,
            Set<Integer> blockedAxes, List<ClipEvent> clipEvents, CapsulePathSource source, double priorPathUpper,
            double movementAllowance, String reason, Query limitingQuery, List<SweepFacet> responseFacets) {
        transaction.requireCurrent();
        var prefix = new Resolved(transaction, current.end(), current.fraction(), queries, planes, supportReceipts, segments,
                blockedAxes, clipEvents, source, priorPathUpper,
                Optional.of(new Stop(reason, limitingQuery, false)),
                activeFacetsAt(transaction, current.end(), responseFacets));
        Vec3d initialCenter = segments.getFirst().tube().start().placement().center();
        if (prefix.end().placement().center().equals(initialCenter))
            return new Inconclusive("no center displacement was certified before stop: " + reason,
                    limitingQuery, queries, planes);
        if (Double.isFinite(movementAllowance) && prefix.pathUpper() > movementAllowance)
            return new Inconclusive("resolved movement prefix exceeds its separate movement allowance: " + reason,
                    limitingQuery, queries, planes);
        if (!prefix.matches(transaction))
            return new Inconclusive("resolved movement prefix became invalid before stop: " + reason,
                    limitingQuery, queries, planes);
        return prefix;
    }

    static Result resolve(FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube,
                          WorldCollisionCandidates.Snapshot capture) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(tube); Objects.requireNonNull(capture);
        if (tube.start().placement() != transaction.accepted().placement())
            throw new IllegalArgumentException("translation must start at the transaction's exact accepted placement");
        return resolveSegment(transaction, tube, capture);
    }

    private static Result resolveSegment(FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube,
                                        WorldCollisionCandidates.Snapshot capture) {
        transaction.requireCurrent();
        transaction.requireReservedPath(tube.requestedDisplacementUpper());
        Aabb window = capture.footprint().requestedBounds();
        if (!contains(window, window(transaction.accepted(), tube)))
            return new Inconclusive("capture excludes movement sweep", -1, List.of(), List.of());
        var context = capture.capsuleContextFailure(transaction.accepted(), window);
        if (context.isPresent()) return new Inconclusive("capture context: " + context.get(), -1, List.of(), List.of());
        var evidence = new ArrayList<Query>();
        var planes = new ArrayList<Plane>();
        var supportCandidates = new ArrayList<SupportCandidate>();
        var limitingCandidates = new ArrayList<Query>();
        Query[] endpointObservations = new Query[capture.boxes().size()];
        boolean actualStartInheritanceRequired = false;
        Query missingPrefix = null;
        double fraction = 1;
        var fullEnd = CapsulePlacementMath.atPrefix(tube, 1, transaction.numeric());
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            transaction.requireCurrent();
            Aabb box = capture.boxes().get(ordinal);
            var plane = CapsuleTranslationPlaneProof.certify(tube, box, transaction.numeric());
            if (plane.isPresent()) {
                planes.add(new Plane(ordinal, box, plane.orElseThrow()));
                continue;
            }
            var support = monotoneSupport(transaction, tube, ordinal, box, fullEnd);
            if (support.isPresent()) {
                supportCandidates.add(new SupportCandidate(ordinal, box, support.orElseThrow()));
                continue;
            }
            BoundaryResult first = query(transaction, tube, tube.start(), 0, box);
            Query firstObservation = new Query(ordinal, box, first, capture, tube);
            evidence.add(firstObservation);
            if (first.kind() == BoundaryKind.UNRESOLVED
                    && first.reason().orElseThrow() == Reason.NO_PREFIX_CERTIFICATE) {
                first = query(transaction, tube, fullEnd, 1, box);
                firstObservation = new Query(ordinal, box, first, capture, tube);
                evidence.add(firstObservation);
            }
            if (first.kind() == BoundaryKind.INITIAL_OVERLAP) {
                // Padding can overlap at an exact, still-safe start. Ask CHB once for the
                // actual shape's path domain; its original prefix receipt is not interchangeable
                // with the padded tube's installation receipt.
                var start = tube.start();
                BoundaryResult actualStart = transaction.queries().refineTranslationBoundary(start.capsule(),
                        tube.delta(), box, new BoundarySearchHint(0, 1), new StoredInstallCandidate(0,
                                start.placement().center(), start.placement().center(),
                                start.placement().storedCapsuleGeometry().storedHalfLine(), start.capsule()),
                        FreeOwnerTransaction.ACCURACY);
                Query actualStartObservation = new Query(ordinal, box, actualStart, capture, tube,
                        BoundaryInput.ACTUAL_TRANSLATION);
                evidence.add(actualStartObservation);
                if (actualStart.kind() == BoundaryKind.UNRESOLVED
                        && actualStart.reason().orElse(null) == Reason.NO_PREFIX_CERTIFICATE) {
                    actualStart = transaction.queries().refineTranslationBoundary(start.capsule(), tube.delta(), box,
                            new BoundarySearchHint(0, 1), new StoredInstallCandidate(1,
                                    start.placement().center(), fullEnd.placement().center(),
                                    fullEnd.placement().storedCapsuleGeometry().storedHalfLine(), fullEnd.capsule()),
                            FreeOwnerTransaction.ACCURACY);
                    actualStartObservation = new Query(ordinal, box, actualStart, capture, tube,
                            BoundaryInput.ACTUAL_TRANSLATION);
                    evidence.add(actualStartObservation);
                }
                if (actualStart.kind() == BoundaryKind.BLOCKED_AT_START) {
                    if (actualStart.startRelation() != Relation.TOUCHING)
                        return new Inconclusive("actual-shape start block lacks a touching start receipt",
                                actualStartObservation, evidence, planes);
                    limitingCandidates.add(actualStartObservation);
                    fraction = 0;
                } else if (actualStart.kind() == BoundaryKind.CERTIFIED_BOUNDARY) {
                    var actualPrefix = actualStart.prefix().orElse(null);
                    var actualBracket = actualStart.firstObstructionBracket().orElse(null);
                    var actualInstall = actualStart.installation().orElse(null);
                    if (actualPrefix == null || actualBracket == null || actualInstall == null
                            || !safePrefix(actualPrefix)
                            || Double.doubleToRawLongBits(actualPrefix.endFraction())
                                    != Double.doubleToRawLongBits(actualBracket.lower())
                            || !installationMatchesSource(transaction, actualStartObservation, actualInstall,
                                    actualPrefix.endFraction()))
                        return new Inconclusive("actual-shape boundary lacks a bound nonpenetrating source prefix",
                                actualStartObservation, evidence, planes);
                    limitingCandidates.add(actualStartObservation);
                    fraction = Math.min(fraction, actualPrefix.endFraction());
                    actualStartInheritanceRequired = true;
                } else if (actualStart.kind() == BoundaryKind.SEPARATED_FULL
                        || actualStart.kind() == BoundaryKind.NONPENETRATING_FULL) {
                    limitingCandidates.add(actualStartObservation);
                    actualStartInheritanceRequired = true;
                } else {
                    return new Inconclusive("actual-shape start cannot certify the original path prefix",
                            actualStartObservation, evidence, planes);
                }
                continue;
            }
            if (first.kind() == BoundaryKind.UNRESOLVED
                    && first.reason().orElse(null) == Reason.NO_PREFIX_CERTIFICATE) {
                // A sweep can enter and leave an obstacle between two separated endpoints.
                // Missing a full-sweep boundary is not evidence that any prefix is safe;
                // retain this obstacle for the existing actual-source prefix prover below.
                limitingCandidates.add(firstObservation);
                if (missingPrefix == null) missingPrefix = firstObservation;
                continue;
            }
            if (first.kind() == BoundaryKind.UNRESOLVED || first.prefix().isEmpty())
                return new Inconclusive("boundary did not certify a prefix", firstObservation, evidence, planes);
            limitingCandidates.add(firstObservation);
            fraction = Math.min(fraction, first.prefix().orElseThrow().endFraction());
        }
        if (fraction > 0 && (fraction < 1 || actualStartInheritanceRequired || missingPrefix != null)) {
            Result originalPrefix = resolveOriginalPrefixCandidate(transaction, tube, capture,
                    limitingCandidates, evidence, planes, fraction);
            if (originalPrefix != null) return originalPrefix;
            if (missingPrefix != null)
                return new Inconclusive("missing sweep boundary has no certified actual-source prefix",
                        missingPrefix, evidence, planes);
            if (actualStartInheritanceRequired) {
                Query actualSource = limitingCandidates.stream()
                        .filter(query -> query.boundaryInput() == BoundaryInput.ACTUAL_TRANSLATION)
                        .findFirst().orElse(null);
                String reason = "actual initial overlap has no complete stored-path inheritance receipt";
                return actualSource == null
                        ? new Inconclusive(reason, -1, evidence, planes)
                        : new Inconclusive(reason, actualSource, evidence, planes);
            }
        }
        if (missingPrefix != null)
            return new Inconclusive("missing sweep boundary cannot authorize a zero-prefix installation",
                    missingPrefix, evidence, planes);
        var end = CapsulePlacementMath.atPrefix(tube, fraction, transaction.numeric());
        var commonTube = fraction == 1 ? tube : CapsulePlacementMath.translationTube(tube.start(),
                tube.delta().multiply(fraction), transaction.numeric());
        final double selectedFraction = fraction;
        // Each obstacle must certify the same installed fraction, not merely a different safe endpoint.
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            transaction.requireCurrent();
            Aabb box = capture.boxes().get(ordinal);
            int exactOrdinal = ordinal;
            boolean planeCertified = planes.stream().anyMatch(p -> p.ordinal() == exactOrdinal
                    && p.certificate().matches(tube, box));
            boolean supportCertified = supportCandidates.stream().anyMatch(p -> p.ordinal() == exactOrdinal
                    && p.obstacle() == box);
            BoundaryResult boundary = null;
            boolean sourceBoundaryCertified = evidence.stream().anyMatch(query ->
                    boundaryCoversSegment(transaction, query, capture, tube, selectedFraction, exactOrdinal, box));
            if (!planeCertified && !supportCertified && !sourceBoundaryCertified && fraction != 0) {
                boundary = query(transaction, commonTube, end, 1, box);
                Query observation = new Query(ordinal, box, boundary, capture, tube, commonTube, fraction);
                evidence.add(observation);
                if (boundary.kind() == BoundaryKind.UNRESOLVED || boundary.installation().isEmpty()
                        || boundary.prefix().isEmpty() || boundary.prefix().orElseThrow().endFraction() < 1)
                    return new Inconclusive("common stored prefix not certified", observation, evidence, planes);
            }
            var actual = transaction.queries().query(end.capsule(), box, FreeOwnerTransaction.ACCURACY);
            Query endpointObservation = new Query(ordinal, box, Optional.ofNullable(boundary), Optional.of(actual),
                    Optional.empty(), capture, tube, commonTube, fraction);
            endpointObservations[ordinal] = endpointObservation;
            evidence.add(endpointObservation);
            if (actual.relation() != Relation.SEPARATED && actual.relation() != Relation.TOUCHING)
                return new Inconclusive("actual endpoint fit not resolved", endpointObservation, evidence, planes);
        }
        transaction.requireCurrent();
        if (capture.capsuleContextFailure(transaction.accepted(), window).isPresent())
            return new Inconclusive("capture context changed after queries", -1, evidence, planes);
        var segment = new Segment(tube, capture, fraction, fraction == 0 ? 0 : commonTube.requestedDisplacementUpper(), end);
        var supportReceipts = supportCandidates.stream()
                .map(candidate -> new SupportReceipt(transaction, segment, candidate.ordinal(), candidate.obstacle(), candidate.support()))
                .toList();
        var events = new ArrayList<ClipEvent>();
        if (fraction < 1) {
            for (Query candidate : limitingCandidates) {
                var boundary = candidate.boundary().orElseThrow();
                if (!(fraction == 0 && boundary.kind() == BoundaryKind.BLOCKED_AT_START)
                        && !(boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY && boundary.prefix().isPresent()
                        && boundary.prefix().orElseThrow().endFraction() == fraction)) continue;
                ClipEvent.observe(transaction, segment, candidate, endpointObservations[candidate.ordinal()])
                        .ifPresent(events::add);
            }
        }
        transaction.requireCurrent();
        return new Resolved(transaction, end, fraction, evidence, planes, supportReceipts,
                List.of(segment), Set.of(), events, null, 0);
    }

    private static Optional<FreeMovementExactGeometry.Support> monotoneSupport(
            FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube, int ordinal,
            Aabb obstacle, CapsulePlacementMath.Pose storedEnd) {
        Vec3d delta = tube.delta();
        int changingAxes = (delta.x() == 0 ? 0 : 1) + (delta.y() == 0 ? 0 : 1) + (delta.z() == 0 ? 0 : 1);
        if (changingAxes > 1) return Optional.empty();
        transaction.requireCurrent();
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(), transaction::requireCurrent);
        var startCapsule = tube.start().capsule();
        Optional<FreeMovementExactGeometry.Support> candidate = FreeMovementExactGeometry.support(
                arithmetic, ordinal, startCapsule.centerline(), obstacle, startCapsule.radius());
        if (candidate.isEmpty()) return Optional.empty();
        var support = candidate.orElseThrow();
        var exactDelta = FreeMovementExactGeometry.vector(arithmetic, delta);
        boolean idealPath = FreeMovementExactGeometry.supportsTranslation(arithmetic, support.facet().normal(),
                startCapsule.centerline(), exactDelta, obstacle, startCapsule.radius());
        if (!idealPath || !FreeMovementExactGeometry.supports(arithmetic, support.facet().normal(),
                storedEnd.capsule().centerline(), obstacle, storedEnd.capsule().radius())) return Optional.empty();
        transaction.requireCurrent();
        return Optional.of(support);
    }

    /** Full actual-shape source-prefix proof for a finite cut; only separate real-touch receipts create events. */
    private static Result resolveOriginalPrefixCandidate(FreeOwnerTransaction tx, CapsulePlacementMath.Translation tube,
            WorldCollisionCandidates.Snapshot capture, List<Query> limiting, List<Query> history, List<Plane> planes,
            double maximumFraction) {
        var candidates = new LinkedHashSet<Double>();
        for (Query query : limiting) {
            var boundary = query.boundary().orElseThrow();
            boundary.prefix().ifPresent(prefix -> candidates.add(prefix.endFraction()));
            boundary.firstObstructionBracket().ifPresent(bracket -> {
                candidates.add(bracket.lower()); candidates.add(bracket.upper());
            });
            Vec3d foot = tube.start().placement().storedCapsuleGeometry().negativeEndpoint();
            for (int axis = 0; axis < 3; axis++) {
                double delta = component(tube.delta(), axis);
                if (delta == 0) continue;
                tx.numeric().charge(5);
                double face = component(delta < 0 ? query.obstacle().max() : query.obstacle().min(), axis);
                double radius = tube.start().capsule().radius();
                double target = face + (delta < 0 ? radius : -radius);
                double fraction = (target - component(foot, axis)) / delta;
                if (Double.isFinite(fraction)) candidates.add(fraction);
            }
        }
        for (double value : new ArrayList<>(candidates)) {
            if (Double.isFinite(value) && value > 0 && value <= 1) {
                tx.numeric().charge(1);
                candidates.add(Math.nextDown(value));
            }
        }
        for (double fraction : candidates) {
            tx.postprocessing().charge(1);
            tx.requireCurrent();
            if (!Double.isFinite(fraction) || fraction <= 0 || fraction > maximumFraction || fraction > 1) continue;
            var end = CapsulePlacementMath.atPrefix(tube, fraction, tx.numeric());
            if (!contains(capture.footprint().requestedBounds(), end.placement().bounds())) continue;
            if (!end.placement().attitude().equals(tube.start().placement().attitude())
                    || !end.placement().shape().equals(tube.start().placement().shape())
                    || !StoredRealizationLimits.sameRaw(end.placement().storedCapsuleGeometry().storedHalfLine(),
                            tube.start().placement().storedCapsuleGeometry().storedHalfLine())
                    || !StoredRealizationLimits.sameRaw(end.capsule().radius(), tube.start().capsule().radius()))
                return new Inconclusive("original contact candidate changed its fixed body profile", -1, history, planes);
            var candidate = new StoredInstallCandidate(fraction, tube.start().placement().center(),
                    end.placement().center(), end.placement().storedCapsuleGeometry().storedHalfLine(), end.capsule());
            var proofs = new ArrayList<Query>();
            boolean missing = false;
            for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
                tx.requireCurrent();
                Aabb box = capture.boxes().get(ordinal);
                var result = tx.queries().proveOriginalCapsuleTranslationPrefix(tube.start().capsule(),
                        tube.delta(), fraction, box, candidate, FreeOwnerTransaction.ACCURACY);
                tx.requireCurrent();
                if (capture.capsuleContextFailure(tx.accepted(), capture.footprint().requestedBounds()).isPresent())
                    return contactCandidateFailure("original contact prefix capture changed", ordinal,
                            limiting, history, planes);
                if (result.startRelation() == Relation.UNRESOLVED || result.startRelation() == Relation.OVERLAPPING)
                    return contactCandidateFailure("original contact prefix start unresolved or overlapping: " + result,
                            ordinal, limiting, history, planes);
                if (result.proof().isEmpty()) {
                    Reason reason = result.reason().orElseThrow();
                    if (reason != Reason.NO_PREFIX_CERTIFICATE && reason != Reason.ROUNDED_INSTALL_UNSAFE
                            && reason != Reason.CORRECTION_PATH_UNPROVED)
                        return contactCandidateFailure("original contact prefix unavailable: " + result,
                                ordinal, limiting, history, planes);
                    missing = true;
                    break;
                }
                var proof = result.proof().orElseThrow();
                proofs.add(new Query(ordinal, box, Optional.empty(),
                        Optional.of(proof.installation().storedContact()), Optional.of(proof), capture, tube));
            }
            if (missing) continue;
            var prefixTube = CapsulePlacementMath.translationTube(tube.start(), tube.delta().multiply(fraction), tx.numeric());
            tx.requireReservedPath(prefixTube.requestedDisplacementUpper());
            var segment = new Segment(tube, capture, fraction, prefixTube.requestedDisplacementUpper(), end);
            // A prefix proof can support a safe separated cut. Only a separately touching
            // actual endpoint receipt is allowed to mint the historical landing/axis event.
            var events = new ArrayList<ClipEvent>();
            for (Query proof : proofs) {
                if (proof.actualFinal().filter(actual -> actual.relation() == Relation.TOUCHING).isEmpty()
                        || proof.bodyPrefix().filter(body ->
                                body.installation().storedContact().relation() == Relation.TOUCHING).isEmpty()) continue;
                ClipEvent.observe(tx, segment, proof, null).ifPresent(events::add);
            }
            tx.requireCurrent();
            var evidence = new ArrayList<Query>(history);
            evidence.addAll(proofs);
            if (fraction == 1)
                return new Resolved(tx, end, fraction, evidence, planes, List.of(segment), Set.of(), events);
            if (evidence.stream().noneMatch(query -> isOriginalLimiter(query, segment))) {
                var cut = proofs.stream().filter(query -> query.actualFinal()
                        .filter(actual -> actual.relation() == Relation.SEPARATED).isPresent()).findFirst();
                if (cut.isEmpty())
                    return new Inconclusive("certified prefix has neither contact nor separated cut evidence",
                            -1, evidence, planes);
                return new Resolved(tx, end, fraction, evidence, planes, List.of(), List.of(segment),
                        Set.of(), List.of(), null, 0,
                        Optional.of(new Stop("certified conservative original prefix; no contact normal proved",
                                cut.orElseThrow(), false, true)), List.of());
            }
            return new Resolved(tx, end, fraction, evidence, planes, List.of(segment), Set.of(), events);
        }
        return null;
    }

    private static Inconclusive contactCandidateFailure(String reason, int ordinal, List<Query> limiting,
            List<Query> history, List<Plane> planes) {
        var observation = limiting.stream().filter(query -> query.ordinal() == ordinal).findFirst();
        return observation.isPresent()
                ? new Inconclusive(reason, observation.orElseThrow(), history, planes)
                : new Inconclusive(reason, ordinal, history, planes);
    }

    private static double component(Vec3d vector, int axis) {
        return axis == 0 ? vector.x() : axis == 1 ? vector.y() : vector.z();
    }

    private static double totalPath(double before, double phase, StoredDyadic.Work work) {
        work.charge(3);
        if (!Double.isFinite(before) || before < 0 || !Double.isFinite(phase) || phase < 0)
            throw new IllegalArgumentException("invalid certified movement path cost");
        if (before == 0) return phase;
        if (phase == 0) return before;
        double total = Math.nextUp(before + phase);
        if (!Double.isFinite(total)) throw new IllegalArgumentException("movement path cost overflow");
        return total;
    }
    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y() && outer.min().z() <= inner.min().z()
                && outer.max().x() >= inner.max().x() && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    static Aabb window(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Translation tube) {
        Aabb actual = accepted.placement().bounds(), swept = tube.sweptBounds();
        return new Aabb(new Vec3d(Math.min(actual.min().x(), swept.min().x()),
                Math.min(actual.min().y(), swept.min().y()), Math.min(actual.min().z(), swept.min().z())),
                new Vec3d(Math.max(actual.max().x(), swept.max().x()),
                Math.max(actual.max().y(), swept.max().y()), Math.max(actual.max().z(), swept.max().z())));
    }

    private static BoundaryResult query(FreeOwnerTransaction transaction, CapsulePlacementMath.Translation tube,
            CapsulePlacementMath.Pose end, double fraction, Aabb box) {
        // CHB checks exact radius identity. Its installed receipt refers to the padded capsule;
        // atPrefix independently encloses the actual stored geometry, which is queried afterwards.
        Capsule paddedEnd = new Capsule(end.capsule().centerline(), tube.queryCapsule().radius());
        Vec3d half = end.placement().storedCapsuleGeometry().storedHalfLine();
        return transaction.queries().refineTranslationBoundary(tube.queryCapsule(), tube.delta(), box,
                new BoundarySearchHint(0, 1), new StoredInstallCandidate(fraction,
                        tube.start().placement().center(), end.placement().center(), half, paddedEnd),
                FreeOwnerTransaction.ACCURACY);
    }
}
