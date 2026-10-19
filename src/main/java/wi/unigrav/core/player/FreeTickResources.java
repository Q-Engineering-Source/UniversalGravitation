package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.entity.Entity;

/** Owner-held work accounting survives geometry revisions and movement transaction closure. */
public final class FreeTickResources {
    static final int MAX_NATIVE_ATTEMPTS = 512;

    static final class NativeAttemptLimitException extends RuntimeException {
        NativeAttemptLimitException(int limit) {
            super("native collision attempt limit exceeded: " + limit);
        }
    }
    public static final class Store {
        private FreeTickResources current;
        private boolean retired;
        private final LongSupplier simulationTicks;
        private boolean recoveryTransferred;
        private NativeCorrectionTransfer pendingClientCorrection;
        private NativeResumeTransfer pendingClientResume;

        /** Server accounting follows the authoritative world's tick. */
        public Store() { simulationTicks = null; }

        /** Client accounting follows its actual simulation boundary, never synchronized world time. */
        public Store(LongSupplier simulationTicks) {
            this.simulationTicks = Objects.requireNonNull(simulationTicks, "simulationTicks");
        }

        private LongSupplier clock(World world) {
            return simulationTicks == null ? world::getTotalWorldTime : simulationTicks;
        }

        public FreeTickResources acquire(Object owner, World world, PlayerPhysicalSnapshot.Scope scope,
                                  long correction) {
            if (retired) throw new IllegalStateException("Free resources belong to a retired scope");
            Objects.requireNonNull(owner);
            Objects.requireNonNull(world);
            Objects.requireNonNull(scope);
            // The side-specific client owner checks its game thread before entering this side-neutral store.
            if (world instanceof WorldServer server && !server.isCallingFromMinecraftThread())
                throw new IllegalStateException("Free resources off-thread");
            LongSupplier clock = clock(world);
            long tick = clock.getAsLong();
            if (current != null && current.tick == tick) {
                current.requireCurrent(owner, world, scope, correction);
                return current;
            }
            if (current != null && (current.invalid || current.correction != correction
                    || tick < current.tick || current.owner != owner
                    || current.world != world || !current.scope.equals(scope))) {
                String details = current.mismatch(owner, world, scope, correction, tick);
                invalidate();
                throw new IllegalStateException("Free tick resources changed owner or time lifetime: " + details);
            }
            if (current != null) current.retire();
            current = new FreeTickResources(owner, world, scope, tick, correction, new Frame(), clock);
            return current;
        }

        /** Opaque one-shot accounting handoff, never geometry or native-write authority. */
        public static final class NativeCorrectionTransfer {
            private final Store store;
            private final FreeTickResources previous;
            private final long correction;
            private boolean consumed;
            private NativeCorrectionTransfer(Store store, FreeTickResources previous, long correction) {
                this.store = store; this.previous = previous; this.correction = correction;
            }
        }

        /** Capture the exact acquired client resource before its real correction starts. */
        public NativeCorrectionTransfer prepareClientNativeCorrection(FreeTickResources expected, long correction) {
            if (simulationTicks == null) throw new IllegalStateException("Client correction requires a client clock");
            var capturedPending = pendingClientCorrection;
            long tick = simulationTicks.getAsLong();
            if (pendingClientResume != null || pendingClientCorrection != capturedPending || retired || expected == null || current != expected
                    || correction <= expected.correction || tick < expected.tick
                    || expected.invalid && (pendingClientCorrection == null || pendingClientCorrection.previous != expected))
                throw new IllegalStateException("Client correction changed its captured resource lifetime");
            if (pendingClientCorrection != null) {
                if (pendingClientCorrection.correction == correction) return pendingClientCorrection;
                if (pendingClientCorrection.correction > correction) throw new IllegalStateException("Client correction regressed");
                pendingClientCorrection.consumed = true;
            }
            var transfer = new NativeCorrectionTransfer(this, expected, correction);
            expected.retire();
            pendingClientCorrection = transfer;
            return transfer;
        }

        /** Captured clock boundary; commit below does not invoke the clock or any external callback. */
        public static final class ClientCompletion {
            private final Store store;
            private final Object transfer;
            private final FreeTickResources previous;
            private final long tick;
            private ClientCompletion(Store store, Object transfer, FreeTickResources previous, long tick) {
                this.store = store; this.transfer = transfer; this.previous = previous; this.tick = tick;
            }
        }
        public ClientCompletion captureClientCompletion(NativeCorrectionTransfer transfer) {
            if (simulationTicks == null || retired || transfer == null || transfer.store != this || transfer.consumed
                    || pendingClientCorrection != transfer || current != transfer.previous || pendingClientResume != null)
                throw new IllegalStateException("Client correction lost its completion capture");
            long tick = simulationTicks.getAsLong();
            if (retired || transfer.consumed || pendingClientCorrection != transfer || current != transfer.previous
                    || pendingClientResume != null || tick < transfer.previous.tick)
                throw new IllegalStateException("Client correction changed during completion capture");
            return new ClientCompletion(this, transfer, transfer.previous, tick);
        }

        /** Caller proves matching successful installation before consuming its private-store token. */
        public FreeTickResources completeClientNativeCorrection(NativeCorrectionTransfer transfer, ClientCompletion completion) {
            if (retired || transfer == null || transfer.store != this || transfer.consumed
                    || pendingClientCorrection != transfer || current != transfer.previous || pendingClientResume != null
                    || completion == null || completion.store != this || completion.transfer != transfer
                    || completion.previous != current)
                throw new IllegalStateException("Client correction completion lost its exact accounting token");
            var previous = transfer.previous;
            var next = new FreeTickResources(previous.owner, previous.world, previous.scope, completion.tick,
                    transfer.correction, completion.tick == previous.tick ? previous.frame : new Frame(), simulationTicks);
            transfer.consumed = true;
            pendingClientCorrection = null;
            current = next;
            return next;
        }

        /** Separate accounting handoff for a proven same-scope delegated proposal resume. */
        public static final class NativeResumeTransfer {
            private final Store store;
            private final FreeTickResources previous;
            private boolean consumed;
            private NativeResumeTransfer(Store store, FreeTickResources previous) {
                this.store = store; this.previous = previous;
            }
        }

        /** No ID argument: only the proposal-resume caller can consume this exact private-store lease. */
        public NativeResumeTransfer prepareClientNativeResume(FreeTickResources expected) {
            if (simulationTicks == null) throw new IllegalStateException("Client resume requires a client clock");
            var pending = pendingClientResume;
            var correction = pendingClientCorrection;
            long tick = simulationTicks.getAsLong();
            if (retired || current != expected || expected == null || tick < expected.tick
                    || pendingClientResume != pending || pendingClientCorrection != correction
                    || expected.invalid && (pending == null || pending.previous != expected)
                        && (correction == null || correction.previous != expected))
                throw new IllegalStateException("Client resume changed its captured resource lifetime");
            if (pending != null) pending.consumed = true;
            if (correction != null) correction.consumed = true;
            pendingClientCorrection = null;
            var transfer = new NativeResumeTransfer(this, expected);
            expected.retire();
            pendingClientResume = transfer;
            return transfer;
        }

        public ClientCompletion captureClientCompletion(NativeResumeTransfer transfer) {
            if (simulationTicks == null || retired || transfer == null || transfer.store != this || transfer.consumed
                    || pendingClientResume != transfer || current != transfer.previous || pendingClientCorrection != null)
                throw new IllegalStateException("Client resume lost its completion capture");
            long tick = simulationTicks.getAsLong();
            if (retired || transfer.consumed || pendingClientResume != transfer || current != transfer.previous
                    || pendingClientCorrection != null || tick < transfer.previous.tick)
                throw new IllegalStateException("Client resume changed during completion capture");
            return new ClientCompletion(this, transfer, transfer.previous, tick);
        }

        /** Successful matching OWNED installation proves the new proposal's empty correction identity. */
        public FreeTickResources completeClientNativeResume(NativeResumeTransfer transfer, ClientCompletion completion) {
            if (retired || transfer == null || transfer.store != this || transfer.consumed
                    || pendingClientResume != transfer || current != transfer.previous || pendingClientCorrection != null
                    || completion == null || completion.store != this || completion.transfer != transfer
                    || completion.previous != current)
                throw new IllegalStateException("Client resume lost its exact accounting token");
            var previous = transfer.previous;
            var next = new FreeTickResources(previous.owner, previous.world, previous.scope, completion.tick, -1,
                    completion.tick == previous.tick ? previous.frame : new Frame(), simulationTicks);
            transfer.consumed = true;
            pendingClientResume = null;
            current = next;
            return next;
        }

        /**
         * Explicitly transfers the current owner lifetime after a successfully consumed native correction.
         * The server must establish its session, teleport-ID, correction and actual-geometry fences first.
         */
        /** Only the server's completed native resume handshake may advance this healthy lifetime. */
        FreeTickResources acquireNativeResume(Object owner, World world, PlayerPhysicalSnapshot.Scope scope, long correction) {
            if (current == null || current.correction == correction) return acquire(owner, world, scope, correction);
            return completeNativeCorrection(owner, world, scope, correction);
        }

        /** Retired owners may observe recovery eligibility, never regain movement authority. */
        EnvironmentObservationWork acquireRecoveryObservation(Object owner, World world,
                PlayerPhysicalSnapshot.Scope scope, long correction, Runnable requireCurrent) {
            Objects.requireNonNull(owner); Objects.requireNonNull(world); Objects.requireNonNull(scope);
            Objects.requireNonNull(requireCurrent);
            if (recoveryTransferred || correction < 0
                    || !(world instanceof WorldServer server) || !server.isCallingFromMinecraftThread())
                throw new IllegalStateException("Recovery observation requires a fenced server owner lifetime");
            requireCurrent.run();
            LongSupplier clock = clock(world);
            long tick = clock.getAsLong();
            if (current != null && (current.owner != owner || current.world != world
                    || !current.scope.equals(scope) || correction < current.correction || tick < current.tick))
                throw new IllegalStateException("Recovery observation changed owner lifetime: "
                        + current.mismatch(owner, world, scope, correction, tick));
            retired = true; // The caller proved recovery custody; normal movement can never reacquire this Store.
            if (current != null && !current.invalid && current.observationOnly
                    && current.tick == tick && current.correction == correction && current.observationFence == requireCurrent) {
                current.requireObservationCurrent();
                return current.environmentObservationWork();
            }
            Frame frame = current != null && current.tick == tick ? current.frame : new Frame();
            var observation = new FreeTickResources(owner, world, scope, tick, correction, frame, clock,
                    true, requireCurrent);
            if (current != null) current.retire();
            current = observation;
            return observation.environmentObservationWork();
        }
        FreeTickResources completeNativeCorrection(Object owner, World world,
                PlayerPhysicalSnapshot.Scope scope, long correction) {
            if (retired) throw new IllegalStateException("Free resources belong to a retired scope");
            Objects.requireNonNull(owner);
            Objects.requireNonNull(world);
            Objects.requireNonNull(scope);
            if (world instanceof WorldServer server && !server.isCallingFromMinecraftThread())
                throw new IllegalStateException("Free resources off-thread");
            LongSupplier clock = clock(world);
            long tick = clock.getAsLong();
            Frame nextFrame;
            if (current != null) {
                if (current.invalid || current.owner != owner || current.world != world
                        || !current.scope.equals(scope) || current.correction >= correction || tick < current.tick) {
                    throw new IllegalStateException("Native correction cannot transfer a different free-resource lifetime: "
                            + current.mismatch(owner, world, scope, correction, tick));
                }
                nextFrame = current.tick == tick ? current.frame : new Frame();
            } else nextFrame = new Frame();
            var transferred = new FreeTickResources(owner, world, scope, tick, correction, nextFrame, clock);
            if (current != null) current.retire();
            current = transferred;
            return transferred;
        }

        /** Only explicit recovery scope rotation may carry an already spent tick frame forward. */
        Store transferRecoveryScope(Object owner, World world, PlayerPhysicalSnapshot.Scope oldScope,
                PlayerPhysicalSnapshot.Scope newScope, long correction) {
            return transferRecoveryScope(owner, world, oldScope, newScope, correction, () -> {});
        }

        Store transferRecoveryScope(Object owner, World world, PlayerPhysicalSnapshot.Scope oldScope,
                PlayerPhysicalSnapshot.Scope newScope, long correction, Runnable requireTransferCurrent) {
            Objects.requireNonNull(owner); Objects.requireNonNull(world);
            Objects.requireNonNull(oldScope); Objects.requireNonNull(newScope);
            Objects.requireNonNull(requireTransferCurrent);
            if (!(world instanceof WorldServer server) || !server.isCallingFromMinecraftThread()
                    || recoveryTransferred || oldScope.equals(newScope)
                    || !oldScope.playerId().equals(newScope.playerId())
                    || newScope.worldEpoch() <= oldScope.worldEpoch()
                    || oldScope.dimension() != newScope.dimension())
                throw new IllegalStateException("Invalid recovery resource scope transfer");
            var entry = current;
            LongSupplier transferClock = clock(world);
            long tick = transferClock.getAsLong();
            requireTransferCurrent.run();
            if (recoveryTransferred || current != entry)
                throw new IllegalStateException("Recovery resource transfer changed during its clock observation");
            if (entry != null && (entry.owner != owner || entry.world != world
                    || !entry.scope.equals(oldScope) || correction < entry.correction || tick < entry.tick))
                throw new IllegalStateException("Recovery cannot transfer a different resource lifetime: "
                        + entry.mismatch(owner, world, oldScope, correction, tick));
            Frame frame = entry != null && entry.tick == tick ? entry.frame : new Frame();
            var successor = new Store();
            successor.current = new FreeTickResources(owner, world, newScope, tick, correction, frame, transferClock);
            recoveryTransferred = true;
            invalidate();
            return successor;
        }

        public void invalidate() {
            retired = true;
            if (current != null) current.retire();
        }
    }

    /** Accounting belongs to the world-tick frame, not to an individual correction lifetime handle. */
    private static final class Frame {
        private final CapsuleContactQueryBudget queries = new CapsuleContactQueryBudget(
                QueryLimits.recordingOnly(), 0);
        private final StoredDyadic.Work numeric = new StoredDyadic.Work();
        private final FreePostprocessingWork postprocessing = new FreePostprocessingWork();
        private int nativeAttempts;
        private boolean waterForceConsumed;
    }

    private final Object owner;
    private final World world;
    private final PlayerPhysicalSnapshot.Scope scope;
    private final long tick;
    private final long correction;
    private final Frame frame;
    private final LongSupplier clock;
    private boolean invalid;
    private final boolean observationOnly;
    private final Runnable observationFence;
    private EnvironmentObservationWork environmentObservation;

    private FreeTickResources(Object owner, World world, PlayerPhysicalSnapshot.Scope scope,
                              long tick, long correction, Frame frame, LongSupplier clock) {
        this(owner, world, scope, tick, correction, frame, clock, false, null);
    }

    private FreeTickResources(Object owner, World world, PlayerPhysicalSnapshot.Scope scope,
            long tick, long correction, Frame frame, LongSupplier clock, boolean observationOnly, Runnable observationFence) {
        this.observationOnly = observationOnly;
        this.observationFence = observationFence;        this.owner = owner;
        this.world = world;
        this.scope = scope;
        this.tick = tick;
        this.correction = correction;
        this.frame = Objects.requireNonNull(frame);
        this.clock = Objects.requireNonNull(clock);
    }

    private String mismatch(Object expectedOwner, World expectedWorld, PlayerPhysicalSnapshot.Scope expectedScope,
                            long expectedCorrection, long actualTick) {
        return "invalid=" + invalid + ", ownerChanged=" + (owner != expectedOwner)
                + ", worldChanged=" + (world != expectedWorld) + ", scopeChanged=" + !scope.equals(expectedScope)
                + ", correction=" + correction + ", expectedCorrection=" + expectedCorrection
                + ", frameTick=" + tick + ", actualTick=" + actualTick + ", tickBackward=" + (actualTick < tick);
    }

    public void requireCurrent(Object expectedOwner, World expectedWorld, PlayerPhysicalSnapshot.Scope expectedScope,
                        long expectedCorrection) {
        if (observationOnly) throw new IllegalStateException("Recovery observation has no movement authority");
        requireLifetime(expectedOwner, expectedWorld, expectedScope, expectedCorrection);
    }

    private void requireLifetime(Object expectedOwner, World expectedWorld, PlayerPhysicalSnapshot.Scope expectedScope,
            long expectedCorrection) {        long actualTick = clock.getAsLong();
        if (invalid || owner != expectedOwner || world != expectedWorld || !scope.equals(expectedScope)
                || tick != actualTick || correction != expectedCorrection) {
            String details = mismatch(expectedOwner, expectedWorld, expectedScope, expectedCorrection, actualTick);
            invalid = true;
            throw new OwnerContextChangedException("Free tick resources lost their owner, world, scope or correction: " + details);
        }
    }

    CapsuleContactQueryBudget queries() { return frame.queries; }
    StoredDyadic.Work numeric() { return frame.numeric; }
    FreePostprocessingWork postprocessing() { return frame.postprocessing; }
    Object responseFrameIdentity() { return frame; }

    /**
     * A response fence is sealed only after the normal clock/owner check. Its post-motion-read check must
     * not invoke the tick supplier or owner callbacks, so it verifies the exact resource identities and
     * immutable frame directly while the response remains on the same server thread.
     */
    boolean matchesResponseIdentity(Object expectedOwner, World expectedWorld,
            PlayerPhysicalSnapshot.Scope expectedScope, long expectedCorrection, long expectedTick,
            Object expectedFrame) {
        return !invalid && !observationOnly && owner == expectedOwner && world == expectedWorld
                && scope.equals(expectedScope) && correction == expectedCorrection && tick == expectedTick
                && frame == expectedFrame;
    }

    public EnvironmentObservationWork environmentObservationWork() {
        requireObservationCurrent();
        if (environmentObservation == null) environmentObservation = new EnvironmentObservationWork(this);
        return environmentObservation;
    }

    void requireObservationCurrent() {
        requireLifetime(owner, world, scope, correction);
        if (observationFence != null) {
            try { observationFence.run(); requireLifetime(owner, world, scope, correction); }
            catch (RuntimeException cause) { invalid = true; throw cause; }
        }
    }
    void requireObservationWorld(World expectedWorld) { requireObservationCurrent(); requireLifetime(owner, expectedWorld, scope, correction); }
    void requireObservationActor(Entity actor) {
        Objects.requireNonNull(actor);
        requireObservationWorld(actor.world);
        if (!scope.playerId().equals(actor.getUniqueID()) || scope.dimension() != actor.dimension)
            throw new IllegalArgumentException("observation actor differs from owner resource scope");
    }
    void requireObservationScope(World expectedWorld, PlayerPhysicalSnapshot.Scope expectedScope) {
        requireObservationCurrent();
        requireLifetime(owner, expectedWorld, expectedScope, correction);
    }

    void claimNativeAttempt() {
        requireCurrent(owner, world, scope, correction);
        frame.nativeAttempts++;
    }

    void claimObservationNativeAttempt() {
        requireObservationCurrent();
        frame.postprocessing.charge(1); // The actual aggregate callback dispatch, reserved before invoking it.
        frame.nativeAttempts++;
    }

    void chargeNativeShell(long units) {
        requireCurrent(owner, world, scope, correction);
        frame.numeric.charge(units);
    }

    int nativeAttempts() { return frame.nativeAttempts; }

    /** Claimed before the native consumer: a throwing callback must never replay the force. */
    boolean claimWaterForce() {
        requireCurrent(owner, world, scope, correction);
        if (frame.waterForceConsumed) return false;
        frame.waterForceConsumed = true;
        return true;
    }

    private void retire() {
        invalid = true;
    }
}
