package wi.unigrav.core.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class SixDofGroundPickingRayTest {
    @Test
    void reachesTheFourthNativeSegmentAndStopsAt256Metres() {
        List<Segment> queries = new ArrayList<>();
        String hit = SixDofGroundPickingRay.firstHit(Vec3.ZERO, new Vec3(10, 0, 0),
                (origin, direction, distance) -> {
                    queries.add(new Segment(origin, direction, distance));
                    return origin.x() == 192.0D ? "wall-at-250" : null;
                });
        assertEquals("wall-at-250", hit);
        assertEquals(List.of(0.0D, 64.0D, 128.0D, 192.0D),
                queries.stream().map(query -> query.origin().x()).toList());
        for (Segment query : queries) {
            assertEquals(new Vec3(1, 0, 0), query.direction());
            assertEquals(64.0D, query.distance());
        }
        Segment last = queries.getLast();
        assertEquals(new Vec3(256, 0, 0), last.origin().add(last.direction().scale(last.distance())));
    }

    @Test
    void aNearerHitOccludesEveryLaterSegment() {
        AtomicInteger queries = new AtomicInteger();
        String hit = SixDofGroundPickingRay.firstHit(Vec3.ZERO, new Vec3(0, 0, -1),
                (origin, direction, distance) -> {
                    int query = queries.incrementAndGet();
                    if (query > 2) throw new AssertionError("traced past the nearer native hit");
                    return query == 2 ? "nearer-wall" : null;
                });
        assertEquals("nearer-wall", hit);
        assertEquals(2, queries.get());
    }

    @Test
    void diagonalNegativeAndBoundaryOriginsUseOneContinuousBoundedRay() {
        Vec3 start = new Vec3(-64, 20.25D, 128);
        for (Vec3 direction : List.of(new Vec3(1, 1, 1), new Vec3(-1, -2, 2),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1))) {
            List<Segment> queries = new ArrayList<>();
            assertNull(SixDofGroundPickingRay.firstHit(start, direction, (origin, unit, distance) -> {
                queries.add(new Segment(origin, unit, distance));
                return null;
            }));
            assertEquals(4, queries.size());
            assertEquals(start, queries.getFirst().origin());
            for (int index = 0; index < queries.size(); index++) {
                Segment current = queries.get(index);
                assertEquals(1.0D, current.direction().norm(), 1.0E-12D);
                Vec3 delta = current.direction().scale(current.distance());
                double crossingUpperBound = Math.ceil(Math.abs(delta.x()))
                        + Math.ceil(Math.abs(delta.y())) + Math.ceil(Math.abs(delta.z())) + 3;
                assertTrue(crossingUpperBound < 200, "each query stays below the native DDA budget");
                Vec3 end = current.origin().add(delta);
                if (index + 1 < queries.size()) {
                    assertEquals(0.0D, end.sub(queries.get(index + 1).origin()).norm(), 1.0E-12D);
                } else {
                    assertEquals(256.0D, end.sub(start).norm(), 1.0E-10D);
                }
            }
        }
    }

    @Test
    void invalidCameraVectorsNeverReachThePicker() {
        AtomicInteger queries = new AtomicInteger();
        SixDofGroundPickingRay.Raycast<String> raycast = (origin, direction, distance) -> {
            queries.incrementAndGet();
            return "invalid";
        };
        assertNull(SixDofGroundPickingRay.firstHit(Vec3.ZERO, Vec3.ZERO, raycast));
        assertNull(SixDofGroundPickingRay.firstHit(new Vec3(Double.NaN, 0, 0), new Vec3(1, 0, 0), raycast));
        assertNull(SixDofGroundPickingRay.firstHit(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0), raycast));
        assertEquals(0, queries.get());
    }

    private record Segment(Vec3 origin, Vec3 direction, double distance) {
    }
}
