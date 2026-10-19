package wi.unigrav.proxy;

import net.minecraft.network.INetHandler;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.Entity;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;
/** Side-specific hooks, invoked from the mod's lifecycle handlers. */
public interface IProxy {

    /** Whole native environment operation; an owned failure terminates this operation at its owner boundary. */
    default void runNativeEnvironment(Entity actor, Runnable original) { original.run(); }

    default boolean nativeWaterMovement(Entity actor, BooleanSupplier original) { return original.getAsBoolean(); }

    default boolean nativeInLava(Entity actor, BooleanSupplier original) { return original.getAsBoolean(); }

    /** Entire native motion call site; input/camera ownership remains with UIE. */
    default void runLegacyMotion(EntityLivingBase actor, Runnable original) { original.run(); }

    default void runNativePose(EntityLivingBase actor, Runnable original) { original.run(); }

    /** The actual Aqua growth move call site, inside its controlled resize callback. */
    default void runPoseMove(EntityLivingBase actor, MoverType type, Vec3d delta, Consumer<Vec3d> original) {
        original.accept(delta);
    }

    /** Candidate box for Aqua's real pose collision query; the default preserves its native geometry. */
    default AxisAlignedBB aquaPoseClearanceBox(EntityPlayer actor, AxisAlignedBB aquaCandidate) {
        return aquaCandidate;
    }

    /** Effective physical eye projection is client-only; the server samples the actual mod getter. */
    default float physicalEyeHeight(EntityLivingBase actor, float nativeHeight) { return nativeHeight; }

    default void receiveRawPlayerPhysics(PlayerPhysicsInboundConnections.RawInboundFrame raw,
            INetHandler handler) {
        throw new IllegalStateException("Raw physics frame delivered to the common proxy");
    }

    default void receivePlayerRenderPose(PlayerRenderPoseProtocol.Packet packet, INetHandler handler) {
        throw new IllegalStateException("Client render pose packet delivered to the common proxy");
    }

    default void receiveGIntent(GIntentProtocol.Packet packet, INetHandler handler) {
        throw new IllegalStateException("Client G-intent packet delivered to the common proxy");
    }

    /** Client game-thread dispatch; common initialization never links a client implementation. */
    default void receiveRemoteBoost(BoostProtocol.Packet packet, INetHandler handler) {
        throw new IllegalStateException("Client boost packet delivered to the common proxy");
    }

    /** Called during {@code FMLPreInitializationEvent}, after core singletons exist. */
    default void preInit() {
    }

    /**
     * Applies a mass change of the local player to the client-side player
     * entity (its binding is a different object than the server-side
     * EntityPlayerMP; single player shares the JVM, so this is a direct
     * call). No-op on a dedicated server.
     */
    default void applyLocalPlayerMass(double massKg) {
    }
}
