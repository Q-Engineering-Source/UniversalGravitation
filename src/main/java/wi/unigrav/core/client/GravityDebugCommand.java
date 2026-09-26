package wi.unigrav.core.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/**
 * Client-side debug command {@code /ugv}: toggles the gravity-field
 * visualization ({@link GravityFieldRenderer}). Registered with the
 * {@code ClientCommandHandler}, runs entirely on the client.
 */
public final class GravityDebugCommand extends CommandBase {

    @Override
    public String getName() {
        return "ugv";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ugv [on|off]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length > 0) {
            GravityFieldRenderer.enabled = args[0].equalsIgnoreCase("on");
        } else {
            GravityFieldRenderer.enabled = !GravityFieldRenderer.enabled;
        }
        sender.sendMessage(new TextComponentString(
                "[unigrav] gravity visualization "
                        + (GravityFieldRenderer.enabled ? "ON" : "OFF")));
    }
}
