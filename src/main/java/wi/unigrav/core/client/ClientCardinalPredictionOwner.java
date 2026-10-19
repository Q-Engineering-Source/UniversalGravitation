package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.player.CardinalPredictionSeed;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsStep;
import wi.unigrav.core.player.PlayerPredictionTiming;
import wi.unigrav.UnigravMod;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Function;

/** One exact accepted owner lifetime. Authority and scratch prediction never share a revision owner. */
final class ClientCardinalPredictionOwner {
    record EndpointValidation(boolean accepted, String reason, Optional<PlayerPhysicsProtocol.PredictionRefusal> refusal, String support, Optional<Boolean> refusalGround) {
        EndpointValidation(boolean accepted,String reason) { this(accepted,reason,Optional.empty(),"NOT_OBSERVED",Optional.empty()); }
        static EndpointValidation refused(wi.unigrav.core.player.CardinalPredictionSeed.Reason reason,
                PlayerPhysicsProtocol.PredictionRefusalStage stage,String support) {
            return new EndpointValidation(false,reason.name(),Optional.of(new PlayerPhysicsProtocol.PredictionRefusal(reason,stage)),support,Optional.empty());
        }
        EndpointValidation withGround(boolean ground) {
            return new EndpointValidation(accepted,reason,refusal,support,Optional.of(ground));
        }
        EndpointValidation atStage(PlayerPhysicsProtocol.PredictionRefusalStage stage) {
            return new EndpointValidation(accepted,reason,refusal.map(value->new PlayerPhysicsProtocol.PredictionRefusal(value.reason(),stage)),support,refusalGround);
        }
        static EndpointValidation allowed() { return new EndpointValidation(true, ""); }
        static EndpointValidation rejected(String reason) { return new EndpointValidation(false, reason); }
    }
    record Input(PlayerPhysicsProtocol.PredictionInput identity, PlayerPhysicsProtocol.Controls controls,
                 CardinalPredictionStep.Environment environment) { }
    private final Object lifetime;
    private final EntityPlayer actor;
    private final net.minecraft.world.World world;
    private final long epoch;
    private final int capacity;
    private PlayerPhysicalSnapshot authority;
    private CardinalPredictionSeed.Ready seed;
    private CardinalPredictionStep.State state;
    private CardinalPredictionStep.State projectionState;
    private PlayerPhysicalSnapshot projectionAuthority;
    private PlayerPhysicalSnapshot projection;
    private PlayerPredictionTiming.History<Input> history;
    private long firstTick = -1, lastTick = -1, jumpEdge;
    private boolean jumpHeld;
    private long resolvedTick = -1;
    private String retiredReason;
    private EndpointValidation refusal;
    Optional<EndpointValidation> refusal() { return Optional.ofNullable(refusal); }
    PlayerPhysicsProtocol.PredictionBootstrap bootstrap() { return bootstrap; }
    long firstTick() { return firstTick; }
    long lastTick() { return lastTick; }
    /** Physical-owner lifetime work is retained even when a new prediction epoch begins in this tick. */
    void inheritSuspendedInput(ClientCardinalPredictionOwner previous, boolean heldJump) {
        ledger=previous.ledger; ledgerTick=previous.ledgerTick; jumpHeld=heldJump;
    }
    void refuse(EndpointValidation refusal) { this.refusal=refusal; retire(refusal.reason()); }

    private CardinalPredictionStep.WorkLedger ledger;
    private long ledgerTick = Long.MIN_VALUE;
    private Vec3d previousEye;
    private final PlayerPhysicsProtocol.PredictionBootstrap bootstrap;
    private long firstWireSequence = -1;
    private final LinkedHashMap<Long, Long> submittedInputTicks = new LinkedHashMap<>();
    private long acknowledgedInputSequence = -1, acknowledgedInputTick = -1;
    private record Release(long sequence, long clientTick, Vec3d predictedCenter, Vec3d authorityCenter) { }
    private Vec3d lastSubmittedTranslation;
    private Release release;
    private long releaseCount, lastReleaseProbeTick = Long.MIN_VALUE;
    private PlayerPhysicsProtocol.Packet reconciliationAttempt;
    private long latestAwaitingRevision;
    private boolean anchored;
    private PlayerPhysicsProtocol.Packet lastReconciledPacket;

    ClientCardinalPredictionOwner(Object lifetime, EntityPlayer actor, long epoch,
                                  PlayerPhysicsProtocol.Packet initial) {
        var authority = initial.snapshot().orElseThrow();
        if (!(initial.predictionSeed().orElse(null) instanceof CardinalPredictionSeed.Ready seed)
                || !(initial.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.AwaitingFirstResolvedSlot awaiting))
            throw new IllegalArgumentException("New prediction owner requires a proven awaiting checkpoint; anchored needs existing history");
        this.bootstrap = awaiting.bootstrap();
        latestAwaitingRevision = authority.revision();
        lastReconciledPacket = initial;
        this.lifetime = lifetime; this.actor = actor; this.world = actor.world; this.epoch = epoch;
        this.authority = authority; this.seed = seed;
        // Every real dry step consumes at least one query. Retained/replay slots derive from the existing MOVE ceiling.
        capacity = PlayerPhysicsStep.cardinalMovementLimits().maxQueries();
        state = fromAuthority(authority, awaiting.jumpCooldown());
        previousEye = state.placement().eye();
    }
    boolean current(Object exactLifetime, EntityPlayer exactActor) {
        return retiredReason == null && lifetime == exactLifetime && actor == exactActor
                && actor.world == world && world != null && !actor.isDead;
    }
    boolean active() { return retiredReason == null; }
    String retiredReason() { return retiredReason; }
    void retire(String reason) {
        if (retiredReason == null) {
            traceReconciliation(reconciliationAttempt, state.placement().center(), "retire:" + reason, true);
            retiredReason = reason;
        }
        if (history != null) history.retire();
        submittedInputTicks.clear();
    }
    private CardinalPredictionStep.WorkLedger ledger(long clientTick) {
        if (clientTick != ledgerTick) {
            ledger = new CardinalPredictionStep.WorkLedger(PlayerPhysicsStep.cardinalMovementLimits());
            ledgerTick = clientTick;
        }
        return ledger;
    }
    Optional<PlayerPhysicsProtocol.PredictionInput> predict(long clientTick, PlayerPhysicsProtocol.Controls controls,
            CardinalPredictionStep.Environment environment, Function<AxisAlignedBB, List<AxisAlignedBB>> collisions,
            Function<CardinalPredictionStep.State, EndpointValidation> endpoint) {
        if (!active()) return Optional.empty();
        if (clientTick == lastTick) throw new IllegalStateException("Prediction slot already consumed");
        if (firstTick >= 0 && lastTick >= firstTick && clientTick != lastTick + 1) {
            retire("Prediction slots are not contiguous"); return Optional.empty();
        }
        if (firstTick >= 0 && lastTick >= firstTick && lastTick - Math.max(resolvedTick, firstTick - 1) >= capacity) {
            retire("Prediction history capacity exhausted; rebase required"); return Optional.empty();
        }
        long nextFirstTick = firstTick < 0 ? clientTick : firstTick;
        long nextJumpEdge = controls.jump() && !jumpHeld ? Math.incrementExact(jumpEdge) : jumpEdge;
        var identity = new PlayerPhysicsProtocol.PredictionInput(epoch, nextFirstTick, clientTick,
                controls.jump() ? nextJumpEdge : 0, Optional.of(bootstrap));
        var next = calculate(state, controls, environment, collisions, clientTick);
        if (next == null) return Optional.empty();
        if (!validateEndpoint(endpoint, next)) return Optional.empty();
        if (firstTick < 0) {
            firstTick = nextFirstTick;
            history = new PlayerPredictionTiming.History<>(epoch, firstTick, capacity, capacity);
        }
        jumpEdge = nextJumpEdge; jumpHeld = controls.jump();
        previousEye = state.placement().eye(); state = next;
        history.append(clientTick, new Input(identity, controls, environment));
        lastTick = clientTick;
        return Optional.of(identity);
    }
    /** Captured only after this exact packet returns normally from the real network send boundary. */
    void inputSendSucceeded(PlayerPhysicsProtocol.Packet packet) {
        var input = packet.predictionInput().orElseThrow();
        if (!active() || !packet.scope().equals(authority.scope()) || !input.bootstrap().filter(bootstrap::equals).isPresent()
                || input.epoch() != epoch || input.firstClientTick() != firstTick)
            throw new IllegalArgumentException("Submitted INPUT differs from prediction bootstrap");
        if (firstWireSequence < 0) {
            if (input.clientTick() != firstTick) throw new IllegalArgumentException("First prediction wire payload was lost");
            firstWireSequence = packet.sequence();
        }
        if (submittedInputTicks.size() == capacity) {
            retire("Submitted input history capacity exhausted");
            return;
        }
        submittedInputTicks.put(packet.sequence(), input.clientTick());
        Vec3d translation = packet.controls().orElseThrow().translation();
        if (lastSubmittedTranslation != null && lastSubmittedTranslation.lengthSquared() != 0
                && translation.lengthSquared() == 0) {
            release = new Release(packet.sequence(), input.clientTick(), state.placement().center(), authority.placement().center());
            releaseCount++;
        }
        lastSubmittedTranslation = translation;
    }
    boolean reconcile(PlayerPhysicsProtocol.Packet packet, long clientTick,
            Function<AxisAlignedBB, List<AxisAlignedBB>> collisions,
            Function<CardinalPredictionStep.State, EndpointValidation> endpoint) {
        if (!active()) return false;
        reconciliationAttempt = packet;
        Vec3d predictedBefore = state.placement().center();
        if (lastReconciledPacket != null && packet.sequence() <= lastReconciledPacket.sequence()) {
            if (packet.equals(lastReconciledPacket)) return true;
            retire("Stale or conflicting prediction checkpoint"); return false;
        }
        var freshSeed = packet.predictionSeed().orElse(null);
        if (!(freshSeed instanceof CardinalPredictionSeed.Ready ready)) {
            var reason=freshSeed instanceof CardinalPredictionSeed.Unsupported unsupported ? unsupported.reason()
                    : freshSeed instanceof CardinalPredictionSeed.Unavailable unavailable ? unavailable.reason() : CardinalPredictionSeed.Reason.UNSUPPORTED_STATE;
            if (freshSeed instanceof CardinalPredictionSeed.Unavailable unavailable && unavailable.cause().isPresent()) throw unavailable.cause().orElseThrow();
            refuse(EndpointValidation.refused(reason,PlayerPhysicsProtocol.PredictionRefusalStage.REPLAY,"NOT_OBSERVED")); return false;
        }
        var accepted = packet.snapshot().orElseThrow();
        if (!accepted.scope().equals(authority.scope())) { retire("Prediction scope changed"); return false; }
        if (ready.frame() != seed.frame()) { retire("Prediction frame changed"); return false; }
        var phase = packet.predictionPhase().orElse(null);
        var boundary = packet.predictionBoundary().orElse(null);
        int cooldown;
        PlayerPredictionTiming.Replay<Input> replay = new PlayerPredictionTiming.Ready<>(List.of());
        if (phase instanceof PlayerPhysicsProtocol.AwaitingFirstResolvedSlot awaiting) {
            if (anchored) { retire("Prediction awaiting phase regressed after anchor"); return false; }
            if (!bootstrap.equals(awaiting.bootstrap())) { retire("Prediction bootstrap changed"); return false; }
            cooldown = awaiting.jumpCooldown();
            if (history != null) replay = history.beforeFirstBoundary();
        } else if (phase instanceof PlayerPhysicsProtocol.Anchored checkpoint && boundary != null) {
            if (!bootstrap.equals(checkpoint.bootstrap())) { retire("Prediction bootstrap changed"); return false; }
            if (history == null || firstWireSequence < 0) { retire("Prediction anchor has no submitted local history"); return false; }
            if (boundary.firstInputSequence() != firstWireSequence) { retire("Prediction first wire sequence changed"); return false; }
            long resolved = PlayerPredictionTiming.resolvedClientTick(boundary);
            Long submittedTick = boundary.heldInputSequence() == acknowledgedInputSequence
                    ? Long.valueOf(acknowledgedInputTick) : submittedInputTicks.get(boundary.heldInputSequence());
            if (submittedTick == null || submittedTick.longValue() != resolved) {
                retire("Prediction acknowledgement does not identify the submitted input tick"); return false;
            }
            if (boundary.preSnapshotRevision() < latestAwaitingRevision || boundary.preSnapshotRevision() > accepted.revision()) { retire("Prediction anchor precedes awaiting authority"); return false; }
            cooldown = boundary.jumpCooldown();
            replay = history.after(boundary);
        } else { retire("Prediction boundary missing or phase unavailable"); return false; }
        if (replay instanceof PlayerPredictionTiming.NeedsRebase<Input> failed) { retire(failed.reason()); return false; }
        var scratch = fromAuthority(accepted, cooldown);
        for (var slot : ((PlayerPredictionTiming.Ready<Input>) replay).slots()) {
            scratch = calculate(scratch, slot.state().controls(), slot.state().environment(), collisions, clientTick);
            if (scratch == null) return false;
            if (!validateEndpoint(endpoint, scratch)) return false;
        }
        if (boundary != null) {
            anchored = true; resolvedTick = PlayerPredictionTiming.resolvedClientTick(boundary);
            acknowledgedInputSequence = boundary.heldInputSequence(); acknowledgedInputTick = resolvedTick;
            submittedInputTicks.entrySet().removeIf(entry -> entry.getKey() <= acknowledgedInputSequence);
        }
        else latestAwaitingRevision = Math.max(latestAwaitingRevision, accepted.revision());
        authority = accepted; seed = ready; state = scratch; lastReconciledPacket = packet;
        traceReconciliation(packet, predictedBefore, "reconciled", false);
        reconciliationAttempt = null;
        return true;
    }

    /** Only observes existing input and result values; never polls keys or executes another physics query. */
    private void traceReconciliation(PlayerPhysicsProtocol.Packet packet, Vec3d predictedBefore,
            String disposition, boolean terminal) {
        if (release == null || !UnigravMod.LOGGER.isInfoEnabled()) return;
        long tick = actor.world == null ? lastTick : actor.world.getTotalWorldTime();
        if (!terminal && lastReleaseProbeTick != Long.MIN_VALUE && tick >= lastReleaseProbeTick
                && tick - lastReleaseProbeTick < 20) return;
        var boundary = packet == null ? null : packet.predictionBoundary().orElse(null);
        var canonical = packet == null ? authority : packet.snapshot().orElse(authority);
        UnigravMod.LOGGER.info("ug-prediction-edge scope={} bootstrap={} epoch={} revision={} clientHead={}"
                        + " releaseSeq={} releaseTick={} releaseCount={} releasePredictedCenter={} releaseAuthorityCenter={}"
                        + " checkpointSeq={} boundary={} acknowledgedInputSeq={} acknowledgedInputTick={} submittedPending={}"
                        + " predictedBefore={} canonicalCenter={} predictedAfter={} disposition={}",
                authority.scope(), bootstrap, epoch, canonical.revision(), lastTick,
                release.sequence(), release.clientTick(), releaseCount, release.predictedCenter(), release.authorityCenter(),
                packet == null ? -1 : packet.sequence(), boundary, acknowledgedInputSequence, acknowledgedInputTick,
                submittedInputTicks.size(), predictedBefore, canonical.placement().center(), state.placement().center(), disposition);
        lastReleaseProbeTick = tick;
        if (acknowledgedInputSequence >= release.sequence()) {
            release = null;
            releaseCount = 0;
        }
    }
    private boolean validateEndpoint(Function<CardinalPredictionStep.State, EndpointValidation> endpoint,
                                     CardinalPredictionStep.State scratch) {
        var validation = java.util.Objects.requireNonNull(endpoint.apply(scratch), "endpoint validation");
        if (!validation.accepted()) { refuse(validation); return false; }
        return active();
    }
    private CardinalPredictionStep.State calculate(CardinalPredictionStep.State start,
            PlayerPhysicsProtocol.Controls controls, CardinalPredictionStep.Environment environment,
            Function<AxisAlignedBB, List<AxisAlignedBB>> collisions, long clientTick) {
        var result = CardinalPredictionStep.step(start, environment, controls, CardinalPredictionStep.Mode.NORMAL_DRY,
                collisions, ledger(clientTick));
        if (result instanceof CardinalPredictionStep.Predicted predicted) return predicted.pose();
        if (result instanceof CardinalPredictionStep.Rejected rejected) {
            retire(rejected.reason());
            if (rejected.cause() != null) throw new IllegalStateException(rejected.reason(), rejected.cause());
        } else retire("Unsupported prediction mode");
        return null;
    }
    CardinalPredictionStep.State state() { return state; }
    CardinalPredictionSeed.Ready seed() { return seed; }
    PlayerPhysicalSnapshot authority() { return authority; }
    void bindAcceptedAuthority(PlayerPhysicalSnapshot canonical) {
        if (!authority.equals(canonical)) throw new IllegalArgumentException("Canonical authority differs from reconciled packet");
        authority = canonical;
    }
    private static CardinalPredictionStep.State fromAuthority(PlayerPhysicalSnapshot snapshot, int cooldown) {
        return new CardinalPredictionStep.State(snapshot.placement(), snapshot.worldVelocity().multiply(.05),
                snapshot.contacts().orElseThrow(), cooldown);
    }
    PlayerPhysicalSnapshot predictedSnapshot() {
        // Camera, model and input readers use identity to detect a changed placement
        // across callbacks. Re-reading the same immutable state must retain that identity.
        if (projection == null || projectionState != state || projectionAuthority != authority) {
            projection = new PlayerPhysicalSnapshot(authority.scope(), authority.revision(), state.placement(),
                    state.motionBlocksPerTick().multiply(20), Optional.of(state.contacts()));
            projectionState = state;
            projectionAuthority = authority;
        }
        return projection;
    }
    /** Caller owns lifetime attribution and defers quarantine cleanup until this setter stack has unwound. */
    static final class Mutation {
        private final EntityPlayer actor;
        private final PlayerPhysicalSnapshot projection;
        private final java.util.function.BooleanSupplier current;
        private boolean possiblyPartial;
        private Throwable failure;
        Mutation(EntityPlayer actor, PlayerPhysicalSnapshot projection, java.util.function.BooleanSupplier current) {
            this.actor = actor; this.projection = projection; this.current = current;
        }
        boolean possiblyPartial() { return possiblyPartial; }
        Throwable failure() { return failure; }
        boolean apply() {
            try {
                if (!current.getAsBoolean()) return false;
                var preparation = PlayerPhysicsClientMirror.prepare(actor, projection);
                if (preparation.prepared().isEmpty() || !current.getAsBoolean()) return false;
                // commitPrepared writes fields before invoking a mod-overridable setter. A throw is already partial.
                possiblyPartial = true;
                var bounds = PlayerPhysicsClientMirror.commitPrepared(preparation.prepared().orElseThrow());
                if (!current.getAsBoolean()) return false;
                PlayerPhysicsClientMirror.commitRemaining(bounds);
                var proof = PlayerPhysicsClientMirror.proveInstalled(bounds);
                if (!current.getAsBoolean() || proof.isEmpty()) return false;
                var velocity = projection.worldVelocity().multiply(.05);
                var contacts = projection.contacts().orElseThrow();
                boolean complete = actor.motionX == velocity.x() && actor.motionY == velocity.y() && actor.motionZ == velocity.z()
                        && actor.onGround == contacts.onGround() && actor.collidedHorizontally == contacts.horizontal()
                        && actor.collidedVertically == contacts.vertical()
                        && actor.collided == (contacts.horizontal() || contacts.vertical())
                        && actor.fallDistance == contacts.fallDistance();
                if (!complete || !current.getAsBoolean()) return false;
                possiblyPartial = false;
                return true;
            } catch (Throwable cause) {
                failure = cause;
                return false;
            }
        }
    }
    static boolean matchesCompleteProjection(EntityPlayer actor, PlayerPhysicalSnapshot projection,
            java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean() || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, projection)
                || !current.getAsBoolean()) return false;
        var motion = projection.worldVelocity().multiply(.05);
        var contact = projection.contacts().orElseThrow();
        return actor.motionX == motion.x() && actor.motionY == motion.y() && actor.motionZ == motion.z()
                && actor.onGround == contact.onGround() && actor.collidedHorizontally == contact.horizontal()
                && actor.collidedVertically == contact.vertical() && actor.collided == (contact.horizontal() || contact.vertical())
                && actor.fallDistance == contact.fallDistance() && current.getAsBoolean();
    }
    Vec3d eye(float partialTicks) {
        if (!active() || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, predictedSnapshot())) return null;
        double alpha = Math.max(0, Math.min(1, partialTicks));
        var current = state.placement().eye();
        return previousEye.add(current.subtract(previousEye).multiply(alpha));
    }
}
