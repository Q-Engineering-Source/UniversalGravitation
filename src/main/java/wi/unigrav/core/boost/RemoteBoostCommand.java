package wi.unigrav.core.boost;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.player.GIntentServer;

/** Dedicated-safe, permission-gated diagnostic command for remote boost. */
public final class RemoteBoostCommand extends CommandBase {

    @Override
    public String getName() {
        return "ugboost";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ugboost <status|selftest>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length != 1) {
            throw new CommandException(getUsage(sender));
        }
        if ("status".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new TextComponentString(RemoteBoostServer.INSTANCE.diagnostics()));
            if (sender instanceof EntityPlayerMP player) {
                String intent = GIntentServer.INSTANCE.observe(player).map(value ->
                        (value.active() ? "ON" : "OFF") + ", source=" + value.source()
                                + ", session=" + value.key().sessionId() + ", dimension=" + value.key().dimension()
                                + ", epoch=" + value.sourceEpoch() + ", revision=" + value.revision())
                        .orElse("UNKNOWN");
                sender.sendMessage(new TextComponentString("ugphys1 Personal G: " + intent
                        + "; physical owner: not active"));
            }
            return;
        }
        if ("selftest".equalsIgnoreCase(args[0])) {
            try {
                RemoteBoostDiagnostics.Result result = RemoteBoostNativeDiagnostics.runHeadlessSelfTest();
                sender.sendMessage(new TextComponentString("ugboost selftest: checks="
                        + result.checks() + "; " + result.label()));
                return;
            } catch (RuntimeException failure) {
                UnigravMod.LOGGER.error("ugboost headless selftest failed", failure);
                throw new CommandException("ugboost selftest failed: " + failure.getMessage());
            }
        }
        throw new CommandException(getUsage(sender));
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return sender.canUseCommand(getRequiredPermissionLevel(), getName());
    }

    @Override
    public java.util.List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
            String[] args, BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "status", "selftest")
                : java.util.Collections.emptyList();
    }
}
