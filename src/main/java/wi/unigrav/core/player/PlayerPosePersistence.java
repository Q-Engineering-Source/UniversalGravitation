package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import wi.unigrav.UnigravMod;

/** Save-time historical pose input. No snapshot, movement budget or installation proof is restored here. */
public final class PlayerPosePersistence {
    private static final String KEY = "unigrav:physical_pose";
    private PlayerPosePersistence() { }

    /** Entity-local storage survives logout retirement without retaining disconnected players in a global map. */
    public interface Carrier {
        RetainedPose unigrav$retainedSavePose();
        void unigrav$retainedSavePose(RetainedPose pose);
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
