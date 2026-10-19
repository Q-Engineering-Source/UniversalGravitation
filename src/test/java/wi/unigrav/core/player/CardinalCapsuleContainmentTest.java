package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalCapsuleContainmentTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(0.5, 2, 1.5);

    @Test void sixActualCardinalBoxesCanContainASeparateVirtualCapsule() {
        for (var frame : CardinalFrame.values()) {
            var original = new PlayerBodyPlacement(new Vec3d(8.5, 66, 8.5), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, SIZE));
            var source = snapshot(original);
            var virtual = new PlayerBodyPlacement(original.center(), frame.attitude(),
                    new PlayerBodyShape.FreeCapsule(SIZE));
            var work = new StoredDyadic.Work();
            var proof = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(source, virtual, work));
            assertSame(source, proof.source());
            assertSame(virtual, proof.virtualPlacement());
            assertTrue(proof.matches(source, virtual, work));
            assertFalse(proof.matches(snapshot(original), virtual, work));
            assertFalse(proof.matches(source, virtual, new StoredDyadic.Work()));
            assertTrue(original.bounds().contains(proof.outwardCapsuleBounds().min()));
            assertTrue(original.bounds().contains(proof.outwardCapsuleBounds().max()));
            assertTrue(proof.numericWork() > 0);
            assertInstanceOf(PlayerBodyShape.Cardinal.class, source.placement().shape());
        }
    }

    @Test void nearestRoundedProxyCannotProveActualCapsuleContainment() {
        var virtual = new PlayerBodyPlacement(new Vec3d(0.1, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(SIZE));
        var box = virtual.capsule().bounds();
        var source = snapshot(PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(box.center().x(), box.min().y(), box.center().z()),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE)));
        assertTrue(source.placement().bounds().contains(virtual.capsule().bounds().min()));
        assertTrue(source.placement().bounds().contains(virtual.capsule().bounds().max()));
        var result = assertInstanceOf(CardinalCapsuleContainment.Outside.class,
                CardinalCapsuleContainment.prepare(source, virtual, new StoredDyadic.Work()));
        assertTrue(result.outwardCapsuleBounds().max().x() > box.max().x(),
                "the exact stored endpoint plus radius is beyond the nearest-rounded proxy");
    }

    @Test void smallerVirtualProfileDoesNotChangeTheSourceOrFinalRequestedProfile() {
        var original = new PlayerBodyPlacement(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var source = snapshot(original);
        var smaller = new PlayerBodyPlacement(original.center(), original.attitude(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.25, 1, 0.75)));
        assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                CardinalCapsuleContainment.prepare(source, smaller, new StoredDyadic.Work()));
        assertSame(original, source.placement());
        assertEquals(SIZE, source.placement().shape().dimensions());
    }

    @Test void exhaustedCallerLedgerCannotBeReplacedByContainment() {
        var original = new PlayerBodyPlacement(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var virtual = new PlayerBodyPlacement(original.center(), original.attitude(),
                new PlayerBodyShape.FreeCapsule(SIZE));
        var work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        assertThrows(NumericWorkExhaustedException.class,
                () -> CardinalCapsuleContainment.prepare(snapshot(original), virtual, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void freeSnapshotCannotBeDisguisedAsTheActualCardinalSource() {
        var free = new PlayerBodyPlacement(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(SIZE));
        assertThrows(IllegalArgumentException.class,
                () -> CardinalCapsuleContainment.prepare(snapshot(free), free, new StoredDyadic.Work()));
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerBodyPlacement placement) {
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(
                UUID.fromString("00000000-0000-0000-0000-000000004531"),
                UUID.fromString("00000000-0000-0000-0000-000000004532"), 0, 1),
                1, placement, new Vec3d(0, 0, 0));
    }
}
