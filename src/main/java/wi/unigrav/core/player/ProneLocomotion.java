package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.Optional;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.enchantment.EnchantmentHelper;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;

/** Same-owner prone selection and drive. Geometry admission and installation stay in the existing mode pipeline. */
final class ProneLocomotion {
    private ProneLocomotion() { }

    record Choice(Optional<ProneTransitionRequest> request, boolean blocksStanding) {
        static final Choice NONE = new Choice(Optional.empty(), false);
    }
    record WaterMotion(Vec3 velocity, double tangentDrag, double normalDrag, Vec3d up) { }

    record StandingProfile(FreeOwnerTransaction transaction, NativeCardinalBodyProfile.Value body,
            ProneBodyProducer producer, ProneBodyProducer.Input input) {
        PlayerBodyShape.Dimensions dimensions() {
            return new PlayerBodyShape.Dimensions(body.physical().diameter(), body.physical().length(), input.standingEyeHeight());
        }
        boolean current() {
            transaction.requireOwnerFence();
            var observed = producer.unigrav$proneBodyInput();
            boolean result = body.current() && observed.isPresent()
                    && Float.floatToIntBits(observed.orElseThrow().standingEyeHeight()) == Float.floatToIntBits(input.standingEyeHeight())
                    && Float.floatToIntBits(observed.orElseThrow().standingRawEyeHeight()) == Float.floatToIntBits(input.standingRawEyeHeight());
            transaction.requireOwnerFence();
            return result;
        }
    }

    static StandingProfile standingProfile(FreeOwnerTransaction tx) {
        tx.requireCurrent();
        return standingProfileForInstallation(tx);
    }

    static StandingProfile standingProfileForInstallation(FreeOwnerTransaction tx) {
        tx.requireOwnerFence();
        if (!(tx.accepted().placement().shape() instanceof PlayerBodyShape.FreeCapsule free) || free.pronePose().isEmpty())
            return null;
        if (!(tx.actor() instanceof ProneBodyProducer producer))
            throw new IllegalStateException("Prone standing restoration lost its native eye producer");
        var body = NativeCardinalBodyProfile.sample(tx.actor()).orElseThrow(
                () -> new IllegalStateException("Prone standing restoration lost its full physical body producer"));
        var input = producer.unigrav$proneBodyInput().orElseThrow(
                () -> new IllegalStateException("Prone standing restoration lost its full eye input"));
        if (!free.collisionProfile().equals(body.physical()))
            throw new IllegalStateException("Prone standing restoration requires physical resize before standing");
        var result = new StandingProfile(tx, body, producer, input);
        if (!result.current()) throw new IllegalStateException("Prone standing input changed during observation");
        return result;
    }

    static Choice choose(FreeOwnerTransaction tx, PlayerPhysicsStep.TravelState travel,
            PlayerPhysicsProtocol.Controls controls, boolean water) {
        tx.requireCurrent();
        var actor = tx.actor();
        if (!(actor instanceof ProneBodyProducer producer)) return Choice.NONE;
        var body = NativeCardinalBodyProfile.sample(actor);
        var input = producer.unigrav$proneBodyInput();
        if (body.isEmpty() || input.isEmpty()) return Choice.NONE;
        var placement = tx.accepted().placement();
        var current = placement.shape() instanceof PlayerBodyShape.FreeCapsule free
                ? free.pronePose() : Optional.<PlayerBodyShape.PronePose>empty();
        if (placement.shape() instanceof PlayerBodyShape.FreeCapsule free
                && !free.collisionProfile().equals(body.orElseThrow().physical())) return Choice.NONE;
        var frame = placement.shape() instanceof PlayerBodyShape.Cardinal cardinal ? cardinal.frame()
                : current.map(PlayerBodyShape.PronePose::referenceFrame)
                    .orElseGet(() -> travel.ownerReference() instanceof PlayerBodyReference.FreeReference reference
                            ? reference.previousEffective().map(PlayerBodyReference.Effective::frame).orElse(CardinalFrame.Y_POS)
                            : CardinalFrame.Y_POS);
        boolean flight = travel.mobility().blocksCapture() || actor.capabilities.isFlying || actor.isElytraFlying();
        Optional<PlayerBodyShape.PronePose> selected;
        if (flight) {
            if (current.isEmpty()) return Choice.NONE;
            selected = Optional.empty();
        } else {
            selected = selectPose(actor, input.orElseThrow(), current, frame, false, water, controls.sprint(), false);
            if (selected.isEmpty() && (placement.shape() instanceof PlayerBodyShape.Cardinal || current.isPresent())
                    && !standingRoom(tx, body.orElseThrow().physical(), input.orElseThrow().standingEyeHeight(),
                            frame, placement.shape() instanceof PlayerBodyShape.Cardinal ? controls.translation() : new Vec3d(0, 0, 0)))
                selected = selectPose(actor, input.orElseThrow(), current, frame, false, water, controls.sprint(), true);
            if (selected.isEmpty()) return Choice.NONE;
        }
        Rotation3d requested = selected.isEmpty() ? controls.desiredBodyAttitude().orElse(placement.attitude())
                : attitude(controls.forward(), placement.attitude(), selected.orElseThrow());
        var request = new ProneTransitionRequest(tx, body.orElseThrow(), producer, input.orElseThrow(), selected, requested);
        if (!request.current()) throw new IllegalStateException("Prone selection lost its native body input");
        return new Choice(Optional.of(request), true);
    }

    /** Shared current-mode selection; a saved pose is never an already-swimming permission. */
    static Optional<PlayerBodyShape.PronePose> selectPose(EntityPlayerMP actor, ProneBodyProducer.Input input,
            Optional<PlayerBodyShape.PronePose> current, CardinalFrame frame,
            boolean flight, boolean water, boolean sprint, boolean standingBlocked) {
        if (flight) return Optional.empty();
        boolean swimming = water && sprint
                && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6)
                && (input.eyesInWater() || current.filter(pose ->
                        pose.kind() == PlayerBodyShape.PronePose.Kind.SWIMMING).isPresent());
        if (swimming) return Optional.of(new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.SWIMMING, frame));
        return input.forcedCrawl() || standingBlocked
                ? Optional.of(new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.CRAWLING, frame)) : Optional.empty();
    }

    /** Full-height query for intent selection only; a clear result never grants standing installation. */
    static boolean standingRoom(FreeOwnerTransaction tx, PlayerBodyShape.CollisionProfile profile,
            double eye, CardinalFrame frame, Vec3d approach) {
        tx.requireCurrent();
        var placement = tx.accepted().placement();
        var dimensions = new PlayerBodyShape.Dimensions(profile.diameter(), profile.length(), eye);
        PlayerBodyPlacement standing;
        if (placement.shape() instanceof PlayerBodyShape.Cardinal) standing = placement.proposeDimensionsAtFeet(dimensions);
        else {
            var foot = PlayerBodyPlacement.proneSupportFoot(placement, frame, tx.numeric());
            var center = foot.add(frame.up().multiply(profile.length() / 2));
            tx.numeric().charge(7);
            standing = new PlayerBodyPlacement(center, frame.attitude(), new PlayerBodyShape.Cardinal(frame, dimensions));
        }
        if (!clear(tx, standing.bounds())) return false;
        Vec3d tangent = approach.subtract(frame.up().multiply(approach.dot(frame.up())));
        tx.numeric().charge(10);
        double magnitude = Math.sqrt(tangent.lengthSquared());
        if (!(magnitude > 0)) return true;
        // Begin lowering before the native standing box reaches a low opening. The full prone
        // body and its complete turn still have to fit; an ordinary wall cannot authorize clipping.
        var delta = tangent.multiply(profile.diameter() / magnitude);
        tx.numeric().charge(10);
        return clear(tx, new Aabb(standing.bounds().min().add(delta), standing.bounds().max().add(delta)));
    }

    private static boolean clear(FreeOwnerTransaction tx, Aabb box) {
        var result = WorldCollisionCandidates.capture(tx, box, CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT,
                ModeTransitionPrepared.SCAN, tx.resources().environmentObservationWork());
        tx.requireCurrent();
        if (result instanceof WorldCollisionCandidates.Unavailable failure)
            throw new IllegalStateException("Prone standing-space observation unavailable: "
                    + failure.reason() + "; " + failure.detail(), failure.cause().orElse(null));
        return ((WorldCollisionCandidates.Snapshot) result).boxes().isEmpty();
    }

    static Rotation3d attitude(Vec3d look, Rotation3d accepted, PlayerBodyShape.PronePose pose) {
        var up = pose.referenceFrame().up();
        Vec3d head = pose.kind() == PlayerBodyShape.PronePose.Kind.CRAWLING
                ? look.subtract(up.multiply(look.dot(up))) : look;
        if (head.lengthSquared() < 1.0e-12) {
            head = accepted.basisY().subtract(up.multiply(accepted.basisY().dot(up)));
            if (head.lengthSquared() < 1.0e-12) head = pose.referenceFrame().toWorld(new Vec3d(0, 0, 1));
        }
        head = head.multiply(1 / Math.sqrt(head.lengthSquared()));
        Vec3d back = up.subtract(head.multiply(up.dot(head)));
        if (back.lengthSquared() < 1.0e-12) {
            back = accepted.basisZ().multiply(-1);
            back = back.subtract(head.multiply(back.dot(head)));
            if (back.lengthSquared() < 1.0e-12) back = pose.referenceFrame().toWorld(new Vec3d(0, 0, 1));
        }
        var quaternion = PlayerRenderPoseProtocol.Quaternion.fromUpForward(head, back.multiply(-1));
        return new Rotation3d(quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
    }

    /** Actual full-body contact, evaluated against support-up rather than the capsule's head axis. */
    static boolean bellySupported(FreeOwnerTransaction tx, FreeStandingObservation observation, CardinalFrame frame) {
        tx.requireCurrent();
        var full = observation.support().foot().fullObservation();
        if (!observation.matches(tx) || observation.isEndpointObservation()
                || !full.matches(tx.accepted(), observation.pose(), full.capture(), tx.queries()))
            throw new IllegalStateException("Crawl support lost its current full-body observation");
        boolean supported = false;
        for (var query : full.queries()) {
            tx.postprocessing().charge(1);
            if (query.contact().relation() == Relation.TOUCHING && query.contact().witness().isPresent()
                    && CapsuleSupportSlope.evaluate(query.contact().witness().orElseThrow(), frame.up(), tx.numeric())
                        == CapsuleSupportSlope.SlopeEvidence.SUFFICIENT) supported = true;
        }
        if (!observation.recaptureMatches(tx))
            throw new IllegalStateException("Crawl belly support changed before drive");
        tx.requireCurrent();
        return supported;
    }

    static WaterMotion waterMotion(FreeOwnerTransaction tx, PlayerPhysicsProtocol.Controls controls,
            Vec3 velocity, CardinalFrame reference) {
        var actor = tx.actor();
        float drag = controls.sprint() ? .9F : actor.getWaterSlowDown();
        float coefficient = .02F;
        float depth = Math.min(3, EnchantmentHelper.getDepthStriderModifier(actor));
        if (!actor.onGround) depth *= .5F;
        if (depth > 0) {
            drag += (.54600006F - drag) * depth / 3;
            coefficient += (actor.getAIMoveSpeed() - coefficient) * depth / 3;
        }
        tx.requireCurrent();
        double dt = GravityEntityBinder.TICK_SECONDS;
        var input = controls.translation();
        var driven = velocity.add(new Vec3(input.x(), input.y(), input.z()).scale(coefficient / dt));
        double vertical = (controls.jump() ? 1 : 0) - (controls.sneak() ? 1 : 0);
        var up = reference.up();
        driven = driven.add(new Vec3(up.x(), up.y(), up.z()).scale(vertical * .04 / dt));
        return new WaterMotion(driven, drag, .8, up);
    }

    static Vec3 dampWater(Vec3 velocity, WaterMotion motion) {
        var up = motion.up();
        double normal = velocity.x() * up.x() + velocity.y() * up.y() + velocity.z() * up.z();
        var perpendicular = new Vec3(up.x(), up.y(), up.z()).scale(normal);
        return velocity.sub(perpendicular).scale(motion.tangentDrag()).add(perpendicular.scale(motion.normalDrag()));
    }
}
