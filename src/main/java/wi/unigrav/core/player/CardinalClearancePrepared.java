package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Optional;

/** One continuous, read-only translation then rotation under the original owner ledger. */
final class CardinalClearancePrepared {
    static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    sealed interface Result permits Prepared, NotApplicable, Unavailable { }
    record NotApplicable() implements Result { }
    record Unavailable(CardinalReorientation.Rejected rejection) implements Result { }
    record Prepared(FreeOwnerTransaction transaction, WorldCollisionCandidates.Snapshot capture, Aabb window,
            CapsulePathSource root, CapsuleTranslationValidator.VerifiedTranslation translation,
            CapsulePathSource continuation, CardinalRealizedArcRecipe.Prepared arcRecipe,
            CapsuleArcValidator.VerifiedArc arc, PlayerBodyPlacement target, double centerPathUpper) implements Result {
        boolean current() {
            transaction.requireCurrent();
            return root.actualSource() == transaction.accepted() && root.current()
                    && root.work() == transaction.numeric() && root.queries() == transaction.queries()
                    && root.capture() == capture && root.window() == window
                    && continuation.sameContext(root) && continuation.start() == arcRecipe.recipe().start()
                    && arcRecipe.recipe().matchesFullCardinalTarget(target)
                    && StoredRealizationLimits.sameRaw(centerPathUpper, translation.centerPathUpper())
                    && translation.matchesBound(root) && arc.matchesBound(continuation)
                    && arcRecipe.recipe().matchesPathSource(continuation)
                    && arcRecipe.recipe().matches(continuation.start(), arc.target(), transaction.numeric());
        }
    }
    enum TangentRadiusStatus { COMPATIBLE, Z_INCOMPATIBLE, OTHER_AXIS_INCOMPATIBLE }
    enum TangentCandidateFailure { POSITIVE_NORMAL_DOMAIN, BINADE_DOMAIN, RADIUS_GRID, CENTER_UNREPRESENTABLE, SUPPORT_DOMAIN }
    sealed interface TangentCandidate permits TangentCenter, TangentUnavailable { }
    record TangentCenter(double value) implements TangentCandidate { }
    record TangentUnavailable(TangentCandidateFailure reason) implements TangentCandidate { }
    private CardinalClearancePrepared() { }

    static Result prepare(FreeOwnerTransaction tx, CardinalFrame targetFrame) {
        var accepted = tx.accepted();
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != CardinalFrame.Z_POS || targetFrame != CardinalFrame.Y_POS)
            return new NotApplicable();
        var dims = cardinal.dimensions();
        if (!StoredRealizationLimits.sameRaw(dims.width(), (double) .6F)
                || !StoredRealizationLimits.sameRaw(dims.height(), (double) 1.8F)
                || !StoredRealizationLimits.sameRaw(dims.eyeHeight(), (double) 1.62F)) return new NotApplicable();
        try {
            tx.requireCurrent();
            var work = tx.numeric();
            var center = accepted.placement().center();
            double plane = accepted.placement().bounds().min().z();
            work.charge(3);
            double product = Math.nextUp(1.0) * dims.centerlineHalfLength();
            double lower = center.z() - product;
            boolean wallCompatible = StoredDyadic.of(lower, work).subtract(StoredDyadic.of(dims.radius(), work), work)
                    .compare(StoredDyadic.of(plane, work), work) >= 0;
            // Native Y+ tangent radius can exceed a rounded-in AABB even when the
            // rotation clears the wall. This is a distinct, scalar-independent trigger.
            if (wallCompatible && tangentRadiusStatus(center, dims, work) != TangentRadiusStatus.Z_INCOMPATIBLE)
                return new NotApplicable();
            var live = new PlayerBodyShape.Dimensions(tx.actor().width, tx.actor().height, tx.actor().getEyeHeight());
            tx.requireCurrent();
            var contained = CardinalContainedCapsuleSource.prepare(accepted, live, work);
            if (contained instanceof CardinalContainedCapsuleSource.Unavailable failure)
                return unavailable("clearance source " + failure.reason(), failure.cause().map(c -> (RuntimeException)c));
            var source = (CardinalContainedCapsuleSource.Prepared) contained;
            if (!CardinalRealizedArcRecipe.nominalClearanceSource(source.containment(), work)) return new NotApplicable();
            double z = wallCompatible ? center.z() : candidate(center.z(), product, dims.radius(), plane, work);
            var proposed = new Vec3d(center.x(), center.y(), z);
            var tangent = tangentRadiusStatus(proposed, dims, work);
            if (tangent == TangentRadiusStatus.OTHER_AXIS_INCOMPATIBLE)
                return unavailable("clearance native target tangent X radius incompatible", Optional.empty());
            if (tangent == TangentRadiusStatus.Z_INCOMPATIBLE) {
                var selected = tangentCandidate(center.z(), z, product, dims.radius(), plane, work);
                if (selected instanceof TangentUnavailable failure)
                    return unavailable("clearance native target tangent " + failure.reason(), Optional.empty());
                z = ((TangentCenter) selected).value();
            }
            var shifted = new Vec3d(center.x(), center.y(), z);
            var target = new PlayerBodyPlacement(shifted, targetFrame.attitude(), new PlayerBodyShape.Cardinal(targetFrame, dims));
            if (tangentRadiusStatus(shifted, dims, work) != TangentRadiusStatus.COMPATIBLE
                    || !StoredRealizationLimits.sameRaw(target.transport().z(), z))
                return unavailable("clearance native target tangent realization incompatible", Optional.empty());
            // A deliberately broad capture window is only a query bound. Every segment's
            // actual enclosure must fit it, and all captured obstacles remain authoritative.
            work.charge(12);
            double reach = dims.height() + dims.width();
            var window = union(accepted.placement().bounds(), new Aabb(
                    new Vec3d(center.x()-reach, center.y()-reach, Math.min(center.z(),z)-reach),
                    new Vec3d(center.x()+reach, center.y()+reach, Math.max(center.z(),z)+reach)));
            var captured = WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN);
            if (captured instanceof WorldCollisionCandidates.Unavailable failure)
                return unavailable("clearance capture " + failure.reason() + ": " + failure.detail(), failure.cause());
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            boolean supportingWall = false;
            var actual = accepted.placement().bounds();
            for (var box : capture.boxes()) {
                work.charge(11);
                supportingWall |= StoredRealizationLimits.sameRaw(box.max().z(), plane)
                        && box.min().x() < actual.max().x() && actual.min().x() < box.max().x()
                        && box.min().y() < actual.max().y() && actual.min().y() < box.max().y();
                if (overlaps(box, target.bounds()))
                    return new Unavailable(new CardinalReorientation.Rejected(CardinalReorientation.Reason.TARGET_OCCUPIED,
                            "clearance full Cardinal target occupied"));
            }
            if (!supportingWall) return new NotApplicable();
            var root = CapsulePathSource.containedCardinal(source.containment(), CapsulePlacementMath.pose(source.placement(), work),
                    capture, window, FreeOwnerTransaction.ACCURACY, tx.queries(), work);
            var translated = CapsuleTranslationValidator.validateToCenter(root, shifted);
            if (translated instanceof CapsuleTranslationValidator.Failed failure)
                return unavailable("clearance translation " + failure.failure(), runtime(failure.budgetCause()));
            var translation = (CapsuleTranslationValidator.VerifiedTranslation) translated;
            var continuation = CapsulePathSource.continueAfter(translation, work);
            var recipe = CardinalRealizedArcRecipe.prepareTranslated(source.containment(), continuation, translation, target, work);
            if (recipe instanceof CardinalRealizedArcRecipe.Unavailable failure)
                return unavailable("clearance recipe " + failure.reason() + ": " + failure.detail(), failure.cause());
            var prepared = (CardinalRealizedArcRecipe.Prepared) recipe;
            var result = CapsuleArcValidator.validateCardinal(continuation, prepared);
            if (result instanceof CapsuleArcValidator.Failed failure)
                return unavailable("clearance arc " + failure.failure(), runtime(failure.budgetCause()));
            var arc = (CapsuleArcValidator.VerifiedArc) result;
            var answer = new Prepared(tx, capture, window, root, translation, continuation, prepared, arc,
                    target, translation.centerPathUpper());
            return answer.current() ? answer : unavailable("clearance proof changed after validation", Optional.empty());
        } catch (NumericWorkExhaustedException exhausted) {
            return unavailable("clearance numeric budget exhausted", Optional.of(exhausted));
        } catch (RuntimeException failure) {
            return unavailable("clearance preparation failed", Optional.of(failure));
        }
    }
    static double candidate(double center, double product, double radius, double plane, StoredDyadic.Work work) {
        var endpointThreshold = StoredDyadic.of(plane,work).add(StoredDyadic.of(radius,work),work);
        double endpoint = endpointThreshold.roundUp(work);
        var midpoint = StoredDyadic.of(Math.nextDown(endpoint),work).add(StoredDyadic.of(endpoint,work),work)
                .multiply(StoredDyadic.of(.5,work),work);
        var threshold = midpoint.add(StoredDyadic.of(product,work),work);
        double result = threshold.roundUp(work);
        if ((Double.doubleToRawLongBits(endpoint)&1) != 0 && StoredDyadic.of(result,work).compare(threshold,work)==0)
            result = Math.nextUp(result);
        result = Math.max(result,center);
        work.charge(2);
        if (!Double.isFinite(result) || !(result > center)
                || StoredDyadic.of(result-product,work).subtract(StoredDyadic.of(radius,work),work)
                    .compare(StoredDyadic.of(plane,work),work)<0)
            throw new IllegalStateException("clearance RN-cell candidate does not clear its exact support plane");
        return result;
    }
    /** Exact radius supports against the actual native Y+ tangent faces; h is absent. */
    static TangentRadiusStatus tangentRadiusStatus(Vec3d center, PlayerBodyShape.Dimensions dims, StoredDyadic.Work work) {
        work.charge(12);
        var target = new PlayerBodyPlacement(center, CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dims));
        var r = StoredDyadic.of(dims.radius(), work);
        var x = StoredDyadic.of(center.x(), work);
        if (x.subtract(r, work).compare(StoredDyadic.of(target.bounds().min().x(), work), work) < 0
                || x.add(r, work).compare(StoredDyadic.of(target.bounds().max().x(), work), work) > 0)
            return TangentRadiusStatus.OTHER_AXIS_INCOMPATIBLE;
        var z = StoredDyadic.of(center.z(), work);
        return z.subtract(r, work).compare(StoredDyadic.of(target.bounds().min().z(), work), work) < 0
                || z.add(r, work).compare(StoredDyadic.of(target.bounds().max().z(), work), work) > 0
                ? TangentRadiusStatus.Z_INCOMPATIBLE : TangentRadiusStatus.COMPATIBLE;
    }

    /**
     * First jointly feasible positive-normal center in one certified S+=L+r binade.
     * Its grid is derived from exact S+, not the exponent of a rounded sum. Every
     * feasible center in this binade has C+r on that grid, so ceil64(S+)-r is minimal.
     * Crossing that binade or incompatible radius/center grids is explicitly unsupported.
     */
    static TangentCandidate tangentCandidate(double original, double lower, double product, double radius, double plane,
                                             StoredDyadic.Work work) {
        work.charge(8);
        if (!Double.isFinite(original) || !Double.isFinite(lower) || !Double.isFinite(radius)
                || !Double.isFinite(product) || !Double.isFinite(plane) || !(lower >= original && original > radius)
                || radius < Double.MIN_NORMAL || original < Double.MIN_NORMAL || lower < Double.MIN_NORMAL)
            return new TangentUnavailable(TangentCandidateFailure.POSITIVE_NORMAL_DOMAIN);
        var r = StoredDyadic.of(radius, work);
        var sum = StoredDyadic.of(lower, work).add(r, work);
        double down = sum.roundDown(work);
        work.charge(3);
        int exponent = Math.getExponent(down);
        if (!Double.isFinite(down) || down < Double.MIN_NORMAL || exponent >= Double.MAX_EXPONENT)
            return new TangentUnavailable(TangentCandidateFailure.BINADE_DOMAIN);
        var binade = StoredDyadic.of(Math.scalb(1.0, exponent), work);
        var upper = binade.scalePow2(1, work);
        if (sum.compare(binade, work) < 0 || sum.compare(upper, work) >= 0)
            return new TangentUnavailable(TangentCandidateFailure.BINADE_DOMAIN);
        var radiusGrids = r.scalePow2(1 - (exponent - 52), work);
        double gridCount = radiusGrids.roundNearest(work);
        work.charge(2);
        if (!Double.isFinite(gridCount) || Math.rint(gridCount) != gridCount
                || StoredDyadic.of(gridCount, work).compare(radiusGrids, work) != 0)
            return new TangentUnavailable(TangentCandidateFailure.RADIUS_GRID);
        double endpoint = sum.roundUp(work);
        if (StoredDyadic.of(endpoint, work).compare(upper, work) >= 0)
            return new TangentUnavailable(TangentCandidateFailure.BINADE_DOMAIN);
        var exactCenter = StoredDyadic.of(endpoint, work).subtract(r, work);
        if (!exactCenter.isExactlyRepresentable(work))
            return new TangentUnavailable(TangentCandidateFailure.CENTER_UNREPRESENTABLE);
        double result = exactCenter.roundNearest(work);
        var minus = exactCenter.subtract(r, work);
        var plus = exactCenter.add(r, work);
        work.charge(3);
        if (!Double.isFinite(result) || result < lower || !(result > original) || minus.signum() <= 0
                || !minus.isExactlyRepresentable(work) || !plus.isExactlyRepresentable(work)
                || plus.compare(upper, work) >= 0
                || StoredDyadic.of(result - product, work).subtract(r, work).compare(StoredDyadic.of(plane, work), work) < 0)
            return new TangentUnavailable(TangentCandidateFailure.SUPPORT_DOMAIN);
        return new TangentCenter(result);
    }
    private static Optional<RuntimeException> runtime(Throwable cause) {
        if (cause == null) return Optional.empty();
        if (cause instanceof RuntimeException runtime) return Optional.of(runtime);
        return Optional.of(new IllegalStateException("clearance validation failed", cause));
    }
    private static Unavailable unavailable(String detail, Optional<RuntimeException> cause) {
        return new Unavailable(new CardinalReorientation.Rejected(CardinalReorientation.Reason.WORLD_UNAVAILABLE, detail, cause));
    }
    static boolean overlaps(Aabb a, Aabb b) {
        return a.min().x()<b.max().x() && b.min().x()<a.max().x() && a.min().y()<b.max().y()
                && b.min().y()<a.max().y() && a.min().z()<b.max().z() && b.min().z()<a.max().z();
    }
    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(new Vec3d(Math.min(a.min().x(),b.min().x()), Math.min(a.min().y(),b.min().y()),Math.min(a.min().z(),b.min().z())),
                new Vec3d(Math.max(a.max().x(),b.max().x()),Math.max(a.max().y(),b.max().y()),Math.max(a.max().z(),b.max().z())));
    }
}
