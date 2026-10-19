package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Interval;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Witness;
import java.util.Objects;
import java.util.Optional;

/** A sufficient slope observation; standing, owner authority and world recapture remain caller responsibilities. */
final class CapsuleSupportSlope {
    private CapsuleSupportSlope() { }
    enum Reason { CONTEXT_CHANGED, NOT_FOOT_CONTACT, NO_WITNESS, SLOPE_UNCERTAIN, NUMERIC_WORK_EXHAUSTED }
    enum SlopeEvidence { SUFFICIENT, UNCERTAIN }
    sealed interface Result permits Certificate, Uncertain { }
    record Uncertain(Reason reason, Optional<NumericWorkExhaustedException> cause,
                     long workBefore, long workAfter) implements Result { }

    static Result certify(CapsuleFootContacts.VerifiedFootObservations observations, int ordinal,
                          StoredDyadic.Work sameWork) {
        Objects.requireNonNull(observations); Objects.requireNonNull(sameWork);
        long before = sameWork.units();
        try {
            sameWork.charge(1);
            if (!current(observations)) return uncertain(Reason.CONTEXT_CHANGED, before, sameWork);
            var query = observations.queries().get(ordinal);
            if (query.ordinal() != ordinal || query.classification() != CapsuleFootContacts.Classification.CONTACT
                    || query.contact().relation() != Relation.TOUCHING)
                return uncertain(Reason.NOT_FOOT_CONTACT, before, sameWork);
            if (observations.reach() == 0) {
                if (!query.boundaries().isEmpty() || query.direction().isEmpty()
                        || query.direction().orElseThrow().lower() <= 0)
                    return uncertain(Reason.NOT_FOOT_CONTACT, before, sameWork);
            } else if (query.boundaries().isEmpty()
                    || query.boundaries().getLast().kind() != BoundaryKind.BLOCKED_AT_START) {
                return uncertain(Reason.NOT_FOOT_CONTACT, before, sameWork);
            }
            if (query.contact().witness().isEmpty()) return uncertain(Reason.NO_WITNESS, before, sameWork);
            Vec3d up = observations.fullObservation().pose().placement().bodyUp();
            if (evaluate(query.contact().witness().orElseThrow(), up, sameWork) != SlopeEvidence.SUFFICIENT)
                return uncertain(Reason.SLOPE_UNCERTAIN, before, sameWork);
            if (!current(observations)) return uncertain(Reason.CONTEXT_CHANGED, before, sameWork);
            return new Certificate(observations, ordinal, sameWork.identity(), sameWork.units() - before);
        } catch (NumericWorkExhaustedException exhausted) {
            return new Uncertain(Reason.NUMERIC_WORK_EXHAUSTED, Optional.of(exhausted), before, sameWork.units());
        }
    }

    /** Numeric predicate only. This entry cannot mint a foot/standing certificate for another capsule witness. */
    static SlopeEvidence evaluate(Witness witness, Vec3d up, StoredDyadic.Work work) {
        Objects.requireNonNull(witness); Objects.requireNonNull(up); Objects.requireNonNull(work);
        var components = new ExactInterval[] {
                difference(witness.segmentX(), witness.boxX(), work),
                difference(witness.segmentY(), witness.boxY(), work),
                difference(witness.segmentZ(), witness.boxZ(), work)};
        double[] upComponents = {up.x(), up.y(), up.z()};
        StoredDyadic lowerDot = StoredDyadic.zero();
        StoredDyadic upperNorm = StoredDyadic.zero();
        StoredDyadic upNorm = StoredDyadic.zero();
        for (int axis = 0; axis < 3; axis++) {
            var component = components[axis];
            var direction = StoredDyadic.of(upComponents[axis], work);
            var product = (direction.signum() >= 0 ? component.lower : component.upper).multiply(direction, work);
            lowerDot = lowerDot.add(product, work);
            var first = component.lower.abs(work);
            var second = component.upper.abs(work);
            var maximum = first.compare(second, work) >= 0 ? first : second;
            upperNorm = upperNorm.add(maximum.multiply(maximum, work), work);
            upNorm = upNorm.add(direction.multiply(direction, work), work);
        }
        if (lowerDot.signum() <= 0 || upperNorm.signum() <= 0 || upNorm.signum() <= 0) return SlopeEvidence.UNCERTAIN;
        // All operations are exact bounded dyadics. These interval bounds enclose the true witness vector.
        var sufficientLower = lowerDot.multiply(lowerDot, work).scalePow2(2, work);
        return sufficientLower.compare(upperNorm.multiply(upNorm, work), work) >= 0
                ? SlopeEvidence.SUFFICIENT : SlopeEvidence.UNCERTAIN;
    }

    private record ExactInterval(StoredDyadic lower, StoredDyadic upper) { }
    private static ExactInterval difference(Interval segment, Interval box, StoredDyadic.Work work) {
        return new ExactInterval(StoredDyadic.of(segment.lower(), work).subtract(StoredDyadic.of(box.upper(), work), work),
                StoredDyadic.of(segment.upper(), work).subtract(StoredDyadic.of(box.lower(), work), work));
    }
    private static boolean current(CapsuleFootContacts.VerifiedFootObservations observations) {
        var full = observations.fullObservation();
        return observations.matches(full.accepted(), full.pose(), full.capture(), full.budget());
    }
    private static Uncertain uncertain(Reason reason, long before, StoredDyadic.Work work) {
        return new Uncertain(reason, Optional.empty(), before, work.units());
    }

    static final class Certificate implements Result {
        private final CapsuleFootContacts.VerifiedFootObservations observations;
        private final int ordinal;
        private final Object workIdentity;
        private final long workDelta;
        private Certificate(CapsuleFootContacts.VerifiedFootObservations observations, int ordinal,
                            Object workIdentity, long workDelta) {
            this.observations = observations; this.ordinal = ordinal;
            this.workIdentity = workIdentity; this.workDelta = workDelta;
        }
        boolean matches(CapsuleFootContacts.VerifiedFootObservations expected, int expectedOrdinal,
                        StoredDyadic.Work sameWork) {
            return observations == expected && ordinal == expectedOrdinal
                    && workIdentity == sameWork.identity() && current(observations);
        }
        int ordinal() { return ordinal; }
        long workDelta() { return workDelta; }
    }
}
