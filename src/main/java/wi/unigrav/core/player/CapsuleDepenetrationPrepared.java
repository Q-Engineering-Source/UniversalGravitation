package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.AxisExitCheck;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.AxisExitCheckKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.AxisExitKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Face;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/** A separate owned phase: only initially penetrating boxes may overlap during the outward path. */
final class CapsuleDepenetrationPrepared {
    private CapsuleDepenetrationPrepared() { }

    static final class Verified {
        private final CapsulePathSource source;
        private final CapsulePlacementMath.Pose end;
        private final CapsulePlacementMath.Translation tube;
        private final Face face;
        private final List<AxisExitCheck> exits;
        private final List<CapsuleTranslationValidator.Query> clearPaths;
        private final List<ContactResult> finalContacts;
        private final double pathUpper;

        private Verified(CapsulePathSource source, CapsulePlacementMath.Pose end,
                CapsulePlacementMath.Translation tube, Face face, List<AxisExitCheck> exits,
                List<CapsuleTranslationValidator.Query> clearPaths, List<ContactResult> finalContacts) {
            this.source = source; this.end = end; this.tube = tube; this.face = face;
            this.exits = List.copyOf(exits); this.clearPaths = List.copyOf(clearPaths);
            this.finalContacts = List.copyOf(finalContacts);
            pathUpper = CapsulePlacementMath.monotoneCenterTravelUpper(
                    source.start().placement().center(), end.placement().center(), source.work());
        }
        CapsulePathSource source() { return source; }
        CapsulePlacementMath.Pose end() { return end; }
        double pathUpper() { return pathUpper; }
        Face face() { return face; }
        boolean matchesBound(CapsulePathSource exact) {
            return source == exact && source.completedSegments() == 0 && source.valid() && source.current()
                    && tube.start() == source.start() && !exits.isEmpty()
                    && exits.stream().allMatch(check -> check.kind() == AxisExitCheckKind.CERTIFIED)
                    && finalContacts.size() == source.capture().boxes().size()
                    && finalContacts.stream().allMatch(contact -> nonpenetrating(contact.relation()));
        }
    }

    private record Route(Face face, CapsulePlacementMath.Translation tube, CapsulePlacementMath.Pose end,
            double distance) { }

    /** Empty means positively observed no initial overlap; every failure is an explicit preparation result. */
    static Optional<ModeTransitionPrepared.Preparation> prepare(FreeOwnerTransaction tx) {
        try {
            tx.requireCurrent();
            var accepted = tx.accepted();
            var start = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
            Aabb initialWindow = accepted.placement().bounds();
            var initialResult = WorldCollisionCandidates.capture(tx, initialWindow,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN,
                    tx.resources().environmentObservationWork());
            if (initialResult instanceof WorldCollisionCandidates.Unavailable failed)
                return blocked("initial capture: " + failed.detail(), failed.cause());
            var initial = (WorldCollisionCandidates.Snapshot) initialResult;
            var overlapping = new ArrayList<Aabb>();
            var deltas = new EnumMap<Face, Vec3d>(Face.class);
            for (Aabb box : initial.boxes()) {
                tx.requireCurrent();
                var contact = tx.queries().query(start.capsule(), box, FreeOwnerTransaction.ACCURACY,
                        "depenetration-initial-solid");
                if (contact.relation() == Relation.UNRESOLVED) return blocked("initial relation unresolved", Optional.empty());
                if (contact.relation() != Relation.OVERLAPPING) continue;
                overlapping.add(box);
                var candidates = tx.queries().axisExitCandidates(start.capsule(), box, FreeOwnerTransaction.ACCURACY);
                if (candidates.kind() == AxisExitKind.UNRESOLVED)
                    return blocked("axis candidates unresolved: " + candidates.reason(), Optional.empty());
                for (var candidate : candidates.candidates()) {
                    Vec3d old = deltas.get(candidate.exitFace());
                    Vec3d delta = candidate.suggestedTranslation();
                    if (old == null || length(delta) > length(old)) deltas.put(candidate.exitFace(), delta);
                }
            }
            if (overlapping.isEmpty()) { tx.requireCurrent(); return Optional.empty(); }
            if (deltas.isEmpty()) return blocked("no conservative outward axis candidate", Optional.empty());
            var routes = new ArrayList<Route>();
            Aabb window = initialWindow;
            for (var candidate : deltas.entrySet()) {
                var tube = CapsulePlacementMath.chargedTranslationTube(start, candidate.getValue(), tx.numeric());
                var end = CapsulePlacementMath.chargedFullTranslation(tube, tx.numeric());
                CapsulePlacementMath.requireWholeTranslationProfile(tube, end, tx.numeric());
                routes.add(new Route(candidate.getKey(), tube, end,
                        CapsulePlacementMath.monotoneCenterTravelUpper(accepted.placement().center(),
                                end.placement().center(), tx.numeric())));
                window = union(window, union(tube.sweptBounds(), end.placement().bounds()));
            }
            routes.sort(Comparator.comparingDouble(Route::distance).thenComparing(Route::face));
            var captured = WorldCollisionCandidates.capture(tx, window,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN,
                    tx.resources().environmentObservationWork());
            if (captured instanceof WorldCollisionCandidates.Unavailable failed)
                return blocked("whole-path capture: " + failed.detail(), failed.cause());
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            var source = CapsulePathSource.actualFree(accepted, start, capture, window,
                    FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
            var starts = new ArrayList<ContactResult>();
            for (Aabb box : capture.boxes()) {
                var contact = tx.queries().query(start.capsule(), box, FreeOwnerTransaction.ACCURACY,
                        "depenetration-captured-start");
                if (contact.relation() == Relation.UNRESOLVED)
                    return blocked("whole-path start unresolved", Optional.empty());
                starts.add(contact);
                if (contact.relation() == Relation.OVERLAPPING && !overlapping.contains(box))
                    return blocked("initial collision observation changed during path capture", Optional.empty());
            }
            if (!capture.boxes().containsAll(overlapping))
                return blocked("initial obstacles disappeared during path capture", Optional.empty());
            for (Route route : routes) {
                tx.requireCurrent();
                var exits = new ArrayList<AxisExitCheck>();
                var clearPaths = new ArrayList<CapsuleTranslationValidator.Query>();
                var finals = new ArrayList<ContactResult>();
                boolean safe = true;
                for (int i = 0; i < capture.boxes().size(); i++) {
                    Aabb box = capture.boxes().get(i);
                    var contact = tx.queries().query(route.end().capsule(), box, FreeOwnerTransaction.ACCURACY,
                            "depenetration-actual-stored-final");
                    finals.add(contact);
                    if (!nonpenetrating(contact.relation())) { safe = false; break; }
                    if (starts.get(i).relation() == Relation.OVERLAPPING) {
                        var exit = tx.queries().checkAxisExit(start.capsule(), box, route.face(),
                                route.end().capsule(), FreeOwnerTransaction.ACCURACY);
                        exits.add(exit);
                        if (exit.kind() != AxisExitCheckKind.CERTIFIED) { safe = false; break; }
                    } else if (!CapsuleTranslationValidator.clearRecoveryObstacle(source, route.tube(), route.end(),
                            box, i, starts.get(i), contact, clearPaths)) { safe = false; break; }
                    tx.requireCurrent();
                }
                if (!safe) continue;
                var proof = new Verified(source, route.end(), route.tube(), route.face(), exits, clearPaths, finals);
                var lineage = CapsulePathSource.continueAfter(proof, tx.numeric());
                var observation = CapsuleEnvironmentObservation.observe(tx, route.end(), tx.resources().environmentObservationWork());
                if (!(observation instanceof CapsuleEnvironmentObservation.Prepared environment))
                    return blocked("final environment unavailable: " + observation, Optional.empty());
                tx.requireCurrent();
                return Optional.of(ModeTransitionPrepared.recovery(tx, proof, lineage, environment));
            }
            return blocked("all conservative outward candidates obstructed or uncertified", Optional.empty());
        } catch (RuntimeException failure) {
            return blocked("preparation failed at the owner boundary", Optional.of(failure));
        }
    }

    private static Optional<ModeTransitionPrepared.Preparation> blocked(String detail, Optional<RuntimeException> cause) {
        return Optional.of(new ModeTransitionPrepared.Unavailable("Free depenetration: " + detail, cause));
    }
    private static boolean nonpenetrating(Relation relation) {
        return relation == Relation.SEPARATED || relation == Relation.TOUCHING;
    }
    private static double length(Vec3d v) { return Math.hypot(Math.hypot(v.x(), v.y()), v.z()); }
    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(new Vec3d(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()), Math.min(a.min().z(), b.min().z())),
                new Vec3d(Math.max(a.max().x(), b.max().x()), Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }
}
