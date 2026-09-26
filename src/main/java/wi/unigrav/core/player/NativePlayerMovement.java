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

    /** Completed native centers in simulation ticks; never a physical velocity authorization. */
    public record CenterSample(long serverTick, Vec3d center) {
        public CenterSample { Objects.requireNonNull(center, "center"); }
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
     * binder calls. Acquire before any binder monitor and close after releasing it. No Entity,
     * world, Remote budget or G state is read or written; this is not a force-history certificate.
     * Call changed before the first semantic write. No-op/read paths close without that mark.
     */
    public synchronized ForceMutation beginForceMutation(Entity entity) {
        if (!(entity instanceof EntityPlayerMP player)) return inertMutation;
        forceMutations.put(player, Math.incrementExact(forceMutations.getOrDefault(player, 0)));
        return new ForceMutation(player);
    }

    /** Thread-confined mutation marker, not a movement lease or a callback executor. */
    public final class ForceMutation implements AutoCloseable {
        private final EntityPlayerMP player;
        private final Thread caller;
        private boolean changed;
        private boolean closed;

        private ForceMutation(EntityPlayerMP player) {
            this.player = player;
            caller = player == null ? null : Thread.currentThread();
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
        long tick = lifetime.world.getTotalWorldTime();
        if (!lifetime.centers.isEmpty()) {
            long previous = lifetime.centers.getLast().serverTick();
            if (tick < previous || tick - previous > 2) lifetime.centers.clear();
            else if (tick == previous) lifetime.centers.removeLast();
        }
        // Use the actual finalized occupied set, not packet coordinates or native motion.
        AxisAlignedBB bounds = handler.player.getEntityBoundingBox();
        Vec3d center = new Vec3d(bounds.minX + (bounds.maxX - bounds.minX) * 0.5,
                bounds.minY + (bounds.maxY - bounds.minY) * 0.5,
                bounds.minZ + (bounds.maxZ - bounds.minZ) * 0.5);
        lifetime.centers.add(new CenterSample(tick, center));
        if (lifetime.centers.size() > 3) lifetime.centers.removeFirst();
        lifetime.observedRevision = completed.delegateRevision();
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
        Optional<Geometry> actual = observe(handler);
        if (actual.isEmpty() || !actual.orElseThrow().placement().equals(lifetime.receipt.placement())
                || !actual.orElseThrow().motion().equals(lifetime.receipt.observedNativeMotionBlocksPerTick())) {
            lifetimes.remove(handler);
            return Optional.empty();
        }
        return Optional.of(lifetime.receipt);
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
                || player.width <= 0 || player.height < player.width || eye < 0
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
