package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import wi.unigrav.api.math.Vec3;

/** Actual fluid eligibility; the captured owner boundary handles failures and canonical force delivery. */
public final class FreeNativeEnvironment {
    /** The side owner supplies a verified pure lookup; missing chunks are never generated. */
    @FunctionalInterface public interface LoadedChunks { Chunk find(int x, int z); }
    private final Object sourceIdentity;
    private final Entity actor;
    private final CapsulePlacementMath.Pose pose;
    private final FreeTickResources resources;
    private final Runnable requireCurrent;
    private final Optional<Consumer<Vec3>> waterForce;
    private final LoadedChunks loadedChunks;
    private CapsuleEnvironmentObservation.Prepared observation;

    FreeNativeEnvironment(Object sourceIdentity, Entity actor, PlayerPhysicalSnapshot accepted,
                          FreeTickResources resources, Runnable requireCurrent,
                          Optional<Consumer<Vec3>> waterForce) {
        this(sourceIdentity, actor, accepted, resources, requireCurrent, waterForce, serverLoadedChunks(actor.world));
    }

    private FreeNativeEnvironment(Object sourceIdentity, Entity actor, PlayerPhysicalSnapshot accepted,
                                  FreeTickResources resources, Runnable requireCurrent,
                                  Optional<Consumer<Vec3>> waterForce, LoadedChunks loadedChunks) {
        this.sourceIdentity = Objects.requireNonNull(sourceIdentity);
        this.actor = Objects.requireNonNull(actor);
        this.resources = Objects.requireNonNull(resources);
        this.requireCurrent = Objects.requireNonNull(requireCurrent);
        this.waterForce = Objects.requireNonNull(waterForce);
        this.loadedChunks = Objects.requireNonNull(loadedChunks);
        requireCurrent.run();
        pose = CapsulePlacementMath.pose(accepted.placement(), resources.numeric());
        requireCurrent.run();
    }

    /** No canonical force channel: the caller must authenticate its installed local mirror and exact owner fence. */
    public static FreeNativeEnvironment mirror(Object sourceIdentity, Entity actor, PlayerPhysicalSnapshot accepted,
                                               FreeTickResources resources, Runnable requireCurrent,
                                               LoadedChunks loadedChunks) {
        return new FreeNativeEnvironment(sourceIdentity, actor, accepted, resources, requireCurrent,
                Optional.empty(), loadedChunks);
    }

    public boolean waterMovement() {
        requireCurrent.run();
        if (actor.getRidingEntity() instanceof EntityBoat) {
            actor.inWater = false;
            return false;
        }
        var observed = observe();
        if (observed.inWater()) {
            if (waterForce.isPresent() && resources.claimWaterForce()) consumeWaterForce(observed);
            if (!actor.inWater && !actor.firstUpdate) {
                actor.doWaterSplashEffect();
                requireCurrent.run();
            }
            actor.fallDistance = 0;
            actor.inWater = true;
            actor.extinguish();
            requireCurrent.run();
        } else actor.inWater = false;
        return actor.inWater;
    }

    public boolean inLava() { return observe().inLava(); }

    private CapsuleEnvironmentObservation.Prepared observe() {
        requireCurrent.run();
        if (observation != null && observation.matches(sourceIdentity, pose)) return observation;
        var result = CapsuleEnvironmentObservation.observe(sourceIdentity, actor, pose, resources.queries(),
                requireCurrent, loadedChunks);
        if (!(result instanceof CapsuleEnvironmentObservation.Prepared prepared)) {
            var failure = (CapsuleEnvironmentObservation.Unavailable) result;
            throw new IllegalStateException("Free native fluid observation failed: " + failure.reason(),
                    failure.cause().orElse(null));
        }
        observation = prepared;
        return prepared;
    }

    private void consumeWaterForce(CapsuleEnvironmentObservation.Prepared observed) {
        Vec3d flow = Vec3d.ZERO;
        for (var cell : observed.cells()) {
            if (cell.state().getMaterial() != Material.WATER || cell.fluid().isEmpty()
                    || cell.fluid().orElseThrow().relation() != Relation.OVERLAPPING) continue;
            // Native liquid acceleration examines adjacent voxels and their lower neighbour.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = cell.position().getX() + dx, z = cell.position().getZ() + dz;
                var chunk = loadedChunks.find(x >> 4, z >> 4);
                if (chunk == null || !chunk.isLoaded())
                    throw new IllegalStateException("Free native liquid flow lost a loaded neighbour");
            }
            requireCurrent.run();
            flow = Objects.requireNonNull(cell.state().getBlock().modifyAcceleration(
                    actor.world, cell.position(), actor, flow), "native liquid acceleration");
            if (!Double.isFinite(flow.x) || !Double.isFinite(flow.y) || !Double.isFinite(flow.z))
                throw new IllegalStateException("Native liquid acceleration returned non-finite flow");
            requireCurrent.run();
            if (!observed.matches(sourceIdentity, pose))
                throw new IllegalStateException("Free native liquid callback changed its observation");
        }
        boolean pushed = actor.isPushedByWater();
        requireCurrent.run();
        if (flow.length() > 0 && pushed) {
            flow = flow.normalize().scale(.014).scale(20);
            waterForce.orElseThrow().accept(new Vec3(flow.x, flow.y, flow.z));
            requireCurrent.run();
        }
    }

    static LoadedChunks serverLoadedChunks(World world) {
        if (!(world.getChunkProvider() instanceof ChunkProviderServer provider))
            throw new IllegalStateException("Free environment has no verified pure loaded-chunk provider");
        return (x, z) -> provider.loadedChunks.get(ChunkPos.asLong(x, z));
    }
}
