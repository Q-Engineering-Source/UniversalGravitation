package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.Optional;

/** Virtual Cardinal arc recipe. Neither this proposal nor its numeric proof installs geometry. */
final class CardinalRealizedArcRecipe {
    enum Reason { SOURCE_IDENTITY, SOURCE_ENDPOINTS, SOURCE_DOMAIN, TARGET_DOMAIN, TARGET_CONTAINMENT, PATH_DOMAIN, NUMERIC_BUDGET }
    sealed interface Result permits Prepared, Unavailable { }
    record Prepared(CardinalRealizedArcRecipe recipe, CapsuleArcEnclosure.Path path) implements Result { }
    record Unavailable(Reason reason, String detail, Optional<RuntimeException> cause) implements Result { }
    static final class DomainException extends IllegalArgumentException {
        DomainException(String message) { super(message); }
    }
    private sealed interface SourceChoice permits PreparedSource, NotApplicable { }
    private record PreparedSource(PlayerBodyPlacement placement,
            CardinalCapsuleContainment.Contained containment, double scalar) implements SourceChoice { }
    private record NotApplicable(Reason reason, String detail) implements SourceChoice { }

    private final CardinalCapsuleContainment.Contained source;
    private final CapsulePlacementMath.Pose start;
    private final PlayerBodyPlacement target;
    private final CardinalCapsuleContainment.Contained targetContainment;
    private final CapsulePathSource translatedSource;
    private final CapsuleTranslationValidator.VerifiedTranslation translation;
    private final PlayerBodyPlacement fullTarget;
    private final double halfLength;
    private final StoredDyadic.Work work;

    private CardinalRealizedArcRecipe(CardinalCapsuleContainment.Contained source,
            CapsulePlacementMath.Pose start, PlayerBodyPlacement target,
            CardinalCapsuleContainment.Contained targetContainment, double halfLength, StoredDyadic.Work work) {
        this(source, start, target, targetContainment, halfLength, work, null, null, null);
    }
    private CardinalRealizedArcRecipe(CardinalCapsuleContainment.Contained source,
            CapsulePlacementMath.Pose start, PlayerBodyPlacement target,
            CardinalCapsuleContainment.Contained targetContainment, double halfLength, StoredDyadic.Work work,
            CapsulePathSource translatedSource, CapsuleTranslationValidator.VerifiedTranslation translation,
            PlayerBodyPlacement fullTarget) {
        this.translatedSource = translatedSource; this.translation = translation; this.fullTarget = fullTarget;
        this.source = source; this.start = start; this.target = target;
        this.targetContainment = targetContainment; this.halfLength = halfLength; this.work = work;
    }

    /** Bind the stationary arc to the exact full collider that its owner will install. */
    static Result prepare(CardinalCapsuleContainment.Contained certificate, PlayerBodyPlacement fullTarget,
            StoredDyadic.Work work) {
        if (!(fullTarget.shape() instanceof PlayerBodyShape.Cardinal targetCardinal)
                || !(certificate.source().placement().shape() instanceof PlayerBodyShape.Cardinal sourceCardinal)
                || !StoredRealizationLimits.sameRaw(fullTarget.center(), certificate.source().placement().center())
                || !StoredRealizationLimits.sameRaw(fullTarget.attitude(), targetCardinal.frame().attitude())
                || !StoredRealizationLimits.sameRaw(targetCardinal.dimensions(), sourceCardinal.dimensions()))
            return unavailable(Reason.TARGET_DOMAIN, "stationary full target changed center, frame or dimensions");
        var selected = prepare(certificate, targetCardinal.frame(), work);
        if (selected instanceof Unavailable failure) return failure;
        var previous = ((Prepared) selected).recipe();
        var accepted = certificate.source();
        var targetSnapshot = new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision(), fullTarget,
                accepted.worldVelocity(), accepted.contacts());
        var containment = CardinalCapsuleContainment.prepare(targetSnapshot, previous.target, work);
        if (!(containment instanceof CardinalCapsuleContainment.Contained targetCertificate))
            return unavailable(Reason.TARGET_CONTAINMENT, "stationary arc target is outside its exact installed collider");
        var recipe = new CardinalRealizedArcRecipe(previous.source, previous.start, previous.target,
                targetCertificate, previous.halfLength, work, null, null, fullTarget);
        return new Prepared(recipe, CapsuleArcEnclosure.prepareFixed(recipe, sourceCardinal.frame().up(), work));
    }

    static Result prepare(CardinalCapsuleContainment.Contained certificate, CardinalFrame targetFrame,
            StoredDyadic.Work work) {
        Objects.requireNonNull(certificate); Objects.requireNonNull(targetFrame); Objects.requireNonNull(work);
        try {
            work.charge(1);
            var accepted = certificate.source();
            var virtual = certificate.virtualPlacement();
            if (!certificate.matches(accepted, virtual, work)
                    || !(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
                return unavailable(Reason.SOURCE_IDENTITY, "source certificate or Work changed");
            var choice = prepareH1Source(certificate, cardinal, work);
            if (choice instanceof PreparedSource preparedSource) {
                // Selection is final before target inspection. No target/path failure falls back.
                return finishSource(preparedSource, targetFrame, work);
            }
            var rejected = (NotApplicable) choice;
            if (virtual.storedCapsuleGeometry().relation().kind() != RealizationRelation.Kind.NOMINAL)
                return unavailable(rejected.reason(), rejected.detail());
            return prepareCandidate(certificate, targetFrame, cardinal.dimensions().centerlineHalfLength(), work);
        } catch (NumericWorkExhaustedException exhausted) {
            return new Unavailable(Reason.NUMERIC_BUDGET, "fixed arc numeric budget exhausted", Optional.of(exhausted));
        } catch (DomainException unproved) {
            return new Unavailable(Reason.PATH_DOMAIN, unproved.getMessage(), Optional.of(unproved));
        }
    }

    /** Source-only eligibility. No target, obstacle or path result can influence this choice. */
    private static SourceChoice prepareH1Source(CardinalCapsuleContainment.Contained certificate,
            PlayerBodyShape.Cardinal cardinal, StoredDyadic.Work work) {
        work.charge(1);
        var dimensions = cardinal.dimensions();
        if (!StoredRealizationLimits.sameRaw(dimensions.width(), (double) .6F)
                || !StoredRealizationLimits.sameRaw(dimensions.height(), (double) 1.8F))
            return new NotApplicable(Reason.SOURCE_DOMAIN, "H1 requires the native float profile");
        // Select only a recipe whose whole-orientation certificate covers this source center.
        // NOMINAL fallback still requires its original source relation and exact endpoint bits;
        // target containment, path proof and collision rejection never influence this choice.
        if (!CardinalJointRnDomain.supportsCenterGrid(certificate.source().placement().center(), work))
            return new NotApplicable(Reason.SOURCE_DOMAIN, "H1 whole-domain center grid is unsupported; center="
                    + certificate.source().placement().center() + "; sourceRelation="
                    + certificate.virtualPlacement().storedCapsuleGeometry().relation().kind());
        double scalar = Double.longBitsToDouble(0x3fe333331ffffffcL);
        var accepted = certificate.source();
        var limits = StoredRealizationLimits.derive(accepted.placement().center(), cardinal.frame().attitude(), dimensions, work);
        // Explicit domain predicate, not a catch of the proposal/target/path pipeline.
        var half = limits.attitude().basisY().multiply(scalar);
        for (int axis = 0; axis < 3; axis++) {
            var difference = StoredDyadic.of(StoredRealizationLimits.component(half, axis), work)
                    .subtract(StoredDyadic.of(StoredRealizationLimits.component(limits.nominalHalfLine(), axis), work), work).abs(work);
            if (difference.compare(limits.halfLineBound(axis), work) > 0)
                return new NotApplicable(Reason.SOURCE_DOMAIN, "H1 source component is outside its realization domain");
        }
        var first = proposal(limits, dimensions, scalar, work);
        var original = certificate.virtualPlacement().storedCapsuleGeometry();
        var actual = first.storedCapsuleGeometry();
        if (!StoredRealizationLimits.sameRaw(original.negativeEndpoint(), actual.negativeEndpoint())
                || !StoredRealizationLimits.sameRaw(original.positiveEndpoint(), actual.positiveEndpoint())
                || !StoredRealizationLimits.sameRaw(original.radius(), actual.radius()))
            return new NotApplicable(Reason.SOURCE_ENDPOINTS, "H1 changes actual source endpoint or radius bits");
        var contained = CardinalCapsuleContainment.prepare(accepted, first, work);
        if (!(contained instanceof CardinalCapsuleContainment.Contained exact))
            return new NotApplicable(Reason.SOURCE_ENDPOINTS, "H1 source lost exact containment");
        return new PreparedSource(first, exact, scalar);
    }

    private static Result finishSource(PreparedSource selected, CardinalFrame targetFrame, StoredDyadic.Work work) {
        var accepted = selected.containment().source();
        var cardinal = (PlayerBodyShape.Cardinal) accepted.placement().shape();
        var center = accepted.placement().center();
        var dimensions = cardinal.dimensions();
        final PlayerBodyPlacement target;
        try {
            target = proposal(center, targetFrame, dimensions, selected.scalar(), work);
        } catch (NumericWorkExhaustedException exhausted) {
            throw exhausted;
        } catch (IllegalArgumentException domain) {
            return new Unavailable(Reason.TARGET_DOMAIN, domain.getMessage(), Optional.of(domain));
        }
        var cardinalTarget = new PlayerBodyPlacement(center, targetFrame.attitude(),
                new PlayerBodyShape.Cardinal(targetFrame, dimensions));
        var targetSnapshot = new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision(),
                cardinalTarget, accepted.worldVelocity(), accepted.contacts());
        var targetContained = CardinalCapsuleContainment.prepare(targetSnapshot, target, work);
        if (!(targetContained instanceof CardinalCapsuleContainment.Contained targetCertificate))
            return unavailable(Reason.TARGET_CONTAINMENT, "fixed scalar target is outside full Cardinal target");
        var recipe = new CardinalRealizedArcRecipe(selected.containment(), CapsulePlacementMath.pose(selected.placement(), work),
                target, targetCertificate, selected.scalar(), work);
        return new Prepared(recipe, CapsuleArcEnclosure.prepareFixed(recipe, cardinal.frame().up(), work));
    }
    static Result prepareCandidate(CardinalCapsuleContainment.Contained certificate, CardinalFrame targetFrame,
            double scalar, StoredDyadic.Work work) {
        Objects.requireNonNull(certificate); Objects.requireNonNull(targetFrame); Objects.requireNonNull(work);
        try {
            work.charge(1);
            var accepted = certificate.source();
            var virtual = certificate.virtualPlacement();
            if (!certificate.matches(accepted, virtual, work)
                    || !(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
                return unavailable(Reason.SOURCE_IDENTITY, "source certificate or Work changed");
            var center = accepted.placement().center();
            var dimensions = cardinal.dimensions();
            PlayerBodyPlacement first;
            PlayerBodyPlacement target;
            try {
                first = proposal(center, cardinal.frame(), dimensions, scalar, work);
            } catch (NumericWorkExhaustedException exhausted) {
                throw exhausted;
            } catch (IllegalArgumentException domain) {
                return new Unavailable(Reason.SOURCE_DOMAIN, domain.getMessage(), Optional.of(domain));
            }
            try {
                target = proposal(center, targetFrame, dimensions, scalar, work);
            } catch (NumericWorkExhaustedException exhausted) {
                throw exhausted;
            } catch (IllegalArgumentException domain) {
                return new Unavailable(Reason.TARGET_DOMAIN, domain.getMessage(), Optional.of(domain));
            }
            var original = virtual.storedCapsuleGeometry();
            var actual = first.storedCapsuleGeometry();
            if (!StoredRealizationLimits.sameRaw(original.negativeEndpoint(), actual.negativeEndpoint())
                    || !StoredRealizationLimits.sameRaw(original.positiveEndpoint(), actual.positiveEndpoint()))
                return unavailable(Reason.SOURCE_ENDPOINTS, "fixed scalar changed actual source endpoint bits");
            var firstContainment = CardinalCapsuleContainment.prepare(accepted, first, work);
            if (!(firstContainment instanceof CardinalCapsuleContainment.Contained firstCertificate))
                return unavailable(Reason.SOURCE_ENDPOINTS, "fixed scalar source lost exact containment");
            var cardinalTarget = new PlayerBodyPlacement(center, targetFrame.attitude(),
                    new PlayerBodyShape.Cardinal(targetFrame, dimensions));
            var targetSnapshot = new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision(),
                    cardinalTarget, accepted.worldVelocity(), accepted.contacts());
            var targetContained = CardinalCapsuleContainment.prepare(targetSnapshot, target, work);
            if (!(targetContained instanceof CardinalCapsuleContainment.Contained targetCertificate))
                return unavailable(Reason.TARGET_CONTAINMENT, "fixed scalar target is outside full Cardinal target");
            var recipe = new CardinalRealizedArcRecipe(firstCertificate, CapsulePlacementMath.pose(first, work),
                    target, targetCertificate, scalar, work);
            return new Prepared(recipe, CapsuleArcEnclosure.prepareFixed(recipe, cardinal.frame().up(), work));
        } catch (NumericWorkExhaustedException exhausted) {
            return new Unavailable(Reason.NUMERIC_BUDGET, "fixed arc numeric budget exhausted", Optional.of(exhausted));
        } catch (DomainException unproved) {
            return new Unavailable(Reason.PATH_DOMAIN, unproved.getMessage(), Optional.of(unproved));
        }
    }

    /** Reuses the selector's source-only predicate. It never inspects a target or obstacle. */
    static boolean nominalClearanceSource(CardinalCapsuleContainment.Contained certificate, StoredDyadic.Work work) {
        work.charge(1);
        if (!certificate.matches(certificate.source(), certificate.virtualPlacement(), work)
                || !(certificate.source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
            throw new IllegalArgumentException("clearance source certificate or Work changed");
        return certificate.virtualPlacement().storedCapsuleGeometry().relation().kind() == RealizationRelation.Kind.NOMINAL
                && prepareH1Source(certificate, cardinal, work) instanceof NotApplicable;
    }

    /** A single verified translation, never a relabelled or reconstructed actual source. */
    static Result prepareTranslated(CardinalCapsuleContainment.Contained certificate,
            CapsulePathSource continuation, CapsuleTranslationValidator.VerifiedTranslation proof,
            PlayerBodyPlacement fullTarget, StoredDyadic.Work work) {
        try {
            work.charge(1);
            if (continuation.work() != work || !continuation.isTranslatedCardinalRoot(certificate, proof)
                    || !(certificate.source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                    || cardinal.frame() != CardinalFrame.Z_POS
                    || !(fullTarget.shape() instanceof PlayerBodyShape.Cardinal targetCardinal)
                    || targetCardinal.frame() != CardinalFrame.Y_POS
                    || !StoredRealizationLimits.sameRaw(continuation.start().placement().attitude(), cardinal.frame().attitude())
                    || !StoredRealizationLimits.sameRaw(fullTarget.attitude(), targetCardinal.frame().attitude())
                    || !StoredRealizationLimits.sameRaw(fullTarget.center(), continuation.start().placement().center())
                    || !StoredRealizationLimits.sameRaw(cardinal.dimensions(), targetCardinal.dimensions()))
                return unavailable(Reason.SOURCE_IDENTITY, "clearance continuation identity changed");
            var actual = continuation.start().placement();
            double scalar = cardinal.dimensions().centerlineHalfLength();
            var fresh = proposal(actual.center(), cardinal.frame(), cardinal.dimensions(), scalar, work);
            var a = actual.storedCapsuleGeometry(); var b = fresh.storedCapsuleGeometry();
            if (!StoredRealizationLimits.sameRaw(a.storedHalfLine(), b.storedHalfLine())
                    || !StoredRealizationLimits.sameRaw(a.negativeEndpoint(), b.negativeEndpoint())
                    || !StoredRealizationLimits.sameRaw(a.positiveEndpoint(), b.positiveEndpoint())
                    || !StoredRealizationLimits.sameRaw(a.radius(), b.radius()))
                return unavailable(Reason.SOURCE_ENDPOINTS, "translated actual geometry differs from the fixed nominal recipe");
            var target = proposal(actual.center(), targetCardinal.frame(), cardinal.dimensions(), scalar, work);
            if (!exactTargetContained(target, fullTarget, work))
                return unavailable(Reason.TARGET_CONTAINMENT, "translated arc target is outside the full Cardinal target");
            var recipe = new CardinalRealizedArcRecipe(certificate, continuation.start(), target, null,
                    scalar, work, continuation, proof, fullTarget);
            return new Prepared(recipe, CapsuleArcEnclosure.prepareFixed(recipe, cardinal.frame().up(), work));
        } catch (NumericWorkExhaustedException exhausted) {
            return new Unavailable(Reason.NUMERIC_BUDGET, "clearance recipe numeric budget exhausted", Optional.of(exhausted));
        } catch (DomainException unproved) {
            return new Unavailable(Reason.PATH_DOMAIN, unproved.getMessage(), Optional.of(unproved));
        }
    }
    private static boolean exactTargetContained(PlayerBodyPlacement virtual, PlayerBodyPlacement full, StoredDyadic.Work work) {
        var geometry = virtual.storedCapsuleGeometry();
        var radius = StoredDyadic.of(geometry.radius(), work);
        for (int axis = 0; axis < 3; axis++) {
            double n = StoredRealizationLimits.component(geometry.negativeEndpoint(), axis);
            double p = StoredRealizationLimits.component(geometry.positiveEndpoint(), axis);
            if (StoredDyadic.of(Math.min(n, p), work).subtract(radius, work).compare(
                    StoredDyadic.of(StoredRealizationLimits.component(full.bounds().min(), axis), work), work) < 0
                    || StoredDyadic.of(Math.max(n, p), work).add(radius, work).compare(
                    StoredDyadic.of(StoredRealizationLimits.component(full.bounds().max(), axis), work), work) > 0) return false;
        }
        return true;
    }
    boolean matchesFullCardinalTarget(PlayerBodyPlacement exactTarget) {
        return fullTarget != null && fullTarget == exactTarget;
    }
    boolean matchesPathSource(CapsulePathSource pathSource) {
        return translatedSource == null ? pathSource.isContainedRoot(source)
                : pathSource == translatedSource && pathSource.isTranslatedCardinalRoot(source, translation);
    }
    private static PlayerBodyPlacement proposal(Vec3d center, CardinalFrame frame,
            PlayerBodyShape.Dimensions dimensions, double scalar, StoredDyadic.Work work) {
        var limits = StoredRealizationLimits.derive(center, frame.attitude(), dimensions, work);
        return proposal(limits, dimensions, scalar, work);
    }
    private static PlayerBodyPlacement proposal(StoredRealizationLimits limits,
            PlayerBodyShape.Dimensions dimensions, double scalar, StoredDyadic.Work work) {
        var center = limits.centerReference();
        var attitude = limits.attitude();
        var relation = RealizationRelation.fixedHalfLength(limits, scalar, work);
        var stored = StoredCapsuleGeometry.fromRecipe(center, attitude, dimensions,
                relation.storedHalfLine(), relation);
        return PlayerBodyPlacement.proposedFromStoredGeometry(center, attitude,
                new PlayerBodyShape.FreeCapsule(dimensions), stored, work);
    }
    private static Unavailable unavailable(Reason reason, String detail) {
        return new Unavailable(reason, detail, Optional.empty());
    }
    boolean matchesWork(StoredDyadic.Work same) { return same == work; }
    boolean matches(CapsulePlacementMath.Pose exactStart, PlayerBodyPlacement exactTarget, StoredDyadic.Work same) {
        return same == work && start == exactStart && target == exactTarget
                && (translatedSource == null
                    ? source.matches(source.source(), start.placement(), same)
                        && targetContainment.matches(targetContainment.source(), target, same)
                    : translatedSource.work() == same && translatedSource.start() == start
                        && translatedSource.isTranslatedCardinalRoot(source, translation)
                        && fullTarget != null);
    }
    CardinalCapsuleContainment.Contained sourceContainment() { return source; }
    CardinalCapsuleContainment.Contained targetContainment() { return targetContainment; }
    CapsulePlacementMath.Pose start() { return start; }
    PlayerBodyPlacement target() { return target; }
    double halfLength() { return halfLength; }
}
