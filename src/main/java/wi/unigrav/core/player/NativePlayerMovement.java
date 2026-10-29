package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * One native Y+ movement transaction and its lifecycle-bound geometry receipt.
 *
 * <p>This is not a tick observer or a custom movement owner. It writes no Entity fields and
 * does not integrate forces, create support, grant movement credit, or send packets.
 * The existing processPlayer hooks delimit the actual native delegate and final assignment.
 * Non-Y+ and unrepresentable native boxes remain unsupported, without changing native movement.
 * A future custom owner must establish its own authoritative velocity and physical lifetime;
 * these receipts must never be converted implicitly to PlayerPhysicalSnapshot.
 */
public final class NativePlayerMovement {
    public static final NativePlayerMovement INSTANCE = new NativePlayerMovement();

    private final Map<NetHandlerPlayServer, Lifetime> lifetimes = new IdentityHashMap<>();
    private final Map<WorldServer, Long> worldEpochs = new IdentityHashMap<>();
    /** Only synchronous, currently open mutations retain actors here; no world access off-thread. */
    private final Map<EntityPlayerMP, Integer> forceMutations = new IdentityHashMap<>();
    private final ForceMutation inertMutation = new ForceMutation(null);
    private long nextWorldEpoch;
    private boolean installed;

    /** Native motion is an observation in blocks/tick, NOT accepted physical world velocity. */
    public record Receipt(PlayerPhysicalSnapshot.Scope scope, long delegateRevision,
                          PlayerBodyPlacement placement, Vec3d observedNativeMotionBlocksPerTick) {
        public Receipt {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(observedNativeMotionBlocksPerTick, "observedNativeMotionBlocksPerTick");
            if (delegateRevision < 0) throw new IllegalArgumentException("negative delegate revision");
            if (!(placement.shape() instanceof PlayerBodyShape.Cardinal cardinal)
                    || cardinal.frame() != CardinalFrame.Y_POS
                    || !placement.attitude().equals(CardinalFrame.Y_POS.attitude())) {
                throw new IllegalArgumentException("native receipt requires canonical Y+ geometry");
            }
            placement.bounds();
            placement.eye();
        }
    }

    /** Value provenance of the occupied set; eye-only changes do not manufacture centre velocity. */
    public record CenterSource(double width, double height, Vec3d minimumOffset, Vec3d maximumOffset,
                               Optional<NativeBodyProfileProducer.Profile> profile,
                               boolean sneaking, boolean gliding) {
        public CenterSource {
            Objects.requireNonNull(minimumOffset, "minimumOffset");
            Objects.requireNonNull(maximumOffset, "maximumOffset");
            Objects.requireNonNull(profile, "profile");
        }
    }

    /** Completed native centers and their immutable sources, never a velocity authorization. */
    public record CenterSample(long serverTick, Vec3d center, PlayerBodyPlacement placement, CenterSource source) {
        public CenterSample {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * Up to three completed native-delegate tick samples, not instantaneous velocity or permission.
     * The last tick is provisional: a later successful packet in that tick replaces its sample.
     * Router admits Remote custody only with its own consumed clean-completion evidence.
     */
    public record CenterWindow(PlayerPhysicalSnapshot.Scope scope, long delegateRevision,
                               List<CenterSample> samples) {
        public CenterWindow {
            Objects.requireNonNull(scope, "scope");
            samples = List.copyOf(samples);
        }
    }

    public synchronized Optional<CenterWindow> acceptedCenters(NetHandlerPlayServer handler) {
        WorldServer world = requireThread(handler);
        Optional<Receipt> receipt = current(handler);
        if (receipt.isEmpty()) return Optional.empty();
        Lifetime lifetime = lifetimes.get(handler);
        if (rejectForceObservation(lifetime) || handler.player.velocityChanged) {
            lifetime.centers.clear();
            return Optional.empty();
        }
        if (lifetime.centers.isEmpty()
                || lifetime.observedRevision != receipt.orElseThrow().delegateRevision()) return Optional.empty();
        var last = lifetime.centers.getLast();
        var observed = observeCenterSample(handler, lifetime, receipt.orElseThrow());
        if (observed.isEmpty()) return Optional.empty();
        if (lifetime.centers.isEmpty() || lifetime.centers.getLast() != last
                || lifetime.observedRevision != receipt.orElseThrow().delegateRevision()) return Optional.empty();
        if (!last.source().equals(observed.orElseThrow().source())
                || !last.placement().equals(observed.orElseThrow().placement())) {
            lifetime.centers.clear();
            return Optional.empty();
        }
        long age = world.getTotalWorldTime() - lifetime.centers.getLast().serverTick();
        if (age < 0 || age > 2) {
            lifetime.centers.clear();
            return Optional.empty();
        }
        return Optional.of(new CenterWindow(lifetime.scope, lifetime.observedRevision, lifetime.centers));
    }

    synchronized void discardCenters(NetHandlerPlayServer handler) {
        requireThread(handler);
        Lifetime lifetime = lifetimes.get(handler);
        if (lifetime != null) lifetime.centers.clear();
    }

    /**
     * Brackets a synchronous UG-owned force/impulse/binding mutation, including foreign-thread
     * binder calls. Acquire before any binder monitor and close after releasing it. No native
     * geometry, world, Remote budget or G state is read or written; changed mutations invalidate
     * only historical pose carriers as well as native observations. This is not a force-history certificate.
     * Call changed before the first semantic write. No-op/read paths close without that mark.
     */
    public synchronized ForceMutation beginForceMutation(Entity entity) {
        if (!(entity instanceof EntityPlayerMP player)) return inertMutation;
        forceMutations.put(player, Math.incrementExact(forceMutations.getOrDefault(player, 0)));
        return new ForceMutation(player);
    }

    /** A save cannot observe a retired pose halfway through a binder mutation. */
    synchronized boolean forceMutationActive(EntityPlayerMP player) {
        return forceMutations.containsKey(player);
    }

    /** Thread-confined mutation marker, not a movement lease or a callback executor. */
    public final class ForceMutation implements AutoCloseable {
        private final EntityPlayerMP player;
        private final Thread caller;
        private boolean initialBinding;
        private boolean bindingClaimAttempted;
        private boolean changed;
        private boolean closed;

        private ForceMutation(EntityPlayerMP player) {
            this.player = player;
            caller = player == null ? null : Thread.currentThread();
        }

        /** Local one-use classification at tryBindWith's actual new-binding write; acquires no Native lock. */
        public void initialPlayerBinding() {
            if (player == null) return;
            requireCaller();
            if (closed || changed || bindingClaimAttempted)
                throw new IllegalStateException("Initial player binding must be classified once before its first write");
            bindingClaimAttempted = true;
            initialBinding = PlayerPosePersistence.claimInitialBinding(player);
        }

        /** Pure local mark: safe inside the binder monitor, with no Native lock acquisition. */
        public void changed() {
            if (player == null) return;
            requireCaller();
            if (closed) throw new IllegalStateException("force mutation already closed");
            changed = true;
        }

        @Override
        public void close() {
            if (player == null) return;
            requireCaller();
            synchronized (NativePlayerMovement.this) {
                if (closed) return;
                closed = true;
                int remaining = forceMutations.get(player) - 1;
                if (remaining == 0) forceMutations.remove(player);
                else forceMutations.put(player, remaining);
                if (changed) {
                    // Only historical carriers/generation are invalidated; no native geometry/world access.
                    if (initialBinding && PlayerPosePersistence.initialBindingCurrent(player))
                        PlayerPosePersistence.retain(player, null);
                    else PlayerPosePersistence.invalidate(player);
                    for (Lifetime lifetime : lifetimes.values()) {
                        if (lifetime.player != player) continue;
                        lifetime.centers.clear();
                        lifetime.observationDirty = true;
                    }
                }
            }
        }

        private void requireCaller() {
            if (Thread.currentThread() != caller) {
                throw new IllegalStateException("force mutation marker must close on its creating thread");
            }
        }
    }

    private boolean rejectForceObservation(Lifetime lifetime) {
        if (!lifetime.observationDirty && !forceMutations.containsKey(lifetime.player)) return false;
        lifetime.observationDirty = true;
        lifetime.centers.clear();
        return true;
    }

    /** Router-only publication after all native/boost callbacks and throwable cleanup succeeded. */
    synchronized void completePacket(NetHandlerPlayServer handler, boolean teleportPending) {
        requireThread(handler);
        if (teleportPending) {
            lifetimes.remove(handler);
            return;
        }
        Optional<Receipt> receipt = current(handler);
        if (receipt.isEmpty()) return;
        Lifetime lifetime = lifetimes.get(handler);
        if (rejectForceObservation(lifetime) || handler.player.velocityChanged) {
            lifetime.centers.clear();
            return;
        }
        Receipt completed = receipt.orElseThrow();
        if (lifetime.observedRevision == completed.delegateRevision()) return;
        var observed = observeCenterSample(handler, lifetime, completed);
        if (observed.isEmpty()) return;
        var sample = observed.orElseThrow();
        long tick = sample.serverTick();
        if (!lifetime.centers.isEmpty()) {
            long previous = lifetime.centers.getLast().serverTick();
            if (tick < previous || tick - previous > 2
                    || !lifetime.centers.getLast().source().equals(sample.source())) lifetime.centers.clear();
            else if (tick == previous) lifetime.centers.removeLast();
        }
        lifetime.centers.add(sample);
        if (lifetime.centers.size() > 3) lifetime.centers.removeFirst();
        lifetime.observedRevision = completed.delegateRevision();
    }

    /** Read real producer values twice around callback-capable geometry reads, then fence publication. */
    private Optional<CenterSample> observeCenterSample(NetHandlerPlayServer handler, Lifetime lifetime, Receipt receipt) {
        EntityPlayerMP player = lifetime.player;
        long tick = lifetime.world.getTotalWorldTime();
        var profile = centerProfile(player);
        boolean sneaking = player.isSneaking();
        boolean gliding = player.isElytraFlying();
        if (current(handler).filter(value -> value == receipt).isEmpty()) return Optional.empty();
        if (!profile.equals(centerProfile(player))) {
            lifetime.centers.clear();
            return Optional.empty();
        }
        if (current(handler).filter(value -> value == receipt).isEmpty()) return Optional.empty();
        boolean finalSneaking = player.isSneaking();
        boolean finalGliding = player.isElytraFlying();
        // Getter callbacks must finish before the final lifetime/force publication fence.
        if (sneaking != finalSneaking || gliding != finalGliding
                || lifetimes.get(handler) != lifetime || lifetime.pending != null || lifetime.receipt != receipt
                || !lifetime.matches(handler) || rejectForceObservation(lifetime) || player.velocityChanged
                || lifetime.world.getTotalWorldTime() != tick) return Optional.empty();
        var placement = receipt.placement();
        var bounds = placement.bounds();
        var transport = placement.transport();
        var dimensions = placement.shape().dimensions();
        var source = new CenterSource(dimensions.width(), dimensions.height(),
                bounds.min().subtract(transport), bounds.max().subtract(transport), profile, sneaking, gliding);
        // Preserve the native actual-box centre arithmetic used by the original producer.
        var center = new Vec3d(bounds.min().x() + (bounds.max().x() - bounds.min().x()) * 0.5,
                bounds.min().y() + (bounds.max().y() - bounds.min().y()) * 0.5,
                bounds.min().z() + (bounds.max().z() - bounds.min().z()) * 0.5);
        return Optional.of(new CenterSample(tick, center, placement, source));
    }

    private static Optional<NativeBodyProfileProducer.Profile> centerProfile(EntityPlayerMP player) {
        return player instanceof NativeBodyProfileProducer producer
                ? producer.unigrav$nativeBodyProfile() : Optional.empty();
    }

    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    /** Called after the native packet thread hop, before any movement locals are captured. */
    public synchronized void begin(NetHandlerPlayServer handler, boolean teleportPending) {
        WorldServer world = requireThread(handler);
        Optional<Geometry> start = observe(handler);
        if (teleportPending || start.isEmpty()) {
            lifetimes.remove(handler);
            return;
        }
        Lifetime lifetime = lifetimes.get(handler);
        if (lifetime != null && lifetime.pending != null) {
            lifetimes.remove(handler);
            return;
        }
        if (lifetime == null || !lifetime.matches(handler)
                || lifetime.receipt != null && !lifetime.receipt.placement().equals(start.orElseThrow().placement())) {
            long epoch = worldEpochs.computeIfAbsent(world, ignored -> allocateWorldEpoch());
            lifetime = new Lifetime(handler.player, world, new PlayerPhysicalSnapshot.Scope(
                    handler.player.getUniqueID(), UUID.randomUUID(), handler.player.dimension, epoch));
            lifetimes.put(handler, lifetime);
        }
        // A completed mutation only permits a fresh packet, never an ABA revival of this packet.
        lifetime.observationDirty = forceMutations.containsKey(handler.player);
        if (lifetime.observationDirty || handler.player.velocityChanged) lifetime.centers.clear();
        lifetime.pending = new Pending(start.orElseThrow(), world.getTotalWorldTime());
    }

    /** After the sole native move: retain a pending observation, never publish a receipt here. */
    public synchronized void captureNativeMove(NetHandlerPlayServer handler) {
        requireThread(handler);
        Lifetime lifetime = pendingLifetime(handler);
        if (lifetime == null) return;
        Optional<Geometry> moved = observe(handler);
        if (moved.isEmpty() || lifetime.pending.moved != null
                || !lifetime.pending.start.placement().shape().equals(moved.orElseThrow().placement().shape())) {
            lifetimes.remove(handler);
            return;
        }
        lifetime.pending.moved = moved.orElseThrow();
    }

    /** After final native position assignment, before tracker update; the only receipt writer. */
    public synchronized void commitBeforeTracking(NetHandlerPlayServer handler, boolean teleportPending) {
        requireThread(handler);
        if (teleportPending) {
            lifetimes.remove(handler);
            return;
        }
        Lifetime lifetime = pendingLifetime(handler);
        if (lifetime == null) return;
        Optional<Geometry> settled = observe(handler);
        if (lifetime.pending.moved == null || settled.isEmpty()
                || !lifetime.pending.moved.placement().equals(settled.orElseThrow().placement())) {
            lifetimes.remove(handler);
            return;
        }
        if (lifetime.receipt != null && lifetime.receipt.delegateRevision() == Long.MAX_VALUE) {
            lifetimes.remove(handler);
            return;
        }
        long revision = lifetime.receipt == null ? 0 : lifetime.receipt.delegateRevision() + 1;
        Geometry end = settled.orElseThrow();
        lifetime.receipt = new Receipt(lifetime.scope, revision, end.placement(), end.motion());
        lifetime.pending = null;
    }

    /** RETURN never manufactures success for a path that skipped the tracker commit anchor. */
    public synchronized void finish(NetHandlerPlayServer handler) {
        requireThread(handler);
        Lifetime lifetime = lifetimes.get(handler);
        if (lifetime != null && (lifetime.pending != null || !lifetime.matches(handler))) lifetimes.remove(handler);
    }

    /** A current native geometry receipt, not an accepted physics snapshot or standing observation. */
    public synchronized Optional<Receipt> current(NetHandlerPlayServer handler) {
        requireThread(handler);
        Lifetime lifetime = lifetimes.get(handler);
        if (lifetime == null) return Optional.empty();
        if (!lifetime.matches(handler)) {
            lifetimes.remove(handler);
            return Optional.empty();
        }
        if (lifetime.pending != null || lifetime.receipt == null) return Optional.empty();
        Receipt expected = lifetime.receipt;
        Optional<Geometry> actual = observe(handler);
        if (lifetimes.get(handler) != lifetime || lifetime.pending != null || lifetime.receipt != expected
                || !lifetime.matches(handler)) return Optional.empty();
        if (actual.isEmpty() || !actual.orElseThrow().placement().equals(expected.placement())
                || !actual.orElseThrow().motion().equals(expected.observedNativeMotionBlocksPerTick())) {
            lifetimes.remove(handler, lifetime);
            return Optional.empty();
        }
        return Optional.of(expected);
    }

    /** Absolute native relocation retires this lifetime; a teleport ACK cannot resurrect it. */
    public synchronized void invalidate(NetHandlerPlayServer handler) {
        requireThread(handler);
        lifetimes.remove(handler);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) { retirePlayer(event.player); }
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) { retirePlayer(event.player); }
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { retirePlayer(event.player); }
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { retirePlayer(event.player); }

    private synchronized void retirePlayer(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP serverPlayer)) return;
        if (!serverPlayer.getServerWorld().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("native player lifecycle requires the server thread");
        }
        lifetimes.entrySet().removeIf(entry -> entry.getValue().player == player
                || entry.getKey() == serverPlayer.connection && entry.getKey().player == player);
    }

    @SubscribeEvent
    public synchronized void onWorldUnload(WorldEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldServer world)) return;
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("world unload requires server thread");
        lifetimes.values().removeIf(lifetime -> lifetime.world == world);
        worldEpochs.remove(world);
    }

    /** Server shutdown drops only this module's references, never player/world data or G intent. */
    public synchronized void stop() {
        for (WorldServer world : worldEpochs.keySet()) {
            if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("shutdown requires server thread");
        }
        lifetimes.clear();
        worldEpochs.clear();
        // In-flight synchronous binder operations own their markers until close; clearing their
        // depth here would admit a new observation before an already-started mutation finishes.
    }

    private long allocateWorldEpoch() {
        long epoch = nextWorldEpoch;
        nextWorldEpoch = Math.incrementExact(nextWorldEpoch);
        return epoch;
    }

    private Lifetime pendingLifetime(NetHandlerPlayServer handler) {
        Lifetime lifetime = lifetimes.get(handler);
        if (lifetime == null || lifetime.pending == null) return null;
        if (!lifetime.matches(handler) || lifetime.world.getTotalWorldTime() != lifetime.pending.worldTick) {
            lifetimes.remove(handler);
            return null;
        }
        return lifetime;
    }

    private static WorldServer requireThread(NetHandlerPlayServer handler) {
        Objects.requireNonNull(handler, "handler");
        WorldServer world = handler.player.getServerWorld();
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("native movement requires server thread");
        return world;
    }

    /** Unsupported native state is explicitly not a receipt; no tolerance or entity repair is applied. */
    private static Optional<Geometry> observe(NetHandlerPlayServer handler) {
        EntityPlayerMP player = handler.player;
        if (player.connection != handler || player.isDead || !player.isEntityAlive() || player.noClip
                || player.isSpectator() || player.isRiding() || player.isPlayerSleeping()
                || player.dimension != player.world.provider.getDimension()
                || player.world.captureBlockSnapshots || player.world.restoringBlockSnapshots) return Optional.empty();
        AxisAlignedBB box = player.getEntityBoundingBox();
        double eye = player.getEyeHeight();
        if (!finite(player.posX, player.posY, player.posZ, player.motionX, player.motionY, player.motionZ,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, player.width, player.height, eye)
                || player.width <= 0 || player.height <= 0 || eye < 0
                || !(box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ)
                || player.posY != box.minY) return Optional.empty();
        // Native player floats cannot overflow binary64 here; reject collapsed translations explicitly.
        double centerY = box.minY + player.height / 2.0;
        if (!Double.isFinite(centerY)) return Optional.empty();
        var dimensions = new PlayerBodyShape.Dimensions(player.width, player.height, eye);
        var placement = PlayerBodyPlacement.fromTransport(new Vec3d(player.posX, box.minY, player.posZ),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        var canonical = placement.bounds();
        if (canonical.min().x() != box.minX || canonical.min().y() != box.minY || canonical.min().z() != box.minZ
                || canonical.max().x() != box.maxX || canonical.max().y() != box.maxY || canonical.max().z() != box.maxZ) {
            return Optional.empty();
        }
        return Optional.of(new Geometry(placement, new Vec3d(player.motionX, player.motionY, player.motionZ)));
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private record Geometry(PlayerBodyPlacement placement, Vec3d motion) { }

    private static final class Pending {
        private final Geometry start;
        private final long worldTick;
        private Geometry moved;

        private Pending(Geometry start, long worldTick) { this.start = start; this.worldTick = worldTick; }
    }

    private static final class Lifetime {
        private final EntityPlayerMP player;
        private final WorldServer world;
        private final PlayerPhysicalSnapshot.Scope scope;
        private Pending pending;
        private Receipt receipt;
        private final List<CenterSample> centers = new ArrayList<>(3);
        private long observedRevision = -1;
        private boolean observationDirty;

        private Lifetime(EntityPlayerMP player, WorldServer world, PlayerPhysicalSnapshot.Scope scope) {
            this.player = player;
            this.world = world;
            this.scope = scope;
        }

        private boolean matches(NetHandlerPlayServer handler) {
            return handler.player == player && player.connection == handler && player.world == world
                    && player.dimension == scope.dimension() && player.getUniqueID().equals(scope.playerId());
        }
    }
}
