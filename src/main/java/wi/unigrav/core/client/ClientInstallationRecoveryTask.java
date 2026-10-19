package wi.unigrav.core.client;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import wi.unigrav.core.player.PlayerPhysicsNetwork;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Bounded recovery of an already retired task. Sending never completes the physical installation. */
final class ClientInstallationRecoveryTask {
    private final WeakReference<EntityPlayer> actor;
    private final WeakReference<World> world;
    private final WeakReference<NetHandlerPlayClient> handler;
    private final WeakReference<NetworkManager> manager;
    private final WeakReference<PlayerPhysicsInboundConnections.ConnectionHandle> connection;
    private final PlayerPhysicsProtocol.Packet request;
    private int ticks;
    private int attempts;
    private int nextAttemptTick = 20;
    private boolean finished;

    record Result(boolean finished, boolean exhausted, Optional<Throwable> failure) {}

    ClientInstallationRecoveryTask(EntityPlayer actor, World world, NetHandlerPlayClient handler,
            NetworkManager manager, PlayerPhysicsInboundConnections.ConnectionHandle connection,
            PlayerPhysicsProtocol.Packet request) {
        this.actor = new WeakReference<>(Objects.requireNonNull(actor, "actor"));
        this.world = new WeakReference<>(Objects.requireNonNull(world, "world"));
        this.handler = new WeakReference<>(Objects.requireNonNull(handler, "handler"));
        this.manager = new WeakReference<>(Objects.requireNonNull(manager, "manager"));
        this.connection = new WeakReference<>(Objects.requireNonNull(connection, "connection"));
        this.request = Objects.requireNonNull(request, "request");
        if (request.kind() != PlayerPhysicsProtocol.Kind.RECOVER
                || !actor.getUniqueID().equals(request.scope().playerId())
                || actor.dimension != request.scope().dimension() || actor.world != world
                || handler.getNetworkManager() != manager || manager.getNetHandler() != handler)
            throw new IllegalArgumentException("Recovery requires its failed actor and actual connection identity");
    }

    boolean matches(EntityPlayer actualActor, World actualWorld, NetHandlerPlayClient actualHandler,
            NetworkManager actualManager, PlayerPhysicsInboundConnections.ConnectionHandle actualConnection) {
        return actualActor != null && actualWorld != null && actualHandler != null && actualManager != null
                && actualConnection != null && actor.get() == actualActor && world.get() == actualWorld
                && actualActor.world == actualWorld && actualActor.dimension == request.scope().dimension()
                && handler.get() == actualHandler && manager.get() == actualManager
                && connection.get() == actualConnection;
    }

    int attempts() { return attempts; }
    String identity() {
        return request.scope() + " proposal=" + request.offerSequence() + " sequence=" + request.sequence();
    }

    Result tick(boolean exactLifetime, boolean maySend) {
        return tick(exactLifetime, maySend, this::sendToServer);
    }

    private void sendToServer(PlayerPhysicsProtocol.Packet packet) {
        NetworkManager exactManager = manager.get();
        NetHandlerPlayClient exactHandler = handler.get();
        if (exactManager == null || exactHandler == null || exactManager.getNetHandler() != exactHandler
                || exactHandler.getNetworkManager() != exactManager)
            throw new IllegalStateException("Installation RECOVER lost its exact client connection before send");
        // SimpleNetworkWrapper's void send cannot confirm its asynchronous write. Prove the normal
        // FML route first; only a later fully installed OWNED can establish physical recovery.
        NetworkManager routed = FMLCommonHandler.instance().getClientToServerNetworkManager();
        if (routed != exactManager)
            throw new IllegalStateException("Installation RECOVER has no exact normal FML TOSERVER route");
        PlayerPhysicsNetwork.sendToServer(packet);
    }

    /** The caller verifies the actual lifetime and retained fence at every END tick. */
    Result tick(boolean exactLifetime, boolean maySend, Consumer<PlayerPhysicsProtocol.Packet> outbound) {
        Objects.requireNonNull(outbound, "outbound");
        if (finished) return new Result(true, false, Optional.empty());
        if (!exactLifetime) {
            finished = true;
            return new Result(true, false, Optional.empty());
        }
        ticks++;
        if (ticks >= 80) {
            finished = true;
            return new Result(true, true, Optional.empty());
        }
        if (!maySend || attempts >= 3 || ticks < nextAttemptTick)
            return new Result(false, false, Optional.empty());
        attempts++;
        nextAttemptTick = ticks + 20;
        try {
            outbound.accept(request);
            return new Result(false, false, Optional.empty());
        } catch (Throwable failure) {
            // The exact same RECOVER identity is idempotent at the server; never mint a new retry.
            finished = attempts >= 3;
            return new Result(finished, finished, Optional.of(failure));
        }
    }
}
