package wi.unigrav.core.client.camera;

import java.util.Objects;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.camera.CameraHit;
import neofontrender.addons.api.camera.CameraMeasurement;
import neofontrender.addons.api.camera.CameraPickingPurpose;
import neofontrender.addons.api.camera.CameraPickingRequest;
import neofontrender.addons.api.camera.CameraVector;

import wi.unigrav.api.math.Vec3;

/** Native same-frame picking; the overlay consumes the exact hit, never an invented box face. */
final class SixDofGroundFacePreview {
    private static final SixDofSceneDepthOverlay OVERLAY = new SixDofSceneDepthOverlay();
    private static boolean reloadListenerInstalled;

    private SixDofGroundFacePreview() {
    }

    /** The process-lifetime listener owns no camera adapter and performs no GL calls. */
    static void installReloadListener() {
        if (!reloadListenerInstalled) {
            if (!(Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager manager)) {
                throw new IllegalStateException("Ground preview requires a reloadable resource manager");
            }
            manager.registerReloadListener(ignored -> OVERLAY.invalidate());
            reloadListenerInstalled = true;
        }
    }

    /** Render-tick cleanup also runs in menus, when no world-render event is produced. */
    static void lifecycle(Object world) {
        OVERLAY.lifecycle(world);
    }

    static void invalidate() {
        OVERLAY.invalidate();
    }

    static void clear() {
        OVERLAY.clear();
    }

    static String depthStatus() {
        return OVERLAY.status();
    }

    static Target sample(float partialTicks) {
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.world;
        if (world == null) {
            return null;
        }
        CameraMeasurement measurement = CameraApi.measure(partialTicks);
        if (measurement.sampleId() <= 0L) {
            return null;
        }
        CameraVector cameraPosition = measurement.frame().position();
        CameraVector cameraForward = measurement.frame().viewBasis().forward();
        CameraHit hit = SixDofGroundPickingRay.firstHit(
                new Vec3(cameraPosition.x, cameraPosition.y, cameraPosition.z),
                new Vec3(cameraForward.x, cameraForward.y, cameraForward.z),
                (origin, direction, distance) -> {
                    CameraHit candidate = CameraApi.pick(new CameraPickingRequest(
                            new CameraVector(origin.x(), origin.y(), origin.z()),
                            new CameraVector(direction.x(), direction.y(), direction.z()),
                            distance, CameraPickingPurpose.MEASUREMENT, false, false));
                    return candidate != null && candidate.isBlock() ? candidate : null;
                });
        if (hit == null || !hit.isBlock() || hit.blockPosition() == null || hit.side() == null
                || hit.nativeResult() == null || hit.nativeResult().hitVec == null) {
            return null;
        }
        BlockPos blockPos = new BlockPos(hit.blockPosition());
        if (!world.isBlockLoaded(blockPos) || !world.getWorldBorder().contains(blockPos)) {
            return null;
        }
        IBlockState state = world.getBlockState(blockPos);
        if (state == null || state.getBlock() == Blocks.AIR) {
            return null;
        }
        Vec3d nativePoint = hit.nativeResult().hitVec;
        if (!Double.isFinite(nativePoint.x) || !Double.isFinite(nativePoint.y)
                || !Double.isFinite(nativePoint.z)) {
            return null;
        }
        return new Target(measurement.sampleId(), blockPos, hit.side(), referenceFor(hit.side()),
                new Vec3(nativePoint.x, nativePoint.y, nativePoint.z));
    }

    /** Keeps the UIE picking sample and actual GL scene-depth capture in one render callback. */
    static void render(Target target, float partialTicks) {
        if (target == null) {
            clear();
            return;
        }
        CameraMeasurement measurement = CameraApi.measure(partialTicks);
        if (measurement.sampleId() <= 0L || measurement.sampleId() != target.sampleId()) {
            clear();
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewer = minecraft.getRenderViewEntity();
        if (minecraft.world == null || viewer == null) {
            clear();
            return;
        }
        Vec3 feet = new Vec3(
                viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTicks,
                viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTicks,
                viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTicks);
        // Eye height and orbit already belong to the captured model-view matrix.
        OVERLAY.render(target.hitWorld(), target.reference().normal(), feet);
    }

    private static SixDofAlignmentInput.Reference referenceFor(EnumFacing normal) {
        return switch (normal) {
            case DOWN -> SixDofAlignmentInput.Reference.Y_NEG;
            case UP -> SixDofAlignmentInput.Reference.Y_POS;
            case NORTH -> SixDofAlignmentInput.Reference.Z_NEG;
            case SOUTH -> SixDofAlignmentInput.Reference.Z_POS;
            case WEST -> SixDofAlignmentInput.Reference.X_NEG;
            case EAST -> SixDofAlignmentInput.Reference.X_POS;
        };
    }

    record Target(long sampleId, BlockPos blockPos, EnumFacing outwardNormal,
                  SixDofAlignmentInput.Reference reference, Vec3 hitWorld) {
        Target {
            if (sampleId <= 0L) {
                throw new IllegalArgumentException("sampleId");
            }
            Objects.requireNonNull(blockPos, "blockPos");
            Objects.requireNonNull(outwardNormal, "outwardNormal");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(hitWorld, "hitWorld");
        }
    }
}
