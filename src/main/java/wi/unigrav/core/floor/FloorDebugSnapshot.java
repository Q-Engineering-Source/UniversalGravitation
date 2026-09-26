package wi.unigrav.core.floor;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import wi.unigrav.api.math.Vec3;

/** Immutable debug field samples. Magnetic arrows describe unit response, not actor eligibility. */
public record FloorDebugSnapshot(List<Arrow> arrows) {
    public FloorDebugSnapshot { arrows = List.copyOf(arrows); }
    public record Arrow(Vec3 position, Vec3 gravity, Vec3 magnetic) { }

    public static FloorDebugSnapshot sample(List<FloorFieldIndex.Source> sources, Vec3 center) {
        if (sources.size() > 1024) throw new IllegalArgumentException("debug source limit exceeded");
        var index = new FloorFieldIndex();
        var groups = new LinkedHashMap<FloorFieldIndex.ChunkKey, List<FloorFieldIndex.Source>>();
        var points = new LinkedHashSet<Vec3>();
        for (var source : sources) {
            var key = new FloorFieldIndex.ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
            Vec3 outward = switch (source.face()) {
                case UP -> new Vec3(0, 1, 0);
                case DOWN -> new Vec3(0, -1, 0);
                case EAST -> new Vec3(1, 0, 0);
                case WEST -> new Vec3(-1, 0, 0);
                case SOUTH -> new Vec3(0, 0, 1);
                case NORTH -> new Vec3(0, 0, -1);
            };
            var origin = new Vec3(source.x() + .5, source.y() + .5, source.z() + .5).add(outward.scale(.5));
            // Built-in reach is 16 m; cap debug work independently from physics/configuration.
            for (double distance = .5; distance < Math.min(16, source.profile().range()); distance += 4) {
                Vec3 point = origin.add(outward.scale(distance));
                if (point.sub(center).normSquared() <= 16 * 16) points.add(point);
            }
        }
        groups.forEach((key, batch) -> index.replaceChunk(key, batch, batch));
        var arrows = new ArrayList<Arrow>();
        for (Vec3 point : points) {
            var force = index.sample(point, 1);
            arrows.add(new Arrow(point, force.gravityAccel(), force.magneticAccel()));
        }
        return new FloorDebugSnapshot(arrows);
    }
}
