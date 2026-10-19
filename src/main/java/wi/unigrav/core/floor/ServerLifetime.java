package wi.unigrav.core.floor;

import java.util.Objects;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Identity token for exactly one server lifetime, even when a server object is reused by a fixture. */
public final class ServerLifetime {
    private final MinecraftServer server;
    private boolean active = true;

    ServerLifetime(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean isActive() {
        return active;
    }

    public boolean owns(WorldServer world) {
        return active && world != null && world.getMinecraftServer() == server;
    }

    void retire() {
        active = false;
    }
}
