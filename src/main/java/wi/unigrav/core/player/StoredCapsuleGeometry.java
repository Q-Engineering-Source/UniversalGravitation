package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Actual stored FreeCapsule endpoints plus the numeric relation that produced
 * the one physical negative endpoint. This is geometry evidence only.
 */
final class StoredCapsuleGeometry {
    private final Vec3d center;
    private final Vec3d nominalHalfLine;
    private final Vec3d storedHalfLine;
    private final double radius;
    private final Rotation3d attitude;
    private final PlayerBodyShape.Dimensions dimensions;
    private final Vec3d negativeEndpoint;
    private final Vec3d positiveEndpoint;
    private final Capsule capsule;
    private final boolean negativeEndpointCanonicalStart;
    private final double negativeEndpointCanonicalParameter;
    private final RealizationRelation relation;

    private StoredCapsuleGeometry(Vec3d center, Rotation3d attitude,
                                  PlayerBodyShape.Dimensions dimensions, Vec3d storedHalfLine,
                                  RealizationRelation relation, boolean initialRecipe) {
        this.center = Objects.requireNonNull(center, "center");
        this.attitude = Objects.requireNonNull(attitude, "attitude");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.storedHalfLine = Objects.requireNonNull(storedHalfLine, "storedHalfLine");
        this.relation = Objects.requireNonNull(relation, "relation");
        radius = dimensions.radius();
        if (!Double.isFinite(radius) || radius <= 0 || radius >= 64) {
            throw new IllegalArgumentException("stored capsule radius outside finite consumer profile");
        }
        StoredRealizationLimits.requirePoint(center, "stored capsule center");
        nominalHalfLine = relation.nominalHalfLine();
        if (!relation.matchesFixedRecipe(attitude, dimensions, radius)
                || !StoredRealizationLimits.sameRaw(storedHalfLine, relation.storedHalfLine())
                || (initialRecipe && !relation.matchesInitialRecipe(center, attitude, dimensions, storedHalfLine, radius))) {
            throw new IllegalArgumentException("stored geometry does not match its realization proof");
        }
        negativeEndpoint = center.subtract(storedHalfLine);
        positiveEndpoint = center.add(storedHalfLine);
        StoredRealizationLimits.requirePoint(negativeEndpoint, "stored capsule negative endpoint");
        StoredRealizationLimits.requirePoint(positiveEndpoint, "stored capsule positive endpoint");
        Segment3d segment = new Segment3d(negativeEndpoint, positiveEndpoint);
        boolean startsAtNegative = StoredRealizationLimits.sameRaw(segment.start(), negativeEndpoint);
        boolean endsAtNegative = StoredRealizationLimits.sameRaw(segment.end(), negativeEndpoint);
        if (!startsAtNegative && !endsAtNegative) {
            throw new IllegalStateException("CHB canonical segment lost the physical negative endpoint mapping");
        }
        negativeEndpointCanonicalStart = startsAtNegative;
        negativeEndpointCanonicalParameter = startsAtNegative ? 0.0 : 1.0;
        capsule = new Capsule(segment, radius);
    }

    private StoredCapsuleGeometry(StoredCapsuleGeometry source, PlayerBodyShape.Dimensions replacement) {
        if (!StoredRealizationLimits.sameRaw(source.dimensions.width(), replacement.width())
                || !StoredRealizationLimits.sameRaw(source.dimensions.height(), replacement.height()))
            throw new IllegalArgumentException("Eye metadata cannot change a stored capsule");
        center = source.center;
        nominalHalfLine = source.nominalHalfLine;
        storedHalfLine = source.storedHalfLine;
        radius = source.radius;
        attitude = source.attitude;
        dimensions = replacement;
        negativeEndpoint = source.negativeEndpoint;
        positiveEndpoint = source.positiveEndpoint;
        capsule = source.capsule;
        negativeEndpointCanonicalStart = source.negativeEndpointCanonicalStart;
        negativeEndpointCanonicalParameter = source.negativeEndpointCanonicalParameter;
        relation = source.relation.withEyeMetadata(replacement);
        if (!matchesRecipe(center, attitude, replacement)
                || !relation.matchesFixedRecipe(attitude, replacement, radius))
            throw new IllegalStateException("Eye metadata lost the original stored recipe");
    }

    StoredCapsuleGeometry withEyeMetadata(PlayerBodyShape.Dimensions replacement) {
        return new StoredCapsuleGeometry(this, replacement);
    }

    static StoredCapsuleGeometry fromRecipe(Vec3d center, Rotation3d attitude,
                                            PlayerBodyShape.Dimensions dimensions,
                                            Vec3d storedHalfLine, RealizationRelation relation) {
        return new StoredCapsuleGeometry(center, attitude, dimensions, storedHalfLine, relation, true);
    }

    /**
     * Keeps the exact accepted half-line and its original C_ref proof during a
     * fixed-Q, fixed-size center translation. A smaller bound at the new binade
     * cannot invalidate geometry already proved at the original reference.
     */
    StoredCapsuleGeometry withTranslatedCenter(Vec3d translatedCenter) {
        Objects.requireNonNull(translatedCenter, "translatedCenter");
        if (StoredRealizationLimits.sameRaw(center, translatedCenter)) return this;
        return new StoredCapsuleGeometry(translatedCenter, attitude, dimensions, storedHalfLine, relation, false);
    }

    Vec3d center() { return center; }
    Vec3d nominalHalfLine() { return nominalHalfLine; }
    Vec3d storedHalfLine() { return storedHalfLine; }
    double radius() { return radius; }
    Rotation3d attitude() { return attitude; }
    PlayerBodyShape.Dimensions dimensions() { return dimensions; }
    Vec3d negativeEndpoint() { return negativeEndpoint; }
    Vec3d positiveEndpoint() { return positiveEndpoint; }
    Capsule capsule() { return capsule; }
    boolean negativeEndpointCanonicalStart() { return negativeEndpointCanonicalStart; }
    double negativeEndpointCanonicalParameter() { return negativeEndpointCanonicalParameter; }
    RealizationRelation relation() { return relation; }
    int peakDyadicBits() { return relation.peakDyadicBits(); }

    boolean matchesRecipe(Vec3d expectedCenter, Rotation3d expectedAttitude,
                          PlayerBodyShape.Dimensions expectedDimensions) {
        return StoredRealizationLimits.sameRaw(center, expectedCenter)
                && StoredRealizationLimits.sameRaw(attitude, expectedAttitude)
                && StoredRealizationLimits.sameRaw(dimensions, expectedDimensions)
                && StoredRealizationLimits.sameRaw(radius, expectedDimensions.radius());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredCapsuleGeometry that
                && StoredRealizationLimits.sameRaw(center, that.center)
                && StoredRealizationLimits.sameRaw(nominalHalfLine, that.nominalHalfLine)
                && StoredRealizationLimits.sameRaw(storedHalfLine, that.storedHalfLine)
                && StoredRealizationLimits.sameRaw(radius, that.radius)
                && StoredRealizationLimits.sameRaw(attitude, that.attitude)
                && StoredRealizationLimits.sameRaw(dimensions, that.dimensions)
                && StoredRealizationLimits.sameRaw(negativeEndpoint, that.negativeEndpoint)
                && StoredRealizationLimits.sameRaw(positiveEndpoint, that.positiveEndpoint)
                && negativeEndpointCanonicalStart == that.negativeEndpointCanonicalStart
                && negativeEndpointCanonicalParameter == that.negativeEndpointCanonicalParameter
                && relation.equals(that.relation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw(center.x()), raw(center.y()), raw(center.z()),
                raw(nominalHalfLine.x()), raw(nominalHalfLine.y()), raw(nominalHalfLine.z()),
                raw(storedHalfLine.x()), raw(storedHalfLine.y()), raw(storedHalfLine.z()),
                raw(radius), raw(attitude.x()), raw(attitude.y()), raw(attitude.z()), raw(attitude.w()),
                raw(dimensions.width()), raw(dimensions.height()), raw(dimensions.eyeHeight()),
                raw(negativeEndpoint.x()), raw(negativeEndpoint.y()), raw(negativeEndpoint.z()),
                raw(positiveEndpoint.x()), raw(positiveEndpoint.y()), raw(positiveEndpoint.z()),
                negativeEndpointCanonicalStart, relation);
    }

    private static long raw(double value) {
        return Double.doubleToRawLongBits(value);
    }

    @Override
    public String toString() {
        return "StoredCapsuleGeometry[center=" + center + ", negative=" + negativeEndpoint
                + ", positive=" + positiveEndpoint + ", radius=" + radius + ", relation=" + relation.kind() + "]";
    }
}