package wi.unigrav.core.player;

import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicalSnapshotTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final UUID SESSION = new UUID(3, 4);
    private static final PlayerPhysicalSnapshot.Scope SCOPE = new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 1);
    private static final Vec3d CENTER = new Vec3d(12, 40, -8);
    private static final Vec3d VELOCITY = new Vec3d(2, -3, 4);
    private static final PlayerBodyShape.Dimensions DIMENSIONS = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);

    @Test
    void actualCardinalTransportDecodePreservesOwnStateAndTranslatesTheRetainedBox() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F));
        var actualBox = new Aabb(new Vec3d(0.1, 2, 3),
                new Vec3d(1.8999999523162843, 2.600000023841858, 3.600000023841858));
        var transport = new Vec3d(0.9999999761581422, 2, 3.300000011920929);
        var actual = PlayerBodyPlacement.fromCardinalBounds(actualBox, transport, CardinalFrame.X_POS.attitude(), shape);
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 17, actual, VELOCITY);
        var own = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class,
                snapshot.decodeTransport(snapshot.transportSample()));
        assertEquals(actual, own.placement());
        assertEquals(new Vec3d(0, 0, 0), own.centerDelta());

        var proposal = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class, snapshot.decodeTransport(
                new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17, transport.add(new Vec3d(0.125, 0, 0)))));
        assertEquals(0.225, proposal.placement().bounds().min().x());
        assertEquals(2.0249999523162843, proposal.placement().bounds().max().x());
        assertEquals(0.225, proposal.placement().physicalFeet().orElseThrow().x());
        assertEquals(new Vec3d(0.125, 0, 0), proposal.centerDelta());
        assertEquals(actualBox, snapshot.placement().bounds());
        assertEquals(17, snapshot.revision(), "decoding does not install the proposed move");
        assertEquals(VELOCITY, snapshot.worldVelocity());
    }

    @Test
    void translatedCardinalProposalUsesItsResetMirrorNotTheIncomingAnchor() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F));
        double halfWidth = (double) 0.6F / 2;
        var box = new Aabb(new Vec3d(0.1 - halfWidth, 2, -halfWidth),
                new Vec3d(0.1 + halfWidth, 2 + (double) 1.8F, halfWidth));
        var placement = PlayerBodyPlacement.fromCardinalBounds(box, new Vec3d(0.1, 2, 0),
                Rotation3d.identity(), shape);
        var state = new PlayerPhysicalSnapshot(SCOPE, 17, placement, VELOCITY);
        assertSame(placement, assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class,
                state.decodeTransport(state.transportSample())).placement());
        var incoming = new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17, new Vec3d(0.2, 2, 0));
        var candidate = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class, state.decodeTransport(incoming));
        assertEquals(-0.10000001192092894, candidate.placement().bounds().min().x());
        assertEquals(0.5000000119209289, candidate.placement().bounds().max().x());
        assertEquals(0.19999999999999998, candidate.placement().transport().x());
        assertNotEquals(incoming.position(), candidate.placement().transport());
        assertEquals(0.09999999999999999, candidate.centerDelta().x());
        assertEquals(placement, state.placement());
        assertEquals(17, state.revision());
    }

    @Test
    void allSixFramesDecodeOnlyTheirOwnExactRevisionAndPlacement() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            var placement = new PlayerBodyPlacement(CENTER, frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, DIMENSIONS));
            var snapshot = new PlayerPhysicalSnapshot(SCOPE, 17, placement, VELOCITY);
            var decoded = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class,
                    snapshot.decodeTransport(snapshot.transportSample()));
            assertEquals(placement, decoded.placement());
            assertEquals(new Vec3d(0, 0, 0), decoded.centerDelta());
            assertEquals(VELOCITY, snapshot.worldVelocity());
        }
    }

    @Test
    void staleAndFutureRevisionsAreRejectedBeforeOffsetInterpretation() {
        var snapshot = farSnapshot();
        for (long revision : new long[]{0, 16, 18, Long.MAX_VALUE}) {
            // Opposite finite extremes overflow centre subtraction if interpreted first.
            var sample = new PlayerPhysicalSnapshot.TransportSample(SCOPE, revision,
                    new Vec3d(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE));
            assertEquals(new PlayerPhysicalSnapshot.Decode.Rejected(PlayerPhysicalSnapshot.Mismatch.REVISION),
                    snapshot.decodeTransport(sample));
        }
        assertEquals(-Double.MAX_VALUE, snapshot.placement().center().x());
        assertEquals(17, snapshot.revision());
        assertInstanceOf(PlayerPhysicalSnapshot.Decode.Invalid.class, snapshot.decodeTransport(
                new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17,
                        new Vec3d(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE))));
    }

    @Test
    void entitySessionDimensionAndWorldLifetimeAreNotInterchangeable() {
        var snapshot = farSnapshot();
        var scopes = new PlayerPhysicalSnapshot.Scope[]{
                new PlayerPhysicalSnapshot.Scope(new UUID(9, 9), SESSION, 0, 1),
                new PlayerPhysicalSnapshot.Scope(PLAYER, new UUID(9, 9), 0, 1),
                new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, -1, 1),
                new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 2)};
        var reasons = new PlayerPhysicalSnapshot.Mismatch[]{PlayerPhysicalSnapshot.Mismatch.PLAYER,
                PlayerPhysicalSnapshot.Mismatch.SESSION, PlayerPhysicalSnapshot.Mismatch.WORLD,
                PlayerPhysicalSnapshot.Mismatch.WORLD};
        for (int i = 0; i < scopes.length; i++) {
            assertEquals(new PlayerPhysicalSnapshot.Decode.Rejected(reasons[i]), snapshot.decodeTransport(
                    new PlayerPhysicalSnapshot.TransportSample(scopes[i], 17, new Vec3d(Double.MAX_VALUE, 0, 0))));
        }
        assertInstanceOf(PlayerPhysicalSnapshot.Decode.Invalid.class, snapshot.decodeTransport(
                new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17, new Vec3d(Double.MAX_VALUE, 0, 0))));
    }

    @Test
    void shapeOffsetCannotTurnOldUprightTransportIntoNewSidewaysMotion() {
        var upright = snapshot(17, Rotation3d.identity());
        var sideways = upright.successor(capsule(CardinalFrame.X_POS.attitude()), VELOCITY);
        assertEquals(0.75, sideways.transportSample().position().y() - upright.transportSample().position().y(), 1e-12);
        assertEquals(new PlayerPhysicalSnapshot.Decode.Rejected(PlayerPhysicalSnapshot.Mismatch.REVISION),
                sideways.decodeTransport(upright.transportSample()));
        var own = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class,
                sideways.decodeTransport(sideways.transportSample()));
        assertEquals(0, own.centerDelta().lengthSquared(), 1e-25);
        assertEquals(CENTER, upright.placement().center());
        assertEquals(17, upright.revision());
        assertEquals(18, sideways.revision());
    }

    @Test
    void decodedTranslationIsCanonicalAndDoesNotMutateOrAuthorizeState() {
        var snapshot = snapshot(17, new Rotation3d(1, 2, 3, 4));
        var delta = new Vec3d(1.25, -2.5, 0.75);
        var sample = new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17,
                snapshot.transportSample().position().add(delta));
        var decoded = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Candidate.class, snapshot.decodeTransport(sample));
        assertEquals(delta.x(), decoded.centerDelta().x(), 1e-12);
        assertEquals(delta.y(), decoded.centerDelta().y(), 1e-12);
        assertEquals(delta.z(), decoded.centerDelta().z(), 1e-12);
        assertEquals(CENTER, snapshot.placement().center());
        assertEquals(VELOCITY, snapshot.worldVelocity());
        assertEquals(17, snapshot.revision());
    }

    @Test
    void resolvedSuccessorChangesWholePlacementAndVelocityButNotTheOldSnapshot() {
        var old = snapshot(17, Rotation3d.identity());
        var newVelocity = new Vec3d(-1, 4, 8);
        var nextPlacement = new PlayerBodyPlacement(new Vec3d(100, -3, 200), CardinalFrame.Z_NEG.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG, DIMENSIONS));
        var next = old.successor(nextPlacement, newVelocity);
        assertEquals(SCOPE, next.scope());
        assertEquals(18, next.revision());
        assertEquals(nextPlacement, next.placement());
        assertEquals(newVelocity, next.worldVelocity());
        assertEquals(CENTER, old.placement().center());
        assertEquals(VELOCITY, old.worldVelocity());
        assertEquals(17, old.revision());
    }

    @Test
    void finiteButUnrepresentableIncomingDisplacementHasAnExplicitOriginalCause() {
        var snapshot = farSnapshot();
        var failure = assertInstanceOf(PlayerPhysicalSnapshot.Decode.Invalid.class, snapshot.decodeTransport(
                new PlayerPhysicalSnapshot.TransportSample(SCOPE, 17, new Vec3d(Double.MAX_VALUE, 0, 0))));
        assertNotNull(failure.cause());
        assertFalse(failure.cause().getMessage().isBlank());
        assertEquals(-Double.MAX_VALUE, snapshot.placement().center().x());
    }

    @Test
    void invalidLifetimesRevisionsAndExhaustionFailWithoutWrapping() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, -1));
        assertThrows(NullPointerException.class, () -> new PlayerPhysicalSnapshot.Scope(null, SESSION, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot(-1, Rotation3d.identity()));
        var exhausted = snapshot(Long.MAX_VALUE, Rotation3d.identity());
        assertThrows(IllegalStateException.class, () -> exhausted.successor(capsule(Rotation3d.identity()), VELOCITY));
        assertEquals(Long.MAX_VALUE, exhausted.revision());
    }

    private static PlayerPhysicalSnapshot snapshot(long revision, Rotation3d attitude) {
        return new PlayerPhysicalSnapshot(SCOPE, revision, capsule(attitude), VELOCITY);
    }

    private static PlayerPhysicalSnapshot farSnapshot() {
        var far = new PlayerBodyPlacement(new Vec3d(-Double.MAX_VALUE, 0, 0), Rotation3d.identity(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, DIMENSIONS));
        return new PlayerPhysicalSnapshot(SCOPE, 17, far, VELOCITY);
    }

    private static PlayerBodyPlacement capsule(Rotation3d attitude) {
        return new PlayerBodyPlacement(CENTER, attitude, new PlayerBodyShape.FreeCapsule(DIMENSIONS));
    }
}
