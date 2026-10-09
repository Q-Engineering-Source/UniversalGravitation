package wi.unigrav.proxy;

import net.minecraft.network.INetHandler;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.function.Consumer;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;
/** Side-specific hooks, invoked from the mod's lifecycle handlers. */
public interface IProxy {

    /** Entire native motion call site; input/camera ownership remains with UIE. */
    default void runLegacyMotion(EntityLivingBase actor, Runnable original) { original.run(); }

    default void runNativePose(EntityLivingBase actor, Runnable original) { original.run(); }

    /** The actual Aqua growth move call site, inside its controlled resize callback. */
    default void runPoseMove(EntityLivingBase actor, MoverType type, Vec3d delta, Consumer<Vec3d> original) {
        original.accept(delta);
    }

    /** Effective physical eye projection is client-only; the server samples the actual mod getter. */
    default float physicalEyeHeight(EntityLivingBase actor, float nativeHeight) { return nativeHeight; }

    default void receivePlayerPhysics(PlayerPhysicsProtocol.Packet packet, INetHandler handler) {
        throw new IllegalStateException("Client physics packet delivered to the common proxy");
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
