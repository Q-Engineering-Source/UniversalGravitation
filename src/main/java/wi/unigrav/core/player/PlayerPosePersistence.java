package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import wi.unigrav.UnigravMod;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraftforge.common.DimensionManager;

/** Save-time historical pose input. No snapshot, movement budget or installation proof is restored here. */
public final class PlayerPosePersistence {
    private static final String KEY = "unigrav:physical_pose";
    private static final ThreadLocal<InitialTeleport> INITIAL_TELEPORT = new ThreadLocal<>();
    private static final ThreadLocal<InitialBinding> INITIAL_BINDING = new ThreadLocal<>();
    private static final ThreadLocal<LoadCycle> LOAD = new ThreadLocal<>();
    private static final ThreadLocal<LoadPosition> LOAD_POSITION = new ThreadLocal<>();
    private static final ThreadLocal<InitialJoin> INITIAL_JOIN = new ThreadLocal<>();
    private PlayerPosePersistence() { }

    /** Entity-local storage survives logout retirement without retaining disconnected players in a global map. */
    public interface Carrier {
        RetainedPose unigrav$retainedSavePose();
        void unigrav$retainedSavePose(RetainedPose pose);
        LoadedPose unigrav$loadedPose();
        void unigrav$loadedPose(LoadedPose pose);
        long unigrav$poseHistoryGeneration();
        void unigrav$invalidatePoseHistory();
        long unigrav$poseExternalGeneration();
        void unigrav$invalidateExternalPoseHistory();
    }

    /** Connection-local candidate. Only a separate initialization prepare may grant physical ownership. */
    public static final class LoadedPose {
        private final SavedPlayerPose saved;
        private final long generation;
        private final NetHandlerPlayServer connection;
        private final WorldServer world;
        private boolean initialTeleportUsed;
        private boolean initialJoinUsed;
        private LoadedPose(SavedPlayerPose saved, long generation, NetHandlerPlayServer connection, WorldServer world) {
            this.saved = saved; this.generation = generation; this.connection = connection; this.world = world;
        }
    }

    public static void invalidate(EntityPlayerMP player) {
        if (player instanceof Carrier carrier) carrier.unigrav$invalidateExternalPoseHistory();
    }

    private record LoadCycle(EntityPlayerMP player, long externalGeneration) { }
    private static final class LoadPosition {
        private final EntityPlayerMP player;
        private final double x, y, z;
        private boolean consumed;
        private LoadPosition(EntityPlayerMP player, double x, double y, double z) {
            this.player = player; this.x = x; this.y = y; this.z = z;
        }
    }

    public static void readCycle(EntityPlayerMP player, NBTTagCompound root, Runnable original) {
        if (!(player instanceof Carrier carrier)) { original.run(); return; }
        carrier.unigrav$invalidatePoseHistory();
        if (LOAD.get() != null) {
            invalidate(player);
            original.run();
            return;
        }
        LoadCycle cycle = new LoadCycle(player, carrier.unigrav$poseExternalGeneration());
        LOAD.set(cycle);
        try {
            original.run();
            if (carrier.unigrav$poseExternalGeneration() == cycle.externalGeneration) read(player, root);
            else if (root.hasKey(KEY)) UnigravMod.LOGGER.warn(
                    "UG pose-load rejected: external mutation or nested read during native deserialization, player={}", player.getUniqueID());
        } finally { LOAD.remove(); }
    }

    /** The two pinned Entity.readFromNBT calls are the only native position initialization allowance. */
    public static void nativeLoadPosition(EntityPlayerMP player, double x, double y, double z, Runnable original) {
        LoadCycle cycle = LOAD.get();
        if (cycle == null || cycle.player != player || LOAD_POSITION.get() != null) { original.run(); return; }
        LOAD_POSITION.set(new LoadPosition(player, x, y, z));
        try { original.run(); }
        finally { LOAD_POSITION.remove(); }
    }

    public static void positionChanged(EntityPlayerMP player, double x, double y, double z) {
        LoadPosition position = LOAD_POSITION.get();
        if (position != null && position.player == player && !position.consumed
                && position.x == x && position.y == y && position.z == z) {
            position.consumed = true;
            if ((player.posX != x || player.posY != y || player.posZ != z) && player instanceof Carrier carrier)
                carrier.unigrav$invalidatePoseHistory();
        } else if (player.posX != x || player.posY != y || player.posZ != z) invalidate(player);
    }

    private static final class InitialBinding {
        private final EntityPlayerMP player;
        private final LoadedPose loaded;
        private boolean claimed;
        private InitialBinding(EntityPlayerMP player, LoadedPose loaded) { this.player = player; this.loaded = loaded; }
    }
    private record InitialJoin(EntityPlayerMP player, LoadedPose loaded) { }

    /** The pinned PlayerList.playerLoggedIn spawn call, rather than an arbitrary pre-login world event. */
    public static boolean initialJoin(EntityPlayerMP player, BooleanSupplier original) {
        if (!(player instanceof Carrier carrier)) return original.getAsBoolean();
        LoadedPose loaded = carrier.unigrav$loadedPose();
        if (loaded == null || loaded.connection != null || loaded.initialJoinUsed || INITIAL_JOIN.get() != null)
            return original.getAsBoolean();
        loaded.initialJoinUsed = true;
        INITIAL_JOIN.set(new InitialJoin(player, loaded));
        boolean complete = false;
        try { complete = original.getAsBoolean(); return complete; }
        finally {
            INITIAL_JOIN.remove();
            if (!complete) invalidate(player);
        }
    }

    public static boolean initialJoinCurrent(EntityPlayerMP player) {
        InitialJoin join = INITIAL_JOIN.get();
        return join != null && join.player == player && player instanceof Carrier carrier
                && carrier.unigrav$loadedPose() == join.loaded
                && carrier.unigrav$poseHistoryGeneration() == join.loaded.generation;
    }

    /** A new login binding changes future force configuration, but doBind does not integrate velocity. */
    public static void initialBinding(EntityPlayerMP player, Runnable original) {
        if (!(player instanceof Carrier carrier)) { original.run(); return; }
        LoadedPose loaded = carrier.unigrav$loadedPose();
        if (loaded == null || loaded.connection != null || INITIAL_BINDING.get() != null
                || carrier.unigrav$poseHistoryGeneration() != loaded.generation || !atTransport(player, loaded.saved)) {
            original.run(); return;
        }
        var world = player.world;
        var connection = player.connection;
        INITIAL_BINDING.set(new InitialBinding(player, loaded));
        boolean complete = false;
        try { original.run(); complete = true; }
        finally {
            INITIAL_BINDING.remove();
            if (!complete || player.world != world || player.connection != connection || player.isDead
                    || !atTransport(player, loaded.saved) || NativePlayerMovement.INSTANCE.forceMutationActive(player)) invalidate(player);
        }
    }

    /** Pure carrier/token observation, safe under NativePlayerMovement's force lock. */
    static boolean initialBindingCurrent(EntityPlayerMP player) {
        InitialBinding binding = INITIAL_BINDING.get();
        return binding != null && binding.player == player && player instanceof Carrier carrier
                && carrier.unigrav$loadedPose() == binding.loaded
                && carrier.unigrav$poseHistoryGeneration() == binding.loaded.generation;
    }

    static boolean claimInitialBinding(EntityPlayerMP player) {
        InitialBinding binding = INITIAL_BINDING.get();
        if (binding == null || binding.claimed || !initialBindingCurrent(player)) return false;
        binding.claimed = true;
        return true;
    }

    /** Complete native deserialization is the load boundary; its data cannot itself establish a world/session. */
    private static void read(EntityPlayerMP player, NBTTagCompound root) {
        if (!(player instanceof Carrier carrier) || !root.hasKey(KEY)) return;
        long generation = carrier.unigrav$poseHistoryGeneration();
        try {
            if (!root.hasKey(KEY, 10)) throw new IllegalArgumentException("UG pose history must be a compound");
            SavedPlayerPose saved = SavedPlayerPose.read(root.getCompoundTag(KEY));
            var pos = root.getTagList("Pos", 6);
            if (!root.hasKey("UUIDMost", 4) || !root.hasKey("UUIDLeast", 4)
                    || !root.hasKey("Dimension", 3) || !root.hasKey("Pos", 9)
                    || !saved.player().equals(root.getUniqueId("UUID"))
                    || !saved.player().equals(player.getUniqueID()) || saved.dimension() != root.getInteger("Dimension")
                    || saved.dimension() != player.dimension || pos.tagCount() != 3
                    || pos.getDoubleAt(0) != saved.transport().x() || pos.getDoubleAt(1) != saved.transport().y()
                    || pos.getDoubleAt(2) != saved.transport().z() || !atTransport(player, saved)
                    || player.isDead || NativePlayerMovement.INSTANCE.forceMutationActive(player)
                    || carrier.unigrav$poseHistoryGeneration() != generation)
                throw new IllegalArgumentException("UG pose history does not match completed native player load");
            carrier.unigrav$loadedPose(new LoadedPose(saved, generation, null, null));
        } catch (IllegalArgumentException failure) {
            carrier.unigrav$loadedPose(null);
            UnigravMod.LOGGER.warn("UG pose-load rejected: player={}, dimension={}, reason={}",
                    player.getUniqueID(), player.dimension, failure.getMessage());
        } catch (RuntimeException failure) {
            carrier.unigrav$loadedPose(null);
            UnigravMod.LOGGER.error("UG pose-load failed; native player load retained without restore permission: player={}",
                    player.getUniqueID(), failure);
        }
    }

    static void bindLogin(EntityPlayerMP player) {
        if (!(player instanceof Carrier carrier)) return;
        LoadedPose loaded = carrier.unigrav$loadedPose();
        if (loaded == null) return;
        try {
            NetHandlerPlayServer connection = player.connection;
            if (!(player.world instanceof WorldServer world) || connection == null || player.isDead
                    || !loaded.saved.player().equals(player.getUniqueID()) || loaded.saved.dimension() != player.dimension
                    || !loaded.saved.world().equals(PlayerPoseWorldIdentity.get(world))
                    || !atTransport(player, loaded.saved) || NativePlayerMovement.INSTANCE.forceMutationActive(player)
                    || carrier.unigrav$poseHistoryGeneration() != loaded.generation || carrier.unigrav$loadedPose() != loaded
                    || player.connection != connection || connection.player != player
                    || connection.netManager.getNetHandler() != connection || !connection.netManager.isChannelOpen()
                    || player.world != world || DimensionManager.getWorld(player.dimension) != world || player.isDead)
                throw new IllegalArgumentException("Loaded pose world/connection/transport/generation changed before login binding");
            carrier.unigrav$loadedPose(new LoadedPose(loaded.saved, loaded.generation, connection, world));
        } catch (IllegalArgumentException failure) {
            carrier.unigrav$loadedPose(null);
            UnigravMod.LOGGER.warn("UG pose-login rejected: player={}, reason={}", player.getUniqueID(), failure.getMessage());
        } catch (RuntimeException failure) {
            carrier.unigrav$loadedPose(null);
            UnigravMod.LOGGER.error("UG pose-login binding failed: player={}", player.getUniqueID(), failure);
        }
    }

    private static final class InitialTeleport {
        private final NetHandlerPlayServer handler;
        private final LoadedPose loaded;
        private boolean observed;
        private InitialTeleport(NetHandlerPlayServer handler, LoadedPose loaded) {
            this.handler = handler; this.loaded = loaded;
        }
    }

    /** Only the pinned PlayerList initialization call supplies this one-use handshake bracket. */
    public static void initialTeleport(NetHandlerPlayServer handler, double x, double y, double z,
            float yaw, float pitch, Runnable original) {
        if (!(handler.player instanceof Carrier carrier)) { original.run(); return; }
        LoadedPose loaded = carrier.unigrav$loadedPose();
        if (loaded == null || loaded.connection != null || loaded.initialTeleportUsed
                || INITIAL_TELEPORT.get() != null || !atTransport(handler.player, loaded.saved)
                || x != handler.player.posX || y != handler.player.posY || z != handler.player.posZ
                || yaw != handler.player.rotationYaw || pitch != handler.player.rotationPitch) {
            original.run(); return;
        }
        loaded.initialTeleportUsed = true;
        InitialTeleport token = new InitialTeleport(handler, loaded);
        INITIAL_TELEPORT.set(token);
        boolean completed = false;
        try { original.run(); completed = true; }
        finally {
            INITIAL_TELEPORT.remove();
            if (!completed || !token.observed) carrier.unigrav$invalidatePoseHistory();
        }
    }

    /** Before native mutation/send callbacks: only the exact first-login handshake can retain a candidate. */
    public static void observeTeleport(NetHandlerPlayServer handler, double x, double y, double z,
            float yaw, float pitch, Set<?> relative) {
        if (handler.player instanceof Carrier carrier) {
            LoadedPose loaded = carrier.unigrav$loadedPose();
            InitialTeleport token = INITIAL_TELEPORT.get();
            if (loaded != null && token != null && token.handler == handler && token.loaded == loaded && !token.observed
                    && loaded.connection == null && carrier.unigrav$poseHistoryGeneration() == loaded.generation
                    && relative.isEmpty() && atTransport(handler.player, loaded.saved)
                    && x == handler.player.posX && y == handler.player.posY && z == handler.player.posZ
                    && yaw == handler.player.rotationYaw && pitch == handler.player.rotationPitch) {
                token.observed = true;
            } else invalidate(handler.player);
        }
    }

    private static boolean atTransport(EntityPlayerMP player, SavedPlayerPose saved) {
        return StoredRealizationLimits.sameRaw(player.posX, saved.transport().x())
                && StoredRealizationLimits.sameRaw(player.posY, saved.transport().y())
                && StoredRealizationLimits.sameRaw(player.posZ, saved.transport().z());
    }

    public static final class RetainedPose {
        private final NetHandlerPlayServer connection;
        private final WorldServer world;
        private final PlayerPhysicalSnapshot snapshot;
        RetainedPose(NetHandlerPlayServer connection, WorldServer world, PlayerPhysicalSnapshot snapshot) {
            this.connection = connection;
            this.world = world;
            this.snapshot = snapshot;
        }
    }

    static void retain(EntityPlayerMP player, RetainedPose pose) {
        if (player instanceof Carrier carrier) carrier.unigrav$retainedSavePose(pose);
    }

    /** Runs after the complete native serialization, including virtual player/mod callbacks and vanilla Pos. */
    public static void write(EntityPlayerMP player, NBTTagCompound root) {
        root.removeTag(KEY);
        try {
            writeObserved(player, root);
        } catch (RuntimeException failure) {
            root.removeTag(KEY);
            UnigravMod.LOGGER.error("UG pose-save failed; native player data retained without restore history: player={}, dimension={}",
                    player.getUniqueID(), player.dimension, failure);
        } finally {
            // A disconnected carrier authorizes one save attempt, including a failed attempt.
            retain(player, null);
        }
    }

    private static boolean identityCurrent(EntityPlayerMP player, RetainedPose pose, NBTTagCompound root) {
        var pos = root.getTagList("Pos", 6);
        var transport = pose.snapshot.placement().transport();
        return player.connection == pose.connection && player.world == pose.world && !player.isDead
                && player.dimension == pose.snapshot.scope().dimension()
                && player.getUniqueID().equals(pose.snapshot.scope().playerId())
                && root.getInteger("Dimension") == player.dimension && pos.tagCount() == 3
                && pos.getDoubleAt(0) == transport.x() && pos.getDoubleAt(1) == transport.y()
                && pos.getDoubleAt(2) == transport.z();
    }

    private static void writeObserved(EntityPlayerMP player, NBTTagCompound root) {
        RetainedPose pose = PlayerPhysicsServer.INSTANCE.poseForSave(player);
        if (pose == null || !identityCurrent(player, pose, root)) return;
        if (!pose.world.isCallingFromMinecraftThread())
            throw new IllegalStateException("Player pose serialization requires the server world thread");
        var placement = pose.snapshot.placement();
        var actual = WorldCollisionCandidates.ActorGeometry.read(player);
        var dimensions = placement.shape().dimensions();
        float eye = player.getEyeHeight();
        var after = WorldCollisionCandidates.ActorGeometry.read(player);
        if (!actual.equals(after) || !actual.position().equals(placement.transport())
                || !actual.bounds().equals(placement.bounds()) || actual.width() != (float) dimensions.width()
                || actual.height() != (float) dimensions.height() || eye != dimensions.eyeHeight()) return;
        var pos = root.getTagList("Pos", 6);
        if (pos.tagCount() != 3 || pos.getDoubleAt(0) != actual.position().x()
                || pos.getDoubleAt(1) != actual.position().y() || pos.getDoubleAt(2) != actual.position().z()
                || root.getInteger("Dimension") != player.dimension) return;

        var tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setUniqueId("player", player.getUniqueID());
        tag.setUniqueId("world", PlayerPoseWorldIdentity.get(pose.world));
        tag.setInteger("dimension", player.dimension);
        putVector(tag, "center", placement.center());
        putVector(tag, "transport", placement.transport());
        putVector(tag, "boundsMin", placement.bounds().min());
        putVector(tag, "boundsMax", placement.bounds().max());
        putVector(tag, "eye", placement.eye());
        var rotation = placement.attitude();
        var q = new NBTTagCompound();
        q.setDouble("x", rotation.x()); q.setDouble("y", rotation.y());
        q.setDouble("z", rotation.z()); q.setDouble("w", rotation.w());
        tag.setTag("attitude", q);
        tag.setDouble("nativeWidth", dimensions.width());
        tag.setDouble("nativeHeight", dimensions.height());
        tag.setDouble("nativeEye", dimensions.eyeHeight());
        if (placement.shape() instanceof PlayerBodyShape.Cardinal cardinal) {
            tag.setString("kind", "CARDINAL");
            tag.setString("reference", cardinal.frame().name());
        } else if (placement.shape() instanceof PlayerBodyShape.FreeCapsule free) {
            tag.setString("kind", free.pronePose().map(p -> p.kind().name()).orElse("FREE"));
            free.pronePose().ifPresent(p -> tag.setString("reference", p.referenceFrame().name()));
            tag.setDouble("diameter", free.collisionProfile().diameter());
            tag.setDouble("length", free.collisionProfile().length());
            putVector(tag, "storedHalfLine", placement.storedCapsuleGeometry().storedHalfLine());
        }
        // Save reads only the world-load identity; it never creates or loads world data in player serialization.
        if (player.world != pose.world || !actual.equals(WorldCollisionCandidates.ActorGeometry.read(player))
                || player.getEyeHeight() != eye || !actual.equals(WorldCollisionCandidates.ActorGeometry.read(player))) return;
        RetainedPose current = PlayerPhysicsServer.INSTANCE.poseForSave(player);
        if (current == null || current.snapshot != pose.snapshot || current.world != pose.world
                || current.connection != pose.connection || !identityCurrent(player, pose, root)) return;
        root.setTag(KEY, tag);
    }

    private static void putVector(NBTTagCompound parent, String key, Vec3d vector) {
        var tag = new NBTTagCompound();
        tag.setDouble("x", vector.x()); tag.setDouble("y", vector.y()); tag.setDouble("z", vector.z());
        parent.setTag(key, tag);
    }
}
