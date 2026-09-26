package wi.unigrav.core.player;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Conversion-seam tests only; no fake World or claimed live-server capture. */
class WorldCollisionCandidatesTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void signedZeroInNativeBoxDoesNotInvalidateIdenticalPhysicalGeometry(boolean zeroMinima) {
        var center = zeroMinima ? new Vec3d(0.5, 1, 0.5) : new Vec3d(-0.5, -1, -0.5);
        var placement = new PlayerBodyPlacement(center, CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(1, 2, 1.5)));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER_ID, SESSION_ID, 3, 7),
                12, placement, new Vec3d(0, 0, 0));
        var bounds = placement.bounds();
        var positive = new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
        var signed = new AxisAlignedBB(zeroMinima ? -0.0 : bounds.min().x(), zeroMinima ? -0.0 : bounds.min().y(),
                zeroMinima ? -0.0 : bounds.min().z(), zeroMinima ? bounds.max().x() : -0.0,
                zeroMinima ? bounds.max().y() : -0.0, zeroMinima ? bounds.max().z() : -0.0);
        assertNotEquals(positive, signed); // The actual native equals method distinguishes the zero sign.
        var observed = new WorldCollisionCandidates.ActorGeometry(PLAYER_ID, 3, placement.transport(), bounds, 1, 2, false);
        var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, signed, new Vec3d(0, 0, 0),
                MoverType.SELF, false, false, 0);
        assertTrue(WorldCollisionCandidates.cardinalStartFailure(accepted, request, observed).isEmpty());

        var changed = new AxisAlignedBB(signed.minX, signed.minY, signed.minZ,
                Math.nextUp(signed.maxX), signed.maxY, signed.maxZ);
        var different = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, changed, new Vec3d(0, 0, 0),
                MoverType.SELF, false, false, 0);
        assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                WorldCollisionCandidates.cardinalStartFailure(accepted, different, observed).orElseThrow().reason());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void cardinalEntryBindsRealObservedValuesToTheProvidedPhysicalSnapshot(CardinalFrame frame) {
        var dimensions = new PlayerBodyShape.Dimensions(0.6, 1.8, 1.62);
        var placement = new PlayerBodyPlacement(new Vec3d(10, 300, -20), frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, dimensions));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER_ID, SESSION_ID, 3, 7),
                12, placement, new Vec3d(1, 2, 3));
        var bounds = placement.bounds();
        var nativeBounds = new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
        var request = new CardinalMovementResolver.Request(frame, nativeBounds, new Vec3d(0, 0, 0),
                MoverType.SELF, false, false, 0);
        var observed = new WorldCollisionCandidates.ActorGeometry(PLAYER_ID, 3, placement.transport(), bounds, 0.6F, 1.8F, false);
        assertTrue(WorldCollisionCandidates.cardinalStartFailure(accepted, request, observed).isEmpty());

        var wrongActor = new WorldCollisionCandidates.ActorGeometry(SESSION_ID, 3, placement.transport(), bounds, 0.6F, 1.8F, false);
        var wrongDimension = new WorldCollisionCandidates.ActorGeometry(PLAYER_ID, 4, placement.transport(), bounds, 0.6F, 1.8F, false);
        var dead = new WorldCollisionCandidates.ActorGeometry(PLAYER_ID, 3, placement.transport(), bounds, 0.6F, 1.8F, true);
        for (var invalid : List.of(wrongActor, wrongDimension, dead)) {
            assertEquals(WorldCollisionCandidates.Reason.ACTOR_SCOPE,
                    WorldCollisionCandidates.cardinalStartFailure(accepted, request, invalid).orElseThrow().reason());
        }

        var oldTransport = new WorldCollisionCandidates.ActorGeometry(PLAYER_ID, 3,
                placement.transport().add(new Vec3d(0, 1, 0)), bounds, 0.6F, 1.8F, false);
        assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                WorldCollisionCandidates.cardinalStartFailure(accepted, request, oldTransport).orElseThrow().reason());
        var staleBounds = new CardinalMovementResolver.Request(frame, nativeBounds.offset(1, 0, 0), new Vec3d(0, 0, 0),
                MoverType.SELF, false, false, 0);
        assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                WorldCollisionCandidates.cardinalStartFailure(accepted, staleBounds, observed).orElseThrow().reason());
        var wrongFrame = frame == CardinalFrame.Y_POS ? CardinalFrame.X_POS : CardinalFrame.Y_POS;
        var rotatedRequest = new CardinalMovementResolver.Request(wrongFrame, nativeBounds, new Vec3d(0, 0, 0),
                MoverType.SELF, false, false, 0);
        assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                WorldCollisionCandidates.cardinalStartFailure(accepted, rotatedRequest, observed).orElseThrow().reason());
        var free = new PlayerPhysicalSnapshot(accepted.scope(), 12,
                new PlayerBodyPlacement(placement.center(), placement.attitude(), new PlayerBodyShape.FreeCapsule(dimensions)),
                accepted.worldVelocity());
        assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED,
                WorldCollisionCandidates.cardinalStartFailure(free, request, observed).orElseThrow().reason());
        assertEquals(12, accepted.revision());
        assertEquals(new Vec3d(1, 2, 3), accepted.worldVelocity());
    }

    @Test
    void realNativeMultiBoxesKeepTheirExactCoordinatesOrderAndDuplicates() {
        var lower = new AxisAlignedBB(1, 0, 2, 2, 0.5, 3);
        var upper = new AxisAlignedBB(1.5, 0.5, 2, 2, 1, 3);
        var input = new ArrayList<>(List.of(lower, upper, lower));
        var boxes = WorldCollisionCandidates.copyNativeBoxes(input, 3);
        assertEquals(3, boxes.size());
        assertEquals(new Vec3d(1, 0, 2), boxes.getFirst().min());
        assertEquals(new Vec3d(2, 0.5, 3), boxes.getFirst().max());
        assertEquals(new Vec3d(1.5, 0.5, 2), boxes.get(1).min());
        assertEquals(boxes.getFirst(), boxes.getLast());
        input.clear();
        assertEquals(3, boxes.size());
        assertThrows(UnsupportedOperationException.class, boxes::clear);
    }

    @Test
    void oversizedOutputIsRejectedRatherThanTruncatedOrTraversed() {
        List<AxisAlignedBB> oversized = new AbstractList<>() {
            @Override public AxisAlignedBB get(int index) { throw new AssertionError("over-budget list must not be traversed"); }
            @Override public int size() { return 3; }
        };
        var failure = assertThrows(WorldCollisionCandidates.ObservationFailure.class,
                () -> WorldCollisionCandidates.copyNativeBoxes(oversized, 2));
        assertEquals(WorldCollisionCandidates.Reason.CANDIDATE_LIMIT, failure.reason());
    }

    @Test
    void invalidNativeColliderRejectsTheWholeObservationWithItsCause() {
        AxisAlignedBB valid = new AxisAlignedBB(0, 0, 0, 1, 1, 1);
        for (AxisAlignedBB invalid : Arrays.asList(null, new AxisAlignedBB(Double.NaN, 0, 0, 1, 1, 1),
                new AxisAlignedBB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1))) {
            var failure = assertThrows(WorldCollisionCandidates.ObservationFailure.class,
                    () -> WorldCollisionCandidates.copyNativeBoxes(Arrays.asList(valid, invalid), 2));
            assertEquals(WorldCollisionCandidates.Reason.MALFORMED_COLLIDER, failure.reason());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test
    void emptyOutputAndZeroThicknessBoxesAreNotReplacedWithInventedSolids() {
        assertEquals(List.of(), WorldCollisionCandidates.copyNativeBoxes(List.of(), 1));
        var plane = new AxisAlignedBB(1, -1, -1, 1, 1, 1);
        var result = WorldCollisionCandidates.copyNativeBoxes(List.of(plane), 1);
        assertEquals(1, result.size());
        assertEquals(1, result.getFirst().min().x());
        assertEquals(1, result.getFirst().max().x());
        assertThrows(IllegalArgumentException.class, () -> WorldCollisionCandidates.copyNativeBoxes(List.of(), 0));
    }
}
