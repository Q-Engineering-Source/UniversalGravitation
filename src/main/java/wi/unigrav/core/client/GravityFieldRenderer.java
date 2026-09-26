package wi.unigrav.core.client;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.SphericalHarmonics;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.floor.FloorDebugSnapshot;
import wi.unigrav.core.floor.FloorWorldFields;

/**
 * Client-side debug visualization of the gravity field, toggled with the
 * client command {@code /ugv} ({@link GravityDebugCommand}).
 *
 * <p>Renders, in the world pass ({@link RenderWorldLastEvent}):
 * <ul>
 *   <li><b>Source markers</b> — a 3-axis cross at each source's centre of
 *       mass (the "point"), plus a wireframe sphere of its characteristic
 *       size: the harmonics reference radius when present, otherwise the
 *       Plummer softening length when non-zero, otherwise a small nominal
 *       sphere. Sources with harmonics are drawn cyan, plain point masses
 *       yellow.
 *   <li><b>Field arrows</b> — a sparse grid of arrows around the player:
 *       each arrow starts at a grid sample point and points along the field
 *       acceleration there, its length encoding the magnitude on a log
 *       scale and its colour anchored at 1G = green (blue below, red above).
 * </ul>
 *
 * <p>Single player only in practice: the client shares the in-JVM
 * {@code GravityFields} singleton with the integrated server; on a
 * dedicated server the field is empty and nothing renders.
 */
public final class GravityFieldRenderer {

    /** Master toggle, flipped by {@code /ugv}. */
    public static boolean enabled = false;
    private World debugWorld;
    private Future<Optional<FloorDebugSnapshot>> pendingFloors;
    private FloorDebugSnapshot floors;
    private long nextFloorRequest;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || mc.world == null || mc.player == null || !mc.isIntegratedServerRunning()
                || debugWorld != mc.world) {
            if (pendingFloors != null) pendingFloors.cancel(false);
            pendingFloors = null;
            floors = null;
            debugWorld = mc.world;
            nextFloorRequest = 0;
            if (!enabled || mc.world == null || mc.player == null || !mc.isIntegratedServerRunning()) return;
        }
        if (pendingFloors != null && pendingFloors.isDone()) {
            try {
                floors = pendingFloors.get().orElse(null);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                floors = null;
                UnigravMod.LOGGER.warn("UGV floor snapshot interrupted", failure);
            } catch (ExecutionException failure) {
                floors = null;
                UnigravMod.LOGGER.warn("UGV floor snapshot failed", failure.getCause());
            } finally {
                pendingFloors = null;
            }
        }
        if (mc.isGamePaused() || pendingFloors != null || System.nanoTime() < nextFloorRequest) return;
        nextFloorRequest = System.nanoTime() + 500_000_000L;
        var server = mc.getIntegratedServer();
        int dimension = mc.player.dimension;
        Vec3 center = new Vec3(mc.player.posX, mc.player.posY, mc.player.posZ);
        pendingFloors = server.callFromMainThread(() -> {
            var world = server.getWorld(dimension);
            return world == null ? Optional.<FloorDebugSnapshot>empty() : FloorWorldFields.debugSnapshot(world, center);
        });
    }

    /** Arrow grid half-extent around the player, in m. */
    private static final double GRID_RADIUS = 16.0;
    /** Arrow grid spacing, in m. */
    private static final double GRID_STEP = 4.0;
    /**
     * Acceleration (m/s²) mapped to the maximum arrow length — anchored at
     * 1G (vanilla player gravity), which is also the green colour anchor.
     */
    private static final double ARROW_REF_ACCEL = GravityEntityBinder.VANILLA_PLAYER_GRAVITY;
    /** Maximum arrow length, in m. */
    private static final double ARROW_MAX_LEN = 2.0;
    /** Half-length of the source centre cross arms, in m. */
    private static final double CROSS_HALF = 2.0;
    /** Nominal sphere radius for sources with no harmonics/softening. */
    private static final double NOMINAL_RADIUS = 1.0;
    /** Sphere wireframe segment count per great circle. */
    private static final int SPHERE_SEGMENTS = 48;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (mc.world == null || player == null || !mc.isIntegratedServerRunning()) {
            return;
        }
        Entity viewer = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : player;
        float partial = event.getPartialTicks();
        double vx = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partial;
        double vy = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partial;
        double vz = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partial;
        double time = mc.world.getTotalWorldTime() * 0.05;

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GL11.glLineWidth(2.0F);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // One begin/draw pair for everything: BufferBuilder throws
        // "Already building!" on a nested begin.
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        renderSources(buffer, time);
        renderArrows(buffer, player, time);
        if (floors != null) for (var arrow : floors.arrows()) {
            renderFieldArrow(buffer, arrow.position(), arrow.gravity(), false);
            // A slight visual offset separates coincident arrows; it does not move the sampled field point.
            renderFieldArrow(buffer, arrow.position().add(new Vec3(.08, .04, .08)), arrow.magnetic(), true);
        }

        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();

        renderEntityLabels(mc, viewer, vx, vy, vz, partial);
    }

    /**
     * Floating mass/speed labels above living entities within 32 m, drawn
     * like name tags (billboarded, occluded by blocks). Mass comes from the
     * entity's binding (estimated when unbound); a ★ marks an active
     * gravity-source entity.
     */
    private static void renderEntityLabels(Minecraft mc, Entity viewer,
                                           double vx, double vy, double vz, float partial) {
        FontRenderer font = mc.fontRenderer;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.disableLighting();
        for (Entity entity : mc.world.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase) || entity == viewer
                    || entity.getDistanceSq(viewer) > 32.0 * 32.0) {
                continue;
            }
            double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
            double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial;
            double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;
            BodyPhysics physics = UnigravMod.entityBinder.bodyPhysicsOf(entity);
            double mass = physics != null ? physics.massKg()
                    : BodyPhysics.estimate(entity).massKg();
            double speed = Math.sqrt(entity.motionX * entity.motionX
                    + entity.motionY * entity.motionY
                    + entity.motionZ * entity.motionZ)
                    * GravityEntityBinder.TICKS_PER_SECOND;
            boolean isSource = mass >= UnigravConfig.entityGravityThreshold;
            String text = String.format(Locale.ROOT, "%sm=%s  v=%.1f m/s",
                    isSource ? "★ " : "", BodyPhysics.formatMass(mass), speed);
            GlStateManager.pushMatrix();
            GlStateManager.translate(ex - vx, ey + entity.height + 0.6 - vy, ez - vz);
            GL11.glRotatef(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-0.025F, -0.025F, 0.025F);
            font.drawString(text, -font.getStringWidth(text) / 2.0F, 0.0F,
                    isSource ? 0xFFFFCC00 : 0xFF66FF66, false);
            GlStateManager.popMatrix();
        }
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
    }

    /** HUD readout of the field acceleration at the player's position. */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!enabled || event.getType() != RenderGameOverlayEvent.ElementType.EXPERIENCE) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || !mc.isIntegratedServerRunning()) {
            return;
        }
        double time = mc.world.getTotalWorldTime() * 0.05;
        Vec3 a = GravityFields.get().accelerationAt(
                new Vec3(mc.player.posX, mc.player.posY, mc.player.posZ), time);
        mc.fontRenderer.drawStringWithShadow(
                String.format("[unigrav] a = (%.3f, %.3f, %.3f) m/s^2, |a| = %.3f",
                        a.x(), a.y(), a.z(), a.norm()),
                4, 4, 0xFF55FFFF);
        // Entity motion is in blocks/tick (1 block/tick = 20 m/s).
        double vx = mc.player.motionX * 20.0;
        double vy = mc.player.motionY * 20.0;
        double vz = mc.player.motionZ * 20.0;
        mc.fontRenderer.drawStringWithShadow(
                String.format("[unigrav] v = (%.3f, %.3f, %.3f) m/s, |v| = %.3f",
                        vx, vy, vz, Math.sqrt(vx * vx + vy * vy + vz * vz)),
                4, 14, 0xFF55FF55);
        BodyPhysics self = UnigravMod.entityBinder.bodyPhysicsOf(mc.player);
        double selfMass = self != null ? self.massKg()
                : BodyPhysics.estimate(mc.player).massKg();
        boolean selfSource = selfMass >= UnigravConfig.entityGravityThreshold;
        mc.fontRenderer.drawStringWithShadow(
                String.format(Locale.ROOT, "[unigrav] m = %s%s",
                        BodyPhysics.formatMass(selfMass),
                        selfSource ? " (gravity source)" : ""),
                4, 24, selfSource ? 0xFFFFCC00 : 0xFF55FF55);
        mc.fontRenderer.drawStringWithShadow(floors == null
                        ? "[unigrav] floor field: unavailable / pending (bounded debug scan)"
                        : "[unigrav] floor gravity: G scale; magnetic: PURPLE (unit response)",
                4, 34, 0xFFCC66FF);
    }

    /** Centre crosses and characteristic-size spheres for every source. */
    private void renderSources(BufferBuilder buffer, double time) {
        for (GravitySource source : GravityFields.get().sources()) {
            Vec3 centre = source.position(time);
            SphericalHarmonics harmonics = source.harmonics();
            float r, g, b;
            if (harmonics != null) {
                r = 0.2F; g = 0.9F; b = 1.0F; // cyan: extended body
            } else {
                r = 1.0F; g = 0.85F; b = 0.1F; // yellow: point mass
            }
            cross(buffer, centre, CROSS_HALF, r, g, b);
            double radius = NOMINAL_RADIUS;
            if (harmonics != null) {
                radius = harmonics.referenceRadius();
            } else if (source.softeningLength() > 0.0) {
                radius = source.softeningLength();
            }
            sphere(buffer, centre, radius, r, g, b);
        }
    }

    /** Sparse arrow grid around the player showing field direction/magnitude. */
    private void renderArrows(BufferBuilder buffer, EntityPlayerSP player, double time) {
        double baseX = Math.floor(player.posX / GRID_STEP) * GRID_STEP;
        double baseY = Math.floor(player.posY / GRID_STEP) * GRID_STEP;
        double baseZ = Math.floor(player.posZ / GRID_STEP) * GRID_STEP;
        for (double dx = -GRID_RADIUS; dx <= GRID_RADIUS; dx += GRID_STEP) {
            for (double dy = -GRID_RADIUS; dy <= GRID_RADIUS; dy += GRID_STEP) {
                for (double dz = -GRID_RADIUS; dz <= GRID_RADIUS; dz += GRID_STEP) {
                    Vec3 p = new Vec3(baseX + dx, baseY + dy, baseZ + dz);
                    Vec3 a = GravityFields.get().accelerationAt(p, time);
                    renderFieldArrow(buffer, p, a, false);
                }
            }
        }
    }

    private static void renderFieldArrow(BufferBuilder buffer, Vec3 p, Vec3 a, boolean magnetic) {
        double mag = a.norm();
        if (mag < 1.0e-6) return;
        double t = Math.min(1.0, Math.log10(1.0 + 9.0 * mag / ARROW_REF_ACCEL));
        double len = 0.2 + (ARROW_MAX_LEN - 0.2) * t;
        Vec3 tip = p.add(a.scale(len / mag));
        double rel = mag / ARROW_REF_ACCEL;
        float cr, cg, cb;
        if (rel <= 1.0) {
            float u = (float) Math.min(1.0, Math.log10(1.0 + 99.0 * rel));
            cr = 0.15F * u;
            cg = 0.30F + 0.70F * u;
            cb = 1.0F - u;
        } else {
            float u = (float) Math.min(1.0, Math.log10(1.0 + 9.0 * (rel - 1.0) / 3.0));
            cr = 0.15F + 0.85F * u;
            cg = 1.0F - 0.85F * u;
            cb = 0.0F;
        }
        if (magnetic) { cr = 0.75F; cg = 0.25F; cb = 1.0F; }
        line(buffer, p, tip, cr, cg, cb);
        arrowHead(buffer, p, tip, cr, cg, cb);
    }

    /** Two short fins at the tip, tilted back along the incoming direction. */
    private static void arrowHead(BufferBuilder buffer, Vec3 from, Vec3 tip,
                                  float r, float g, float b) {
        Vec3 dir = tip.sub(from);
        double len = dir.norm();
        if (len < 1.0e-9) {
            return;
        }
        Vec3 back = dir.scale(-0.25 / len);
        // Any vector not parallel to dir gives a perpendicular basis.
        Vec3 ref = Math.abs(dir.x()) < 0.9 * len ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 side = dir.cross(ref).normalize().scale(0.12);
        line(buffer, tip, tip.add(back).add(side), r, g, b);
        line(buffer, tip, tip.add(back).sub(side), r, g, b);
    }

    private static void cross(BufferBuilder buffer, Vec3 c, double half, float r, float g, float b) {
        line(buffer, new Vec3(c.x() - half, c.y(), c.z()), new Vec3(c.x() + half, c.y(), c.z()), r, g, b);
        line(buffer, new Vec3(c.x(), c.y() - half, c.z()), new Vec3(c.x(), c.y() + half, c.z()), r, g, b);
        line(buffer, new Vec3(c.x(), c.y(), c.z() - half), new Vec3(c.x(), c.y(), c.z() + half), r, g, b);
    }

    /** Wireframe sphere as three great circles (XY, XZ, YZ planes). */
    private static void sphere(BufferBuilder buffer, Vec3 c, double radius, float r, float g, float b) {
        Vec3 prevXY = null, prevXZ = null, prevYZ = null;
        for (int i = 0; i <= SPHERE_SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / SPHERE_SEGMENTS;
            double cos = Math.cos(angle) * radius;
            double sin = Math.sin(angle) * radius;
            Vec3 xy = new Vec3(c.x() + cos, c.y() + sin, c.z());
            Vec3 xz = new Vec3(c.x() + cos, c.y(), c.z() + sin);
            Vec3 yz = new Vec3(c.x(), c.y() + cos, c.z() + sin);
            if (prevXY != null) {
                line(buffer, prevXY, xy, r, g, b);
                line(buffer, prevXZ, xz, r, g, b);
                line(buffer, prevYZ, yz, r, g, b);
            }
            prevXY = xy; prevXZ = xz; prevYZ = yz;
        }
    }

    private static void line(BufferBuilder buffer, Vec3 a, Vec3 b, float r, float g, float bl) {
        buffer.pos(a.x(), a.y(), a.z()).color(r, g, bl, 1.0F).endVertex();
        buffer.pos(b.x(), b.y(), b.z()).color(r, g, bl, 1.0F).endVertex();
    }
}
