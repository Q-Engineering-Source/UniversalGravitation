package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryResult;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import java.util.LinkedHashSet;
import java.util.Optional;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.OriginalPrefixResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import wi.unigrav.api.math.Vec3;

/** Tick-local gait permission only; neither standing nor an installation receipt. */
final class FreeNearFootDrive {
    private FreeNearFootDrive() { }

    record Motion(Vec3 velocity, boolean activeDrive) { }

    /** The original finite gait recipe; computing it does not authorize consuming its result. */
    static Motion gait(Vec3 velocity, Vec3d worldInput, Vec3d bodyUp,
            double targetSpeed, double maximumChange) {
        double inputNormal = worldInput.dot(bodyUp);
        Vec3d tangentInput = worldInput.subtract(bodyUp.multiply(inputNormal));
        double inputLength = Math.sqrt(tangentInput.lengthSquared());
        if (inputLength > 1) tangentInput = tangentInput.multiply(1 / inputLength);
        double normal = velocity.x() * bodyUp.x() + velocity.y() * bodyUp.y() + velocity.z() * bodyUp.z();
        Vec3 tangent = velocity.sub(new Vec3(bodyUp.x(), bodyUp.y(), bodyUp.z()).scale(normal));
        Vec3 desired = new Vec3(tangentInput.x(), tangentInput.y(), tangentInput.z()).scale(targetSpeed);
        Vec3 drive = desired.sub(tangent);
        double change = drive.norm();
        if (change > maximumChange) drive = drive.scale(maximumChange / change);
        return new Motion(velocity.add(drive), inputLength > 0);
    }

    static boolean permitted(FreeOwnerTransaction tx, FreeStandingObservation observation) {
        tx.requireCurrent();
        if (observation.isEndpointObservation() || !observation.matches(tx))
            throw new IllegalStateException("Free gait requires its exact accepted observation");
        var foot = observation.support().foot();
        if (foot.reach() != .25 || !foot.matches(tx.accepted(), observation.pose(),
                foot.fullObservation().capture(), tx.queries()))
            throw new IllegalStateException("Free gait lost its complete quarter-metre foot observation");
        boolean permitted = eventPermission(tx, foot, observation.bodyUp());
        if (!observation.recaptureMatches(tx))
            throw new IllegalStateException("Free near-foot collision observation changed before gait");
        tx.requireCurrent();
        return permitted;
    }

    static boolean permitted(FreeOwnerTransaction tx,
            CertifiedReferenceEndpoint.CardinalRootObservation root,
            CapsuleFootContacts.VerifiedFootObservations foot) {
        root.requireCurrent();
        if (root.endpoint().transaction() != tx || foot.reach() != .25
                || !foot.matches(tx.accepted(), root.pose(), root.capture(), tx.queries())
                || foot.window() != root.queryWindow())
            throw new IllegalStateException("Cardinal gait lost its exact contained-root observation");
        boolean permitted = eventPermission(tx, foot, root.pose().placement().bodyUp());
        var refreshed = WorldCollisionCandidates.capture(tx, root.queryWindow(),
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN,
                tx.resources().environmentObservationWork());
        if (refreshed instanceof WorldCollisionCandidates.Unavailable unavailable)
            throw new IllegalStateException("Cardinal gait recapture unavailable: " + unavailable.reason(),
                    unavailable.cause().orElse(null));
        root.requireCurrent();
        if (!root.capture().sameObservation((WorldCollisionCandidates.Snapshot) refreshed))
            throw new IllegalStateException("Cardinal gait collision observation changed before conversion");
        root.requireCurrent();
        return permitted;
    }

    private static boolean eventPermission(FreeOwnerTransaction tx,
            CapsuleFootContacts.VerifiedFootObservations foot, Vec3d bodyUp) {
        Capsule start = foot.footSphere();
        Vec3d center = start.centerline().start();
        Vec3d delta = bodyUp.multiply(-.25);
        boolean permitted = false;
        ProofInsufficient insufficient = null;
        // VerifiedFootObservations has already completed all mandatory full-body/foot
        // queries. This loop asks only whether one certified gait event exists.
        candidateQueries:
        for (var query : foot.queries()) {
            tx.postprocessing().charge(1);
            tx.requireCurrent();
            if (query.contact().relation() == Relation.TOUCHING && query.contact().witness().isPresent()
                    && CapsuleSupportSlope.evaluate(query.contact().witness().orElseThrow(),
                            bodyUp, tx.numeric()) == CapsuleSupportSlope.SlopeEvidence.SUFFICIENT) {
                permitted = true;
                break candidateQueries;
            }
            if (query.contact().relation() != Relation.SEPARATED) continue;
            // These are finite candidates from this very cast, not a sampled or enlarged reach.
            var candidates = new LinkedHashSet<Double>();
            candidates.add(1.0);
            for (var boundary : query.boundaries()) {
                boundary.prefix().ifPresent(prefix -> candidates.add(prefix.endFraction()));
                boundary.firstObstructionBracket().ifPresent(bracket -> {
                    candidates.add(bracket.lower()); candidates.add(bracket.upper());
                });
            }
            // A cast bracket need not contain a stored TOUCHING endpoint. Faces supply
            // only six further candidates; the exact same contact/bridge gates below
            // remain the sole authority, including for a rounded or irrelevant face.
            double[] origin = {center.x(), center.y(), center.z()};
            double[] movement = {delta.x(), delta.y(), delta.z()};
            double[] low = {query.rawAabb().min().x(), query.rawAabb().min().y(), query.rawAabb().min().z()};
            double[] high = {query.rawAabb().max().x(), query.rawAabb().max().y(), query.rawAabb().max().z()};
            for (int axis = 0; axis < 3; axis++) {
                if (movement[axis] == 0) continue;
                for (int sign : new int[]{-1, 1}) {
                    tx.numeric().charge(5);
                    double face = sign < 0 ? low[axis] : high[axis];
                    double fraction = (face + sign * start.radius() - origin[axis]) / movement[axis];
                    if (Double.isFinite(fraction)) candidates.add(fraction);
                }
            }
            // One adjacent representable fraction per existing candidate, never a search loop.
            // The original parameter must itself pass CHB, even if both round to the same pose.
            var originalCandidates = new LinkedHashSet<>(candidates);
            for (double fraction : originalCandidates) {
                if (Double.isFinite(fraction) && fraction > 0 && fraction <= 1) {
                    tx.numeric().charge(1);
                    candidates.add(Math.nextDown(fraction));
                }
            }
            for (double fraction : candidates) {
                tx.postprocessing().charge(1);
                if (!Double.isFinite(fraction) || fraction <= 0 || fraction > 1) continue;
                try {
                    if (proveCandidate(tx, start, delta, fraction, query.rawAabb(), bodyUp) != null) {
                        permitted = true;
                        break candidateQueries;
                    }
                } catch (ProofInsufficient rejected) {
                    // Only a finite candidate's missing exact proof may be followed by another
                    // existing candidate. Query/context/numeric budget failures are never caught.
                    if (insufficient == null) insufficient = rejected;
                    else insufficient.addSuppressed(rejected);
                }
            }
        }
        tx.requireCurrent();
        if (!permitted && insufficient != null) throw insufficient;
        return permitted;
    }

    /** A diagnostic view of the genuine CHB receipt, not standing or installation authority. */
    static final class EventProof {
        private final Optional<BoundaryResult> boundary;
        private final Optional<OriginalPrefixResult> originalPrefix;
        private EventProof(BoundaryResult boundary) {
            this.boundary = Optional.of(boundary); this.originalPrefix = Optional.empty();
        }
        private EventProof(OriginalPrefixResult originalPrefix) {
            this.boundary = Optional.empty(); this.originalPrefix = Optional.of(originalPrefix);
        }
        BoundaryResult boundary() { return boundary.orElseThrow(); }
        Optional<OriginalPrefixResult> originalPrefix() { return originalPrefix; }
    }
    static final class ProofInsufficient extends IllegalStateException {
        private ProofInsufficient(String detail) { super("Free near-foot proof insufficient: " + detail); }
    }
    static final class QueryUnavailable extends IllegalStateException {
        private final Object evidence;
        private QueryUnavailable(String stage, Object evidence) {
            super("Free near-foot " + stage + " unresolved: " + evidence); this.evidence = evidence;
        }
        Object evidence() { return evidence; }
    }

    /** Test-visible genuine candidate path; its result cannot authorize movement outside eventPermission. */
    static EventProof proveCandidate(FreeOwnerTransaction tx, Capsule start, Vec3d delta,
            double fraction, Aabb rawAabb, Vec3d bodyUp) {
        tx.requireCurrent();
        if (!Double.isFinite(fraction) || fraction <= 0 || fraction > 1)
            throw new IllegalArgumentException("near-foot candidate lies outside its original finite cast");
        if (!StoredRealizationLimits.sameRaw(start.centerline().start(), start.centerline().end()))
            throw new IllegalArgumentException("near-foot exact subcast requires the actual point-centerline sphere");
        tx.numeric().charge(6); // Three stored multiplies, then three stored center additions.
        Vec3d dSub = delta.multiply(fraction);
        Vec3d center = start.centerline().start();
        Vec3d at = center.add(dSub);
        Capsule sphere = new Capsule(new Segment3d(at, at), start.radius());
        var actual = tx.queries().query(sphere, rawAabb, FreeOwnerTransaction.ACCURACY);
        tx.requireCurrent();
        if (actual.relation() == Relation.UNRESOLVED) throw new QueryUnavailable("event contact", actual);
        if (actual.relation() != Relation.TOUCHING || actual.witness().isEmpty()
                || CapsuleSupportSlope.evaluate(actual.witness().orElseThrow(), bodyUp, tx.numeric())
                    != CapsuleSupportSlope.SlopeEvidence.SUFFICIENT) return null;
        if (!exactSubpath(center, delta, fraction, dSub, at, tx.numeric())) {
            var candidate = new StoredInstallCandidate(fraction, center, at, new Vec3d(0, 0, 0), sphere);
            var original = tx.queries().proveOriginalTranslationPrefix(start, delta, fraction,
                    rawAabb, candidate, FreeOwnerTransaction.ACCURACY);
            tx.requireCurrent();
            if (original.startRelation() == Relation.UNRESOLVED
                    || original.reason().filter(reason -> reason != Reason.NO_PREFIX_CERTIFICATE
                            && reason != Reason.ROUNDED_INSTALL_UNSAFE
                            && reason != Reason.CORRECTION_PATH_UNPROVED).isPresent())
                throw new QueryUnavailable("original prefix", original);
            var proof = original.proof().orElseThrow(() -> new ProofInsufficient(
                    "not an exact subpath and original prefix not certified: " + original));
            var installed = proof.installation();
            if (proof.startCapsule() != start || proof.originalDelta() != delta
                    || proof.obstacle() != rawAabb || proof.candidate() != candidate
                    || !StoredRealizationLimits.sameRaw(proof.endFraction(), fraction)
                    || !StoredRealizationLimits.sameRaw(proof.prefix().endFraction(), fraction)
                    || !StoredRealizationLimits.sameRaw(installed.fraction(), fraction)
                    || installed.storedCapsule() != sphere
                    || !StoredRealizationLimits.sameRaw(installed.storedCenter(), at)
                    || !StoredRealizationLimits.sameRaw(installed.halfLine(), new Vec3d(0, 0, 0))
                    || installed.storedContact().relation() != Relation.TOUCHING
                    || installed.storedContact().witness().isEmpty()
                    || CapsuleSupportSlope.evaluate(installed.storedContact().witness().orElseThrow(),
                            bodyUp, tx.numeric()) != CapsuleSupportSlope.SlopeEvidence.SUFFICIENT)
                throw new ProofInsufficient("original prefix receipt lost its exact candidate identity: " + original);
            tx.requireCurrent();
            return new EventProof(original);
        }
        // Exact dSub=t*delta proves the continuous subcast parameter s maps to u=t*s.
        // at is the actual RN(center+dSub), not an exact affine endpoint. CHB must
        // validate that stored identity AND prove the entire ideal-to-stored bridge;
        // its successful prefix/installation receipt below is mandatory. Static touch
        // alone never substitutes for either proof, even when CHB rejects RN residuals.
        var bridge = tx.queries().refineTranslationBoundary(start, dSub, rawAabb,
                new BoundarySearchHint(0, 1),
                new StoredInstallCandidate(1, center, at, new Vec3d(0, 0, 0), sphere),
                FreeOwnerTransaction.ACCURACY);
        tx.requireCurrent();
        if (bridge.kind() == BoundaryKind.UNRESOLVED) throw new QueryUnavailable("exact subcast", bridge);
        boolean prefix = bridge.prefix().isPresent() && bridge.prefix().orElseThrow().endFraction() >= 1;
        boolean installed = bridge.installation().filter(receipt -> receipt.fraction() == 1
                && StoredRealizationLimits.sameRaw(receipt.storedCenter(), at)
                && StoredRealizationLimits.sameRaw(receipt.halfLine(), new Vec3d(0, 0, 0))
                && receipt.storedCapsule().equals(sphere)
                && StoredRealizationLimits.sameRaw(receipt.storedCapsule().radius(), start.radius())
                && receipt.storedContact().relation() == Relation.TOUCHING
                && receipt.storedContact().witness().isPresent()
                && CapsuleSupportSlope.evaluate(receipt.storedContact().witness().orElseThrow(), bodyUp, tx.numeric())
                    == CapsuleSupportSlope.SlopeEvidence.SUFFICIENT).isPresent();
        tx.requireCurrent();
        if (!prefix || !installed) throw new ProofInsufficient("exact subcast did not certify its whole prefix: " + bridge);
        return new EventProof(bridge);
    }

    /** Exact continuous-subpath product lemma, using the existing monotonic numeric Work.
     * center/at retain the existing caller signature; no exact endpoint-addition premise is imposed.
     * CHB's stored-candidate identity and full correction-bridge receipt remain mandatory. */
    static boolean exactSubpath(Vec3d center, Vec3d delta, double fraction, Vec3d dSub, Vec3d at,
            StoredDyadic.Work work) {
        if (!Double.isFinite(fraction) || fraction <= 0 || fraction > 1) return false;
        var t = StoredDyadic.of(fraction, work);
        double[] d = {delta.x(), delta.y(), delta.z()}, sub = {dSub.x(), dSub.y(), dSub.z()};
        for (int axis = 0; axis < 3; axis++) {
            var exactSub = StoredDyadic.of(sub[axis], work);
            var product = t.multiply(StoredDyadic.of(d[axis], work), work);
            work.charge(1); // Exact continuous-subpath product equality; stored RN identity belongs to CHB.
            if (!exactSub.equals(product)) return false;
        }
        return true;
    }
}
