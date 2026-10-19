package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

import wi.unigrav.core.player.FreeCapsulePlacementV1.AxisPlane;
import wi.unigrav.core.player.FreeCapsulePlacementV1.Nominal;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawAabb;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawQuaternion;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawVec3;

/** Restores proof-bearing numerical placement only; it grants no path or install authority. */
final class PlayerPlacementCodec {
    private PlayerPlacementCodec() { }

    /**
     * Exports the proof already retained by an accepted placement. In particular,
     * a translated nominal geometry keeps its original center reference and is
     * not reclassified from its current center.
     */
    static FreeCapsulePlacementV1 exportFreeV1(PlayerBodyPlacement placement) {
        Objects.requireNonNull(placement, "placement");
        if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule free)) {
            throw new IllegalArgumentException("FreeCapsule export requires a FreeCapsule placement");
        }
        StoredCapsuleGeometry geometry = placement.storedCapsuleGeometry();
        if (geometry == null || !geometry.matchesRecipe(placement.center(), placement.attitude(), free.geometryDimensions())) {
            throw new IllegalArgumentException("FreeCapsule export requires its accepted stored geometry");
        }
        RealizationRelation relation = geometry.relation();
        FreeCapsulePlacementV1.Proof proof = switch (relation.kind()) {
            case NOMINAL -> new Nominal();
            case CONTAINED_CARDINAL, FIXED_HALF_LENGTH -> throw new IllegalArgumentException(
                    "proposed contained source has no FreeV1 accepted-wire proof");
            case AXIS_PLANE -> new AxisPlane(RawVec3.from(relation.realizedCenter()),
                    relation.modifiedAxis(), relation.outwardSign(),
                    Double.doubleToRawLongBits(relation.supportCoordinate()));
        };
        return new FreeCapsulePlacementV1(RawVec3.from(placement.center()),
                RawVec3.from(geometry.storedHalfLine()),
                RawVec3.from(relation.limits().centerReference()), proof,
                free.eyeAnchor().map(anchor -> new FreeCapsulePlacementV1.BodyProfile(
                        Double.doubleToRawLongBits(free.collisionProfile().diameter()),
                        Double.doubleToRawLongBits(free.collisionProfile().length()), anchor.recipeVersion(),
                        RawVec3.from(anchor.center()), RawVec3.from(anchor.eye()), RawQuaternion.from(anchor.attitude()))));
    }

    /**
     * Restores a FreeCapsule placement from its original proof reference. The
     * caller must have bounded and completely framed the packet before this
     * method, and must share {@code budget}'s numeric Work across packet attempts.
     */
    static PlayerBodyPlacement restoreFreeV1(
            FreeCapsulePlacementV1 wire,
            RawQuaternion rawQuaternion,
            PlayerBodyShape.Dimensions dimensions,
            RawAabb acceptedProxy,
            RawVec3 acceptedTransport,
            PhysicsDecodeBudget budget) {
        Objects.requireNonNull(budget, "budget").beginFreeRestore();
        Objects.requireNonNull(wire, "wire");
        Objects.requireNonNull(rawQuaternion, "rawQuaternion");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(acceptedProxy, "acceptedProxy");
        Objects.requireNonNull(acceptedTransport, "acceptedTransport");

        int fieldCount = wire.rawFieldCount() + 4 + 3 + 6 + 3;
        budget.chargeRawFieldReads(fieldCount);

        // Validate raw bits before any CHB Vec3d/Aabb constructor can canonicalize them.
        RawVec3 currentCenter = wire.currentCenter();
        RawVec3 storedHalfLine = wire.storedHalfLine();
        RawVec3 centerReference = wire.centerReference();
        currentCenter.requireFiniteCanonical("current center");
        storedHalfLine.requireFiniteCanonical("stored half-line");
        centerReference.requireFiniteCanonical("original center reference");
        validateDimensions(dimensions);
        rawQuaternion.requireFiniteCanonical();
        Objects.requireNonNull(wire.proof(), "realization proof");
        RawVec3 realizedCenterBits = null;
        double supportCoordinate = Double.NaN;
        if (wire.proof() instanceof AxisPlane axisPlane) {
            realizedCenterBits = axisPlane.realizedCenter();
            realizedCenterBits.requireFiniteCanonical("axis-plane realized center");
            supportCoordinate = Double.longBitsToDouble(axisPlane.supportCoordinateBits());
            requireFiniteCanonical(supportCoordinate, axisPlane.supportCoordinateBits(),
                    "axis-plane support coordinate");
        } else if (!(wire.proof() instanceof Nominal)) {
            throw new IllegalArgumentException("unsupported FreeCapsule realization proof variant");
        }
        acceptedProxy.min().requireFiniteCanonical("accepted proxy minimum");
        acceptedProxy.max().requireFiniteCanonical("accepted proxy maximum");
        acceptedTransport.requireFiniteCanonical("accepted transport");

        PlayerBodyShape.FreeCapsule shape;
        if (wire.bodyProfile().isPresent()) {
            var body = wire.bodyProfile().orElseThrow();
            double diameter = Double.longBitsToDouble(body.diameterBits());
            double length = Double.longBitsToDouble(body.lengthBits());
            requireFiniteCanonical(diameter, body.diameterBits(), "physical diameter");
            requireFiniteCanonical(length, body.lengthBits(), "physical length");
            body.eyeCenter().requireFiniteCanonical("eye center anchor");
            body.worldEye().requireFiniteCanonical("world eye anchor");
            body.eyeAttitude().requireFiniteCanonical();
            budget.reserveEyeAnchorQuaternionReconstruction();
            var eyeQ = body.eyeAttitude().reconstructExact();
            if (!body.eyeAttitude().exactlyMatches(eyeQ))
                throw new IllegalArgumentException("eye quaternion reconstruction changed raw bits");
            var anchor = new FreeEyeAnchor(body.eyeRecipeVersion(), body.eyeCenter().toVector("eye center anchor"),
                    body.worldEye().toVector("world eye anchor"), eyeQ);
            shape = new PlayerBodyShape.FreeCapsule(dimensions, new PlayerBodyShape.CollisionProfile(diameter, length),
                    java.util.Optional.of(anchor));
        }
        else shape = new PlayerBodyShape.FreeCapsule(dimensions);
        var geometryDimensions = shape.geometryDimensions();
        Vec3d currentCenterVector = currentCenter.toVector("current center");
        Vec3d storedHalfLineVector = storedHalfLine.toVector("stored half-line");
        Vec3d referenceVector = centerReference.toVector("original center reference");
        Vec3d realizedCenterVector = realizedCenterBits == null
                ? referenceVector : realizedCenterBits.toVector("axis-plane realized center");
        Aabb proxyBounds = acceptedProxy.toBounds("accepted proxy");
        Vec3d transportVector = acceptedTransport.toVector("accepted transport");

        budget.reserveQuaternionReconstruction();
        Rotation3d attitude = rawQuaternion.reconstructExact();
        if (!rawQuaternion.exactlyMatches(attitude)) {
            throw new IllegalArgumentException("exact quaternion reconstruction changed raw representation bits");
        }

        StoredDyadic.Work numericWork = budget.numericWork();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(
                referenceVector, attitude, geometryDimensions, numericWork);
        RealizationRelation relation;
        if (wire.proof() instanceof Nominal) {
            if (!StoredRealizationLimits.sameRaw(storedHalfLineVector, limits.nominalHalfLine())) {
                throw new IllegalArgumentException("nominal proof half-line differs from its original recipe");
            }
            relation = RealizationRelation.nominal(limits);
        } else if (wire.proof() instanceof AxisPlane axisPlane) {
            relation = RealizationRelation.axisPlane(limits, axisPlane.modifiedAxis(), axisPlane.outwardSign(),
                    supportCoordinate, realizedCenterVector, storedHalfLineVector, numericWork);
        } else {
            throw new IllegalArgumentException("unsupported FreeCapsule realization proof variant");
        }

        StoredCapsuleGeometry originalGeometry = StoredCapsuleGeometry.fromRecipe(
                realizedCenterVector, attitude, geometryDimensions, storedHalfLineVector, relation);
        // Preserve the original C_ref proof and actual H_s across the fixed-attitude translation.
        StoredCapsuleGeometry currentGeometry = originalGeometry.withTranslatedCenter(currentCenterVector);
        return PlayerBodyPlacement.fromAcceptedFreeGeometry(currentCenterVector, attitude, shape,
                currentGeometry, proxyBounds, transportVector, numericWork);
    }

    private static void validateDimensions(PlayerBodyShape.Dimensions dimensions) {
        requireFiniteCanonical(dimensions.width(), Double.doubleToRawLongBits(dimensions.width()), "width");
        requireFiniteCanonical(dimensions.height(), Double.doubleToRawLongBits(dimensions.height()), "height");
        requireFiniteCanonical(dimensions.eyeHeight(), Double.doubleToRawLongBits(dimensions.eyeHeight()), "eye height");
    }

    private static void requireFiniteCanonical(double value, long rawBits, String label) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(label + " must be finite");
        if (rawBits == Long.MIN_VALUE) {
            throw new IllegalArgumentException(label + " must use canonical positive zero");
        }
    }
}
