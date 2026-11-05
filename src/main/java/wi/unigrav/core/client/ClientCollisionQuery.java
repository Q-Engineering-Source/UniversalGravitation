package wi.unigrav.core.client;

import dev.crlhitbox.api.minecraft.collision.ClientCollisionScenes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** One current client collision view; native collider subclasses retain their actual filtering. */
final class ClientCollisionQuery {
    private ClientCollisionQuery() { }

    static Function<AxisAlignedBB, List<AxisAlignedBB>> capture(World world, EntityPlayer actor,
            Runnable requireCurrent) {
        requireCurrent.run();
        var captured = ClientCollisionScenes.capture(world, actor);
        requireCurrent.run();
        if (captured instanceof ClientCollisionScenes.CaptureUnavailable failure)
            throw new ClientCollisionScenes.SceneUnavailableException(failure.reason());
        Optional<ClientCollisionScenes.Scene> scene = captured instanceof ClientCollisionScenes.Ready ready
                ? Optional.of(ready.scene()) : Optional.empty();
        var query = bind(world, actor, bounds -> world.getCollisionBoxes(actor, bounds), scene);
        return bounds -> {
            requireCurrent.run();
            var result = query.apply(bounds);
            requireCurrent.run();
            return result;
        };
    }

    static Function<AxisAlignedBB, List<AxisAlignedBB>> bind(World world, EntityPlayer actor,
            Function<AxisAlignedBB, List<AxisAlignedBB>> nativeCollisions,
            Optional<ClientCollisionScenes.Scene> captured) {
        if (captured.isEmpty()) return nativeCollisions;
        var scene = captured.orElseThrow();
        return bounds -> {
            if (!scene.valid(world, actor))
                throw new ClientCollisionScenes.SceneUnavailableException("SCENE_LIFETIME_CHANGED");
            var nativeBoxes = nativeCollisions.apply(bounds);
            var query = scene.query(bounds);
            if (query instanceof ClientCollisionScenes.QueryUnavailable failure)
                throw new ClientCollisionScenes.SceneUnavailableException(failure.reason());
            if (!scene.valid(world, actor))
                throw new ClientCollisionScenes.SceneUnavailableException("SCENE_LIFETIME_CHANGED");
            var result = new ArrayList<>(Objects.requireNonNull(nativeBoxes, "native collision boxes"));
            result.addAll(((ClientCollisionScenes.Colliders) query).boxes());
            return List.copyOf(result);
        };
    }
}
