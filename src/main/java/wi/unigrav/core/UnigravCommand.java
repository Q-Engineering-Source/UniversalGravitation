package wi.unigrav.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.field.RegionGravityContribution;
import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;

/**
 * Debug/admin command:
 * <ul>
 *   <li>{@code /unigrav sources} — list the registered gravity sources and
 *       contributions (permission 0);
 *   <li>{@code /unigrav accel} — print the field acceleration at the
 *       command sender's position (permission 0);
 *   <li>{@code /unigrav world get} — show the world gravity (permission 2);
 *   <li>{@code /unigrav world set <ax> <ay> <az>} — set it, in m/s²;
 *   <li>{@code /unigrav world set up|down [strength]} — directional preset,
 *       strength defaults to the config value;
 *   <li>{@code /unigrav world enable|disable} — toggle without losing the
 *       configured vector;
 *   <li>{@code /unigrav world reset} — back to the config default;
 *   <li>{@code /unigrav mass [kg]} — query or set the mass of the entity
 *       under the crosshair; with a block under the crosshair, spawns a
 *       falling-block copy with that mass (permission 2). Mass scales
 *       knockback (inertia) and buoyancy, and at
 *       {@code entity_gravity_threshold} the entity becomes a real gravity
 *       source;
 *   <li>{@code /unigrav chunk <get|set <ax> <ay> <az>|clear>} — per-chunk
 *       gravity override of the sender's chunk column, added to the world
 *       gravity; persisted (permission 2);
 *   <li>{@code /unigrav pos1|pos2 [x y z]} — WorldEdit-style selection
 *       corners (crosshair block when no coords; permission 2);
 *   <li>{@code /unigrav region add box|sphere|cyl dir|center …} — bounded
 *       gravity regions (box from pos1/pos2; sphere centred at pos1 with
 *       radius from pos2 or an argument; cylinder axis pos1→pos2 with a
 *       radius argument), either directional (vector, G suffix allowed) or
 *       centripetal (strength toward the centre/axis); persisted and
 *       additive with world/chunk gravity;
 *   <li>{@code /unigrav region list|remove <name>|clear} — manage regions;
 * </ul>
 * All changes go through {@link WorldGravityManager#setWorldGravity}, so
 * they persist and fire {@code WorldGravityChangeEvent}.
 */
public final class UnigravCommand extends CommandBase {

    @Override
    public String getName() {
        return "unigrav";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/unigrav <sources|accel|mass [self] [kg]|chunk <get|set|clear>"
                + "|world <get|set|enable|disable|reset>|pos1|pos2"
                + "|region <add|list|remove|clear>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // sources/accel are debug aids; "world" is checked inside
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length == 0) {
            throw new CommandException(getUsage(sender));
        }
        switch (args[0]) {
            case "sources" -> executeSources(sender);
            case "accel" -> executeAccel(sender);
            case "world" -> executeWorld(sender, args);
            case "chunk" -> executeChunk(sender, args);
            case "mass" -> executeMass(sender, args);
            case "pos1" -> executePos(sender, args, true);
            case "pos2" -> executePos(sender, args, false);
            case "region" -> executeRegion(sender, args);
            default -> throw new CommandException(getUsage(sender));
        }
    }

    /** WorldEdit-style selection corners, per player. */
    private static final Map<UUID, BlockPos> POS1 = new HashMap<>();
    private static final Map<UUID, BlockPos> POS2 = new HashMap<>();

    /** Sets a selection corner: explicit coords, else the crosshair block. */
    private void executePos(ICommandSender sender, String[] args, boolean first)
            throws CommandException {
        if (!sender.canUseCommand(2, "unigrav.pos")) {
            throw new CommandException("commands.generic.permission");
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        BlockPos pos;
        if (args.length >= 4) {
            pos = new BlockPos(parseInt(args[1]), parseInt(args[2]), parseInt(args[3]));
        } else {
            pos = raycastBlock(player);
            if (pos == null) {
                throw new CommandException("No block under the crosshair (64 m reach);"
                        + " use /unigrav " + (first ? "pos1" : "pos2") + " <x> <y> <z>.");
            }
        }
        (first ? POS1 : POS2).put(player.getUniqueID(), pos);
        sender.sendMessage(new TextComponentString(String.format(
                "%s = (%d, %d, %d)", first ? "pos1" : "pos2",
                pos.getX(), pos.getY(), pos.getZ())));
    }

    /** Bounded gravity regions built from the pos1/pos2 selection. */
    private void executeRegion(ICommandSender sender, String[] args)
            throws CommandException {
        if (!sender.canUseCommand(2, "unigrav.region")) {
            throw new CommandException("commands.generic.permission");
        }
        if (!UnigravMod.regionGravity.isLoaded()) {
            throw new CommandException("No world loaded");
        }
        if (args.length < 2) {
            throw new CommandException("/unigrav region <add|list|remove <name>|clear>");
        }
        switch (args[1]) {
            case "list" -> {
                Map<String, RegionGravityContribution> regions =
                        UnigravMod.regionGravity.regions();
                if (regions.isEmpty()) {
                    sender.sendMessage(new TextComponentString("No gravity regions."));
                    return;
                }
                sender.sendMessage(new TextComponentString(
                        regions.size() + " gravity region(s):"));
                regions.forEach((name, region) -> sender.sendMessage(
                        new TextComponentString("  " + name + ": " + describe(region))));
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new CommandException("/unigrav region remove <name>");
                }
                if (UnigravMod.regionGravity.remove(args[2])) {
                    sender.sendMessage(new TextComponentString(
                            "Removed gravity region " + args[2] + "."));
                } else {
                    throw new CommandException("No region named " + args[2] + ".");
                }
            }
            case "clear" -> sender.sendMessage(new TextComponentString(String.format(
                    "Removed %d gravity region(s).", UnigravMod.regionGravity.clear())));
            case "add" -> executeRegionAdd(sender, args);
            default -> throw new CommandException(
                    "/unigrav region <add|list|remove <name>|clear>");
        }
    }

    /**
     * {@code region add box dir <gx> <gy> <gz> | box center <strength> |
     * sphere dir <gx> <gy> <gz> [radius] | sphere center <strength> [radius] |
     * cyl dir <radius> <gx> <gy> <gz> | cyl center <radius> <strength>}
     * — box corners from pos1/pos2, sphere centre at pos1 (radius from pos2
     * distance when omitted), cylinder axis pos1→pos2.
     */
    private void executeRegionAdd(ICommandSender sender, String[] args)
            throws CommandException {
        boolean dir = args.length >= 4 && args[3].equals("dir");
        int required = switch (args.length >= 3 ? args[2] : "") {
            case "box" -> dir ? 7 : 5;
            case "sphere" -> dir ? 7 : 5; // optional radius may follow
            case "cyl" -> dir ? 8 : 6;
            default -> 4;
        };
        if (args.length < required) {
            throw new CommandException(
                    "/unigrav region add <box|sphere|cyl> <dir|center> …");
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        RegionGravityContribution.Shape shape;
        RegionGravityContribution.Mode mode;
        switch (args[2]) {
            case "box" -> {
                RegionGravityContribution.Box box = RegionGravityContribution.Box.between(
                        requireSelection(player, true), requireSelection(player, false));
                // Inclusive of both corner blocks: extend max by one.
                shape = RegionGravityContribution.Box.between(box.min(),
                        box.max().add(new Vec3(1.0, 1.0, 1.0)));
                mode = args[3].equals("dir")
                        ? new RegionGravityContribution.Directional(new Vec3(
                                parseAcceleration(args[4]), parseAcceleration(args[5]),
                                parseAcceleration(args[6])))
                        : new RegionGravityContribution.Centripetal(
                                parseAcceleration(args[4]));
            }
            case "sphere" -> {
                Vec3 center = requireSelection(player, true);
                int radiusIndex;
                if (args[3].equals("dir")) {
                    mode = new RegionGravityContribution.Directional(new Vec3(
                            parseAcceleration(args[4]), parseAcceleration(args[5]),
                            parseAcceleration(args[6])));
                    radiusIndex = 7;
                } else {
                    mode = new RegionGravityContribution.Centripetal(
                            parseAcceleration(args[4]));
                    radiusIndex = 5;
                }
                double radius = args.length > radiusIndex
                        ? parseDouble(args[radiusIndex])
                        : requireSelection(player, false).sub(center).norm();
                shape = new RegionGravityContribution.Sphere(center, radius);
            }
            case "cyl" -> {
                Vec3 a = requireSelection(player, true);
                Vec3 b = requireSelection(player, false);
                double radius = parseDouble(args[4]);
                if (args[3].equals("dir")) {
                    mode = new RegionGravityContribution.Directional(new Vec3(
                            parseAcceleration(args[5]), parseAcceleration(args[6]),
                            parseAcceleration(args[7])));
                } else {
                    mode = new RegionGravityContribution.Centripetal(
                            parseAcceleration(args[5]));
                }
                shape = new RegionGravityContribution.Cylinder(a, b, radius);
            }
            default -> throw new CommandException(
                    "/unigrav region add <box|sphere|cyl> <dir|center> …");
        }
        String name = UnigravMod.regionGravity.add(
                new RegionGravityContribution(shape, mode));
        if (name == null) {
            throw new CommandException("No world loaded");
        }
        sender.sendMessage(new TextComponentString(
                "Added gravity region " + name + ": " + describe(
                        new RegionGravityContribution(shape, mode))
                        + " (stacks with world/chunk gravity)."));
    }

    /** The player's pos1/pos2 selection as a block-centre vector, or throws. */
    private static Vec3 requireSelection(EntityPlayerMP player, boolean first)
            throws CommandException {
        BlockPos pos = (first ? POS1 : POS2).get(player.getUniqueID());
        if (pos == null) {
            throw new CommandException("Set " + (first ? "pos1" : "pos2")
                    + " first (/unigrav " + (first ? "pos1" : "pos2") + ").");
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** The block under the player's crosshair (64 m), or null. */
    private static BlockPos raycastBlock(EntityPlayerMP player) {
        Vec3d eyes = player.getPositionEyes(1.0F);
        Vec3d end = eyes.add(player.getLook(1.0F).scale(64.0));
        RayTraceResult hit = player.world.rayTraceBlocks(eyes, end, false, false, true);
        return hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK
                ? hit.getBlockPos() : null;
    }

    /** One-line description for {@code region list} / add feedback. */
    private static String describe(RegionGravityContribution region) {
        String mode = switch (region.mode()) {
            case RegionGravityContribution.Centripetal c -> String.format(Locale.ROOT,
                    "center %.4g m/s^2 (%.3g G)", c.strength(),
                    Math.abs(c.strength()) / GravityEntityBinder.VANILLA_PLAYER_GRAVITY);
            case RegionGravityContribution.Directional d -> String.format(Locale.ROOT,
                    "dir (%.4g, %.4g, %.4g) m/s^2 (%.3g G)",
                    d.accel().x(), d.accel().y(), d.accel().z(),
                    d.accel().norm() / GravityEntityBinder.VANILLA_PLAYER_GRAVITY);
            default -> region.mode().toString();
        };
        String shape = switch (region.shape()) {
            case RegionGravityContribution.Box b -> String.format(Locale.ROOT,
                    "box (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f)",
                    b.min().x(), b.min().y(), b.min().z(),
                    b.max().x(), b.max().y(), b.max().z());
            case RegionGravityContribution.Sphere s -> String.format(Locale.ROOT,
                    "sphere (%.1f, %.1f, %.1f) r=%.2f",
                    s.center().x(), s.center().y(), s.center().z(), s.radius());
            case RegionGravityContribution.Cylinder c -> String.format(Locale.ROOT,
                    "cyl (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f) r=%.2f",
                    c.axisA().x(), c.axisA().y(), c.axisA().z(),
                    c.axisB().x(), c.axisB().y(), c.axisB().z(), c.radius());
            default -> region.shape().toString();
        };
        return shape + "  " + mode;
    }

    /** Per-chunk gravity override of the chunk the sender stands in. */
    private void executeChunk(ICommandSender sender, String[] args) throws CommandException {
        if (!sender.canUseCommand(2, "unigrav.chunk")) {
            throw new CommandException("commands.generic.permission");
        }
        if (!UnigravMod.chunkGravity.isLoaded()) {
            throw new CommandException("No world loaded");
        }
        if (args.length < 2) {
            throw new CommandException("/unigrav chunk <get|set <ax> <ay> <az>|clear>");
        }
        int chunkX = (int) Math.floor(sender.getPosition().getX()) >> 4;
        int chunkZ = (int) Math.floor(sender.getPosition().getZ()) >> 4;
        switch (args[1]) {
            case "get" -> {
                Vec3 acc = UnigravMod.chunkGravity.get(chunkX, chunkZ);
                sender.sendMessage(new TextComponentString(acc == null
                        ? String.format("Chunk (%d, %d): no override.", chunkX, chunkZ)
                        : String.format(Locale.ROOT,
                                "Chunk (%d, %d) gravity: (%.4g, %.4g, %.4g) m/s^2 (%.3g G)",
                                chunkX, chunkZ, acc.x(), acc.y(), acc.z(),
                                acc.norm() / GravityEntityBinder.VANILLA_PLAYER_GRAVITY)));
            }
            case "set" -> {
                if (args.length != 5) {
                    throw new CommandException("/unigrav chunk set <ax> <ay> <az>");
                }
                Vec3 target = new Vec3(parseAcceleration(args[2]),
                        parseAcceleration(args[3]), parseAcceleration(args[4]));
                if (UnigravMod.chunkGravity.set(chunkX, chunkZ, target)) {
                    sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                            "Chunk (%d, %d) gravity set to (%.4g, %.4g, %.4g) m/s^2 (%.3g G);"
                                    + " adds to world gravity.",
                            chunkX, chunkZ, target.x(), target.y(), target.z(),
                            target.norm() / GravityEntityBinder.VANILLA_PLAYER_GRAVITY)));
                }
            }
            case "clear" -> {
                if (UnigravMod.chunkGravity.set(chunkX, chunkZ, null)) {
                    sender.sendMessage(new TextComponentString(
                            String.format("Chunk (%d, %d) gravity override cleared.",
                                    chunkX, chunkZ)));
                }
            }
            default -> throw new CommandException("/unigrav chunk <get|set <ax> <ay> <az>|clear>");
        }
    }

    /**
     * Raycasts the sender's crosshair (64 m reach): an entity hit gets its
     * mass queried/set; a block hit spawns a falling-block copy of it with
     * the requested mass (the original block is left in place). Setting a
     * block's mass directly is meaningless — static blocks are not bodies.
     */
    private void executeMass(ICommandSender sender, String[] args) throws CommandException {
        if (!sender.canUseCommand(2, "unigrav.mass")) {
            throw new CommandException("commands.generic.permission");
        }
        // "/unigrav mass self [kg]" — the sender's own mass.
        if (args.length >= 2 && args[1].equalsIgnoreCase("self")) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            Double newMass = args.length >= 3 ? parseMass(args[2]) : null;
            BodyPhysics old = UnigravMod.entityBinder.bodyPhysicsOf(player);
            double volume = old != null ? old.volumeM3()
                    : BodyPhysics.estimate(player).volumeM3();
            if (newMass == null) {
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "Your mass: %s, volume %.4g m^3.",
                        BodyPhysics.formatMass(old != null ? old.massKg()
                                : BodyPhysics.estimate(player).massKg()), volume)));
            } else {
                UnigravMod.entityBinder.setBodyPhysics(
                        player, new BodyPhysics(newMass, volume));
                // Single player: the client-side player entity is a separate
                // object with its own binding — mirror the change to it.
                UnigravMod.proxy.applyLocalPlayerMass(newMass);
                boolean isSource = newMass >= UnigravConfig.entityGravityThreshold;
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "Your mass set to %s (volume %.4g m^3 kept)%s. Active entity sources: %d",
                        BodyPhysics.formatMass(newMass), volume,
                        isSource ? " — you are now a walking gravity source" : "",
                        EntityGravitySources.activeCount())));
            }
            return;
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        double reach = 64.0;
        Vec3d eyes = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        Vec3d end = eyes.add(look.scale(reach));
        RayTraceResult blockHit = player.world.rayTraceBlocks(eyes, end, false, false, true);
        double blockDist = blockHit != null && blockHit.typeOfHit == RayTraceResult.Type.BLOCK
                ? eyes.distanceTo(blockHit.hitVec) : Double.MAX_VALUE;
        Entity entityHit = null;
        double entityDist = blockDist; // blocks occlude entities behind them
        AxisAlignedBB searchBox = player.getEntityBoundingBox()
                .expand(look.x * reach, look.y * reach, look.z * reach).grow(1.0);
        for (Entity candidate : player.world.getEntitiesWithinAABBExcludingEntity(player, searchBox)) {
            AxisAlignedBB box = candidate.getEntityBoundingBox()
                    .grow(candidate.getCollisionBorderSize());
            RayTraceResult intercept = box.calculateIntercept(eyes, end);
            if (intercept != null) {
                double dist = eyes.distanceTo(intercept.hitVec);
                if (dist < entityDist) {
                    entityDist = dist;
                    entityHit = candidate;
                }
            }
        }
        Double newMass = args.length >= 2 ? parseMass(args[1]) : null;
        if (entityHit != null) {
            BodyPhysics old = UnigravMod.entityBinder.bodyPhysicsOf(entityHit);
            double volume = old != null ? old.volumeM3() : BodyPhysics.estimate(entityHit).volumeM3();
            if (newMass == null) {
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "%s: mass=%s, volume=%.4g m^3%s",
                        entityHit.getName(),
                        BodyPhysics.formatMass(old != null ? old.massKg()
                                : BodyPhysics.estimate(entityHit).massKg()),
                        volume, old == null ? " (estimated, unbound)" : "")));
            } else {
                UnigravMod.entityBinder.setBodyPhysics(
                        entityHit, new BodyPhysics(newMass, volume));
                boolean isSource = newMass >= UnigravConfig.entityGravityThreshold;
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "%s: mass set to %s (volume %.4g m^3 kept)%s.",
                        entityHit.getName(), BodyPhysics.formatMass(newMass), volume,
                        isSource ? " — now a gravity source pulling nearby entities" : "")));
            }
            return;
        }
        if (blockHit != null && blockHit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            IBlockState state = player.world.getBlockState(pos);
            if (newMass == null) {
                sender.sendMessage(new TextComponentString(
                        "Block " + state.getBlock().getRegistryName() + " at " + pos
                                + ": static blocks have no mass; use /unigrav mass <kg>"
                                + " to spawn a falling copy with that mass."));
                return;
            }
            EntityFallingBlock falling = new EntityFallingBlock(player.world,
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state);
            player.world.spawnEntity(falling);
            BodyPhysics physics = new BodyPhysics(
                    newMass, BodyPhysics.estimate(falling).volumeM3());
            UnigravMod.entityBinder.setBodyPhysics(falling, physics);
            sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                    "Spawned a falling copy of %s with mass %s (original block kept).",
                    state.getBlock().getRegistryName(), BodyPhysics.formatMass(newMass))));
            return;
        }
        throw new CommandException("No entity or block under the crosshair (64 m reach).");
    }

    /**
     * Parses a mass with an optional unit suffix: plain number or "kg" =
     * kilograms, "g" = grams, "mg" = milligrams, "t" = metric tonnes
     * (e.g. "500g", "1.5t", "200mg"). Feedback is auto-formatted back into
     * the same unit system via {@link BodyPhysics#formatMass}.
     */
    private static double parseMass(String token) throws CommandException {
        String lower = token.toLowerCase(Locale.ROOT);
        double factor = 1.0;
        if (lower.endsWith("mg")) {
            factor = 1.0e-6;
            lower = lower.substring(0, lower.length() - 2);
        } else if (lower.endsWith("kg")) {
            lower = lower.substring(0, lower.length() - 2);
        } else if (lower.endsWith("g")) {
            factor = 1.0e-3;
            lower = lower.substring(0, lower.length() - 1);
        } else if (lower.endsWith("t")) {
            factor = 1.0e3;
            lower = lower.substring(0, lower.length() - 1);
        }
        double kg = parseDouble(lower) * factor;
        if (kg <= 0.0) {
            throw new CommandException("Mass must be positive (suffixes: mg, g, kg, t).");
        }
        // Above ~1e15 kg the entity-source pull saturates the 500 m/s safety
        // clamp several chunks out: everything in range is flung onto the
        // source within a tick (ramming damage kills it) and a PLAYER source
        // yeets the player across chunks faster than they load (live hang,
        // exit 0xCFFFFFFF). 1e15 still gives dramatic visible attraction.
        if (kg > 1.0e15) {
            throw new CommandException(
                    "Mass capped at 1e15 kg (beyond that the 500 m/s safety clamp"
                            + " just teleports everything onto the source).");
        }
        return kg;
    }

    private void executeSources(ICommandSender sender) {
        Map<GravityField.Id, GravitySource> snapshot = GravityFields.get().snapshot();
        Map<GravityField.Id, GravityContribution> contributions =
                GravityFields.get().contributions();
        if (snapshot.isEmpty() && contributions.isEmpty()) {
            sender.sendMessage(new TextComponentString("No gravity sources registered."));
            return;
        }
        sender.sendMessage(new TextComponentString(
                (snapshot.size() + contributions.size()) + " gravity field entrie(s):"));
        double time = sender.getEntityWorld().getTotalWorldTime()
                * GravityEntityBinder.TICK_SECONDS;
        snapshot.forEach((id, source) -> {
            Vec3 pos = source.position(time);
            sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                    "  %s  mu=%.6g m^3/s^2  pos=(%.2f, %.2f, %.2f)%s",
                    id, source.gravitationalParameter(), pos.x(), pos.y(), pos.z(),
                    source.harmonics() != null
                            ? "  harmonics(deg=" + source.harmonics().maxDegree() + ")"
                            : "")));
        });
        contributions.forEach((id, contribution) -> {
            if (contribution instanceof UniformGravityContribution uniform) {
                Vec3 acc = uniform.acceleration();
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "  %s  uniform (%.4g, %.4g, %.4g) m/s^2",
                        id, acc.x(), acc.y(), acc.z())));
            } else {
                sender.sendMessage(new TextComponentString(
                        "  " + id + "  contribution " + contribution.getClass().getSimpleName()));
            }
        });
    }

    private void executeAccel(ICommandSender sender) {
        Vec3d pos = sender.getPositionVector();
        double time = sender.getEntityWorld().getTotalWorldTime()
                * GravityEntityBinder.TICK_SECONDS;
        Vec3 acc = GravityFields.get().accelerationAt(
                new Vec3(pos.x, pos.y, pos.z), time);
        sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                "Field acceleration at (%.2f, %.2f, %.2f): (%.4g, %.4g, %.4g) m/s^2, |a| = %.4g m/s^2",
                pos.x, pos.y, pos.z, acc.x(), acc.y(), acc.z(), acc.norm())));
    }

    private void executeWorld(ICommandSender sender, String[] args) throws CommandException {
        if (!sender.canUseCommand(2, "unigrav.world")) {
            throw new CommandException("commands.generic.permission");
        }
        WorldGravityManager manager = UnigravMod.worldGravity;
        if (!manager.isLoaded()) {
            throw new CommandException("No world loaded");
        }
        if (args.length < 2) {
            throw new CommandException("/unigrav world <get|set|enable|disable|reset>");
        }
        switch (args[1]) {
            case "get" -> {
                Vec3 acc = manager.getAcceleration();
                sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                        "World gravity: (%.4g, %.4g, %.4g) m/s^2, |a| = %.4g m/s^2 (%.3g G), %s",
                        acc.x(), acc.y(), acc.z(), acc.norm(),
                        acc.norm() / GravityEntityBinder.VANILLA_PLAYER_GRAVITY,
                        manager.isEnabled() ? "enabled" : "disabled")));
            }
            case "set" -> executeWorldSet(sender, manager, args);
            case "enable" -> {
                if (manager.setWorldGravity(null, Boolean.TRUE)) {
                    sender.sendMessage(new TextComponentString("World gravity enabled."));
                }
            }
            case "disable" -> {
                if (manager.setWorldGravity(null, Boolean.FALSE)) {
                    sender.sendMessage(new TextComponentString("World gravity disabled."));
                }
            }
            case "reset" -> {
                if (manager.resetToDefault()) {
                    sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                            "World gravity reset to config default (0, -%.4g, 0) m/s^2.",
                            UnigravConfig.worldGravityStrength)));
                }
            }
            default -> throw new CommandException("/unigrav world <get|set|enable|disable|reset>");
        }
    }

    private void executeWorldSet(ICommandSender sender, WorldGravityManager manager, String[] args)
            throws CommandException {
        Vec3 target;
        if (args.length == 5) {
            target = new Vec3(parseAcceleration(args[2]), parseAcceleration(args[3]),
                    parseAcceleration(args[4]));
        } else if (args.length == 3 || args.length == 4) {
            double strength = args.length == 4
                    ? parseAcceleration(args[3]) : UnigravConfig.worldGravityStrength;
            target = switch (args[2]) {
                case "up" -> new Vec3(0.0, strength, 0.0);
                case "down" -> new Vec3(0.0, -strength, 0.0);
                default -> throw new CommandException(
                        "/unigrav world set <ax> <ay> <az> | up|down [strength]");
            };
        } else {
            throw new CommandException("/unigrav world set <ax> <ay> <az> | up|down [strength]");
        }
        if (manager.setWorldGravity(target, null)) {
            sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
                    "World gravity set to (%.4g, %.4g, %.4g) m/s^2 (%.3g G).",
                    target.x(), target.y(), target.z(),
                    target.norm() / GravityEntityBinder.VANILLA_PLAYER_GRAVITY)));
        }
    }

    /**
     * Parses an acceleration token: plain numbers are m/s²; a trailing
     * {@code G}/{@code g} means multiples of vanilla player gravity
     * (1G = {@value GravityEntityBinder#VANILLA_PLAYER_GRAVITY} m/s²).
     */
    private static double parseAcceleration(String token) throws CommandException {
        if (token.regionMatches(true, token.length() - 1, "G", 0, 1)) {
            String number = token.substring(0, token.length() - 1);
            return parseDouble(number.isEmpty() ? "1" : number)
                    * GravityEntityBinder.VANILLA_PLAYER_GRAVITY;
        }
        return parseDouble(token);
    }
}
