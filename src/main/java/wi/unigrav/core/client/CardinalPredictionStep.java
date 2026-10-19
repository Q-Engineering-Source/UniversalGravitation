package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.CardinalTravelKernel;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.NativeFlightParameters;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Local calculation only: no entity installation, accepted revision, network or native callbacks. */
public final class CardinalPredictionStep {
    private CardinalPredictionStep() { }
    private static final double DT = .05D;

    public enum Mode { NORMAL_DRY, FLUID, GLIDE, FREE, PROPULSION, TRANSITION, NATIVE_BLOCK_EFFECT }
    public record State(PlayerBodyPlacement placement, Vec3d motionBlocksPerTick,
                        PlayerPhysicalSnapshot.Contacts contacts, int jumpTicks) {
        public State {
            Objects.requireNonNull(placement); Objects.requireNonNull(motionBlocksPerTick);
            Objects.requireNonNull(contacts);
            if (jumpTicks < 0) throw new IllegalArgumentException("negative jump cooldown");
        }
    }
    /** All values are explicit samples. jumpSprintImpulse is WORLD blocks/tick, excluding launch. */
    public record Environment(Vec3d worldAcceleration, float friction, float aiMoveSpeed,
                              float airFactor, boolean flying, float flySpeed, double jumpLaunch,
                              Vec3d jumpSprintImpulse, double stepHeight, double ceilingMps,
                              boolean standingReference, boolean sprintAllowed, float yawDegrees, boolean bindingActive,
                              NativeFlightParameters equipmentFlight) {
        public Environment(Vec3d worldAcceleration, float friction, float aiMoveSpeed, float airFactor,
                           boolean flying, float flySpeed, double jumpLaunch, Vec3d jumpSprintImpulse,
                           double stepHeight, double ceilingMps, boolean standingReference,
                           boolean sprintAllowed, float yawDegrees, boolean bindingActive) {
            this(worldAcceleration, friction, aiMoveSpeed, airFactor, flying, flySpeed, jumpLaunch,
                    jumpSprintImpulse, stepHeight, ceilingMps, standingReference, sprintAllowed, yawDegrees,
                    bindingActive, new NativeFlightParameters(flySpeed, 0, false, 0, "capabilities"));
        }
        public Environment(Vec3d worldAcceleration, float friction, float aiMoveSpeed, float airFactor,
                           boolean flying, float flySpeed, double jumpLaunch, Vec3d jumpSprintImpulse,
                           double stepHeight, double ceilingMps, boolean standingReference,
                           boolean sprintAllowed, float yawDegrees) {
            this(worldAcceleration, friction, aiMoveSpeed, airFactor, flying, flySpeed, jumpLaunch,
                    jumpSprintImpulse, stepHeight, ceilingMps, standingReference, sprintAllowed, yawDegrees, false);
        }
        public Environment {
            Objects.requireNonNull(worldAcceleration); Objects.requireNonNull(jumpSprintImpulse);
            Objects.requireNonNull(equipmentFlight);
            if (Float.floatToIntBits(flySpeed) != Float.floatToIntBits(equipmentFlight.flySpeed()))
                throw new IllegalArgumentException("Flight speed differs from retained equipment sample");
            if (!Double.isFinite(ceilingMps) || ceilingMps <= 0 || !Double.isFinite(stepHeight) || stepHeight < 0
                    || !Double.isFinite(jumpLaunch) || !Float.isFinite(friction) || friction < 0
                    || !Float.isFinite(aiMoveSpeed) || aiMoveSpeed < 0 || !Float.isFinite(airFactor) || airFactor < 0
                    || !Float.isFinite(flySpeed) || flySpeed < 0 || !Float.isFinite(yawDegrees))
                throw new IllegalArgumentException("invalid sampled dry travel parameters");
        }
    }
    public sealed interface Result permits Predicted, Rejected, Unsupported { }
    public record Predicted(State pose, CardinalMovementResolver.Work work, boolean jumped) implements Result { }
    public record Rejected(String reason, RuntimeException cause, CardinalMovementResolver.Work work) implements Result { }
    public record Unsupported(Mode mode) implements Result { }

    /** Caller owns this execution-cost ledger across all support, movement and replay calculations. */
    public static final class WorkLedger {
        private final CardinalMovementResolver.Limits limits;
        private int queries, candidates, offsets;
        public WorkLedger(CardinalMovementResolver.Limits limits) { this.limits = Objects.requireNonNull(limits); }
        public CardinalMovementResolver.Work spent() { return new CardinalMovementResolver.Work(queries, candidates, offsets); }
        private CardinalMovementResolver.Result resolve(CardinalMovementResolver.Request request,
                Function<AxisAlignedBB, List<AxisAlignedBB>> query) {
            var result = CardinalMovementResolver.resolve(request, query, limits);
            queries += result.work().queries(); candidates += result.work().candidateObservations();
            offsets += result.work().offsetTests();
            return result;
        }
    }

    public static Result step(State state, Environment environment, PlayerPhysicsProtocol.Controls controls,
                              Mode mode, Function<AxisAlignedBB, List<AxisAlignedBB>> query, WorkLedger ledger) {
        Objects.requireNonNull(state); Objects.requireNonNull(environment); Objects.requireNonNull(controls);
        Objects.requireNonNull(mode); Objects.requireNonNull(query); Objects.requireNonNull(ledger);
        if (mode != Mode.NORMAL_DRY) return new Unsupported(mode);
        if (!(state.placement().shape() instanceof PlayerBodyShape.Cardinal shape)) return new Unsupported(Mode.FREE);
        var frame = shape.frame();
        var bounds = box(state.placement().bounds());
        boolean grounded = state.contacts().onGround();
        if (grounded && environment.standingReference()) {
            var support = ledger.resolve(new CardinalMovementResolver.Request(frame, bounds,
                    frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, true, false, 0), query);
            if (!(support instanceof CardinalMovementResolver.Resolved resolved)) return rejected(support, ledger);
            grounded = resolved.flags().onGround();
        }
        Vec3d old = state.motionBlocksPerTick();
        if (environment.flying()) old = environment.equipmentFlight().beforeTravel(old, grounded, controls);
        float friction = grounded ? environment.friction() : .91F;
        Vec3d motion = new Vec3d(Math.abs(old.x()) < .003D ? 0 : old.x(),
                Math.abs(old.y()) < .003D ? 0 : old.y(), Math.abs(old.z()) < .003D ? 0 : old.z());
        int cooldown = CardinalTravelKernel.tickJumpCooldown(state.jumpTicks(), controls.jump());
        double flightVertical = 0;
        if (environment.flying()) {
            flightVertical = CardinalTravelKernel.flightInput(motion.y(), controls.sneak(), controls.jump(), environment.flySpeed());
            motion = new Vec3d(motion.x(), flightVertical, motion.z());
        }
        boolean jumped = false;
        if (environment.standingReference() || environment.flying()) {
            jumped = CardinalTravelKernel.shouldJump(cooldown, controls.jump(), grounded, environment.flying());
            if (jumped) {
                Vec3d local = frame.toLocal(motion);
                motion = frame.toWorld(new Vec3d(local.x(), environment.jumpLaunch(), local.z()));
                if (controls.sprint() && !controls.sneak() && environment.sprintAllowed())
                    motion = motion.add(environment.jumpSprintImpulse());
                // DE's actual LivingJump handler adds WORLD Y even under a cardinal reference.
                motion = motion.add(new Vec3d(0, environment.equipmentFlight().jumpBonus(), 0));
                cooldown = 10;
            }
            var input = new CardinalTravelKernel.Input(frame, controls.translation(), controls.keyboardTravelGain(),
                    controls.sneak(), controls.jump(), environment.yawDegrees());
            var parameters = new CardinalTravelKernel.Parameters(grounded, friction, environment.aiMoveSpeed(),
                    environment.airFactor(), environment.flying(), environment.flySpeed(),
                    controls.sprint() && !controls.sneak() && environment.sprintAllowed());
            motion = CardinalTravelKernel.accelerate(motion, input, parameters);
        }
        double speed = Math.hypot(Math.hypot(motion.x(), motion.y()), motion.z());
        if (!Double.isFinite(speed)) return new Rejected("non-finite predicted motion", null, ledger.spent());
        double ceiling = environment.ceilingMps() * DT;
        if (speed > ceiling) motion = motion.multiply(ceiling / speed);
        var calculation = ledger.resolve(new CardinalMovementResolver.Request(frame, bounds, motion,
                MoverType.PLAYER, grounded, controls.sneak(), environment.stepHeight()), query);
        if (!(calculation instanceof CardinalMovementResolver.Resolved resolved)) return rejected(calculation, ledger);
        var flags = resolved.flags();
        boolean endpointGrounded = flags.onGround();
        if (!endpointGrounded && environment.bindingActive() && environment.standingReference()
                && !environment.flying() && frame.toLocal(motion).y() == 0) {
            var support = ledger.resolve(new CardinalMovementResolver.Request(frame, resolved.endBounds(),
                    frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, false, false, 0), query);
            if (!(support instanceof CardinalMovementResolver.Resolved contact)) return rejected(support, ledger);
            // Sensor endpoint is never installed; only exact zero-gap support is contact.
            endpointGrounded = contact.flags().onGround() && contact.nativeResolvedLocalDelta().y() == 0;
        }
        Vec3d local = frame.toLocal(motion);
        Vec3d observed = frame.toWorld(new Vec3d(flags.x() ? 0 : local.x(), flags.y() ? 0 : local.y(), flags.z() ? 0 : local.z()));
        Vec3d post = CardinalTravelKernel.finish(observed, frame, environment.worldAcceleration(), DT,
                friction, frame.toLocal(observed).y());
        if (environment.flying()) post = new Vec3d(post.x(), flightVertical * .6D, post.z());
        double nativeSpeed = Math.hypot(Math.hypot(post.x(), post.y()), post.z());
        if (!Double.isFinite(nativeSpeed)) return new Rejected("non-finite predicted movement-tail velocity", null, ledger.spent());
        if (nativeSpeed > ceiling) post = post.multiply(ceiling / nativeSpeed);
        AxisAlignedBB end = resolved.endBounds();
        var actual = new Aabb(new Vec3d(end.minX, end.minY, end.minZ), new Vec3d(end.maxX, end.maxY, end.maxZ));
        var transport = new Vec3d((end.minX + end.maxX) / 2, end.minY, (end.minZ + end.maxZ) / 2);
        var placement = PlayerBodyPlacement.fromCardinalBounds(actual, transport, state.placement().attitude(), shape);
        float fall = environment.flying() || endpointGrounded ? 0 : state.contacts().fallDistance();
        if (!environment.flying() && !endpointGrounded && resolved.nativeResolvedLocalDelta().y() < 0)
            fall = (float) (fall - resolved.nativeResolvedLocalDelta().y());
        return new Predicted(new State(placement, post, new PlayerPhysicalSnapshot.Contacts(endpointGrounded,
                flags.x() || flags.z(), flags.y(), fall), cooldown), ledger.spent(), jumped);
    }

    private static Rejected rejected(CardinalMovementResolver.Result result, WorkLedger ledger) {
        return result instanceof CardinalMovementResolver.Rejected failed
                ? new Rejected("cardinal prediction: " + failed.failure(), failed.cause(), ledger.spent())
                : new Rejected("cardinal prediction execution budget exhausted", null, ledger.spent());
    }
    private static AxisAlignedBB box(Aabb bounds) {
        return new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
    }
}
