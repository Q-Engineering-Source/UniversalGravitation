package wi.unigrav.core.player;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

/** Builds Aqua's real pose candidate in an accepted Cardinal frame for its existing collision query. */
public final class AquaCardinalPoseClearance {
    private static final String ARTEMIS_WIDTH = "artemislib.entityWidth";
    private static final String ARTEMIS_HEIGHT = "artemislib.entityHeight";
    private static final UUID AQUA_SWIMMING_HEIGHT_MODIFIER =
            UUID.fromString("F5ED4D20-4DAB-11EB-AE93-0242AC130002");

    private AquaCardinalPoseClearance() { }

    /** Full model scale for an already accepted long body; Aqua's short-pose factor is metadata. */
    public static double modelHeightScale(EntityPlayer actor) {
        Double value = attributeValue(actor, ARTEMIS_HEIGHT, AQUA_SWIMMING_HEIGHT_MODIFIER);
        if (value == null || !Double.isFinite(value) || value <= 0)
            throw new IllegalStateException("Accepted Aqua model requires a finite positive Artemis height scale");
        return value;
    }

    /**
     * Reorients a size candidate produced by Aqua's own pose method. That candidate already contains
     * Aqua's pose dimensions and built-in size integrations, so only Artemis's external size scale is
     * applied. Aqua's swimming-only attribute modifier is excluded by identity: the swimming pose
     * base is already present in Aqua's candidate dimensions. This shapes clearance only and grants no
     * geometry-install authority.
     */
    public static AxisAlignedBB candidate(EntityPlayer actor, PlayerPhysicalSnapshot accepted,
                                          AxisAlignedBB aquaCandidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(aquaCandidate, "aquaCandidate");
        if (accepted == null || !(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal))
            return aquaCandidate;
        if (!accepted.scope().playerId().equals(actor.getUniqueID())
                || accepted.scope().dimension() != actor.dimension)
            throw new IllegalArgumentException("Aqua clearance snapshot does not belong to the queried player world");

        Double registeredWidthScale = attributeValue(actor, ARTEMIS_WIDTH, null);
        Double registeredHeightScale = attributeValue(actor, ARTEMIS_HEIGHT, AQUA_SWIMMING_HEIGHT_MODIFIER);
        double widthScale;
        double heightScale;
        if (registeredWidthScale == null || registeredHeightScale == null) {
            if (registeredWidthScale != null || registeredHeightScale != null)
                throw new IllegalStateException("Artemis player geometry has only one of its width/height attributes");
            widthScale = 1.0;
            heightScale = 1.0;
        } else {
            widthScale = registeredWidthScale;
            heightScale = registeredHeightScale;
        }
        if (!Double.isFinite(widthScale) || widthScale <= 0.0
                || !Double.isFinite(heightScale) || heightScale <= 0.0)
            throw new IllegalStateException("Artemis player geometry returned a non-positive or non-finite size modifier");

        double widthX = extent(aquaCandidate.maxX - aquaCandidate.minX, 1.0, "Aqua candidate width");
        double widthZ = extent(aquaCandidate.maxZ - aquaCandidate.minZ, 1.0, "Aqua candidate depth");
        double extentDifference = Math.abs(widthX - widthZ);
        if (extentDifference > squareExtentRoundingBound(aquaCandidate, widthX, widthZ))
            throw new IllegalArgumentException("Aqua cardinal pose candidate must be square in its tangent plane");
        double width = extent(Math.max(widthX, widthZ), widthScale, "Aqua candidate width");
        double height = extent(aquaCandidate.maxY - aquaCandidate.minY, heightScale, "Aqua candidate height");

        var acceptedPlacement = accepted.placement();
        var dimensions = new PlayerBodyShape.Dimensions(width, height,
                acceptedPlacement.shape().dimensions().eyeHeight());
        var proposed = acceptedPlacement.proposeDimensionsAtFeet(dimensions);
        var bounds = proposed.bounds();
        return new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
    }

    /** Aqua has already applied its native size integrations; exclude only its pose-only swim modifier. */
    public static NativeBodyProfileProducer.Profile standingBodyProfile(EntityPlayer actor, float baseDiameter,
                                                                        float baseLength) {
        Objects.requireNonNull(actor, "actor");
        Double widthScale = attributeValue(actor, ARTEMIS_WIDTH, null);
        Double lengthScale = attributeValue(actor, ARTEMIS_HEIGHT, AQUA_SWIMMING_HEIGHT_MODIFIER);
        if ((widthScale == null) != (lengthScale == null))
            throw new IllegalStateException("Artemis body profile has only one registered size attribute");
        double width = widthScale == null ? 1.0 : widthScale;
        double length = lengthScale == null ? 1.0 : lengthScale;
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(length) || length <= 0)
            throw new IllegalStateException("Artemis body profile scale must be finite and positive");
        // Matches the real AttachAttributes producer's float -> double multiply -> float write.
        return new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.AQUA_STANDING,
                baseDiameter, baseLength, (float) (baseDiameter * width), (float) (baseLength * length));
    }

    private static Double attributeValue(EntityPlayer actor, String name, UUID excludedModifier) {
        IAttributeInstance instance = actor.getAttributeMap().getAttributeInstanceByName(name);
        if (instance == null) return null;

        Collection<AttributeModifier> modifiers = instance.getModifiers();
        double operationZero = instance.getBaseValue();
        for (AttributeModifier modifier : modifiers) {
            if (!modifier.getID().equals(excludedModifier) && modifier.getOperation() == 0)
                operationZero += modifier.getAmount();
        }

        double value = operationZero;
        for (AttributeModifier modifier : modifiers) {
            if (!modifier.getID().equals(excludedModifier) && modifier.getOperation() == 1)
                value += operationZero * modifier.getAmount();
        }
        for (AttributeModifier modifier : modifiers) {
            if (!modifier.getID().equals(excludedModifier) && modifier.getOperation() == 2)
                value *= 1.0 + modifier.getAmount();
        }
        return instance.getAttribute().clampValue(value);
    }

    private static double extent(double value, double multiplier, String description) {
        double scaled = value * multiplier;
        if (!Double.isFinite(value) || value <= 0.0 || !Double.isFinite(scaled) || scaled <= 0.0)
            throw new IllegalArgumentException(description + " must remain finite and positive");
        return scaled;
    }

    private static double squareExtentRoundingBound(AxisAlignedBB candidate, double widthX, double widthZ) {
        // Aqua creates both tangent intervals from the same float half-width. Bound only the
        // representational error of their four rounded endpoints and two rounded subtractions.
        double bound = 0.0;
        bound = addUlpUpperBound(bound, candidate.minX);
        bound = addUlpUpperBound(bound, candidate.maxX);
        bound = addUlpUpperBound(bound, candidate.minZ);
        bound = addUlpUpperBound(bound, candidate.maxZ);
        bound = addUlpUpperBound(bound, widthX);
        return addUlpUpperBound(bound, widthZ);
    }

    private static double addUlpUpperBound(double sum, double value) {
        return Math.nextUp(sum + Math.ulp(value));
    }
}
