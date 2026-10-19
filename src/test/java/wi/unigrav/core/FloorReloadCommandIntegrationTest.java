package wi.unigrav.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wi.unigrav.core.config.FloorConfigLoader;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorWorldFields;

class FloorReloadCommandIntegrationTest {
    @Test
    void realReloadFloorsCommandChecksPermissionAndPublishesTheCompiledFile(@TempDir Path directory)
            throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("world"), new UUID(0, 831), false)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 832));
            player.setPosition(8.5, 65, 8.5);
            fixture.publishWorldRoster(fixture.world());
            Path file = FloorConfigLoader.ensureDefault(directory.resolve("config"));
            var config = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
            var lifetime = FloorWorldFields.start(fixture.server(), config);
            try {
                Sender denied = new Sender(fixture.world(), fixture.server(), false);
                assertThrows(CommandException.class,
                        () -> new UnigravCommand(file).execute(fixture.server(), denied,
                                new String[] {"reload", "floors"}));
                assertTrue(denied.messages.isEmpty());
                assertEquals(1L, FloorWorldFields.captureDecision(fixture.world()).orElseThrow()
                        .configurationRevision());

                Sender admin = new Sender(fixture.world(), fixture.server(), true);
                new UnigravCommand(file).execute(fixture.server(), admin, new String[] {"reload", "floors"});
                assertEquals(2L, FloorWorldFields.captureDecision(fixture.world()).orElseThrow()
                        .configurationRevision());
                assertEquals(1, admin.messages.size());
                assertEquals("Published floors revision 2 across 1 loaded world(s) and 1 loaded chunk(s).",
                        admin.messages.getFirst().getUnformattedText());
            } finally {
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    private static final class Sender implements ICommandSender {
        private final World world;
        private final net.minecraft.server.MinecraftServer server;
        private final boolean permitted;
        private final List<ITextComponent> messages = new ArrayList<>();

        private Sender(World world, net.minecraft.server.MinecraftServer server, boolean permitted) {
            this.world = world;
            this.server = server;
            this.permitted = permitted;
        }

        @Override public String getName() { return "floor-config-test"; }
        @Override public boolean canUseCommand(int permissionLevel, String commandName) { return permitted; }
        @Override public World getEntityWorld() { return world; }
        @Override public net.minecraft.server.MinecraftServer getServer() { return server; }
        @Override public void sendMessage(ITextComponent message) { messages.add(message); }
    }
}
