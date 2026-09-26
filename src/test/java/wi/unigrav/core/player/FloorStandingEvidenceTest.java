package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;
import wi.unigrav.core.floor.FloorFieldProfile;
import wi.unigrav.core.player.StandingConstraints.Availability;
import wi.unigrav.core.player.StandingConstraints.ConstraintPresence;
import wi.unigrav.core.player.StandingConstraints.ConstraintSource;
import wi.unigrav.core.player.StandingConstraints.Evidence;
import wi.unigrav.core.player.StandingConstraints.FaceEvidence;
import wi.unigrav.core.player.StandingConstraints.Request;
import wi.unigrav.core.player.StandingConstraints.State;

class FloorStandingEvidenceTest {
    private static final Vec3 POINT = new Vec3(0.5, 1.0, 0.5);

    @Test
    void actualIndexCancellationStillRequestsCardinalStandingWithoutInventingForceOrContact() {
        var floor = sample(1.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 2, 0, Face.DOWN, Kind.GRAVITY, 8.0));
        Evidence merged = baseline().withFloorSample(Optional.of(floor), true);
        var result = StandingConstraints.resolve(State.initial(), new Request(1, false, merged));
        assertEquals(ConstraintPresence.PRESENT, result.presence());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
        assertEquals(Vec3.ZERO, result.authoritativeForce().orElseThrow().combinedAcceleration());
        assertEquals(Set.of(CardinalFrame.Y_POS, CardinalFrame.Y_NEG), merged.faceEvidence().keySet());
        assertTrue(result.actualSupport().isEmpty());
        assertTrue(result.nextState().acceptedReference().isEmpty());
    }

    @Test
    void gravityGatePreservesResponseScaledMagnetismAndDoesNotInferGAuthority() {
        var floor = sample(2.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(1, 1, 0, Face.WEST, Kind.MAGNETIC, 3.0));
        var both = baseline().withFloorSample(Optional.of(floor), true);
        assertEquals(new Vec3(0.0, -8.0, 0.0), both.gravityAcceleration());
        assertEquals(new Vec3(6.0, 0.0, 0.0), both.magneticAcceleration());
        var magneticOnly = baseline().withFloorSample(Optional.of(floor), false);
        assertEquals(Vec3.ZERO, magneticOnly.gravityAcceleration());
        assertEquals(both.magneticAcceleration(), magneticOnly.magneticAcceleration());
        assertEquals(Map.of(CardinalFrame.X_NEG, new FaceEvidence(6.0, Set.of(ConstraintSource.MAGNETIC))),
                magneticOnly.faceEvidence());
        var ignored = sample(0.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(1, 1, 0, Face.WEST, Kind.MAGNETIC, 3.0));
        var absent = StandingConstraints.resolve(State.initial(), new Request(1, false,
                baseline().withFloorSample(Optional.of(ignored), false)));
        assertEquals(ConstraintPresence.ABSENT, absent.presence());
        assertEquals(Vec3.ZERO, absent.authoritativeForce().orElseThrow().combinedAcceleration());
    }

    @Test
    void mergeRetainsNonfloorBackgroundBootsSupportAndAllContributingProvenance() {
        var support = new StandingConstraints.Surface(4, 5, 6, Face.EAST);
        var tier = StandingConstraints.BootTier.withDefaultCaptureReach(new FloorFieldProfile(12, 1, 4), 4);
        var boot = new StandingConstraints.BootCandidate(support, 1.0, tier);
        Evidence background = new Evidence(Availability.AVAILABLE, new Vec3(1, -2, 3), new Vec3(4, 5, 6),
                Map.of(CardinalFrame.Y_POS, new FaceEvidence(2, Set.of(ConstraintSource.NATURAL_BACKGROUND))),
                List.of(boot), Optional.of(support));
        var floor = sample(2.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, -1, 0, Face.UP, Kind.MAGNETIC, 3.0));
        var merged = background.withFloorSample(Optional.of(floor), true);
        assertEquals(new Vec3(1, -10, 3), merged.gravityAcceleration());
        assertEquals(new Vec3(4, -1, 6), merged.magneticAcceleration());
        assertEquals(new FaceEvidence(16, Set.of(ConstraintSource.NATURAL_BACKGROUND,
                ConstraintSource.FLOOR, ConstraintSource.MAGNETIC)), merged.faceEvidence().get(CardinalFrame.Y_POS));
        assertEquals(background.bootCandidates(), merged.bootCandidates());
        assertEquals(background.actualSupport(), merged.actualSupport());
        assertEquals(2.0, background.faceEvidence().get(CardinalFrame.Y_POS).preSumReferenceScore());
        var gated = background.withFloorSample(Optional.of(floor), false);
        assertEquals(background.gravityAcceleration(), gated.gravityAcceleration());
        assertEquals(new FaceEvidence(8, Set.of(ConstraintSource.NATURAL_BACKGROUND, ConstraintSource.MAGNETIC)),
                gated.faceEvidence().get(CardinalFrame.Y_POS));
    }

    @ParameterizedTest
    @EnumSource(Face.class)
    void allSixRealFloorDirectionsMapToTheSameStandingFrameAndPreserveFullForce(Face face) {
        Vec3 point = switch (face) {
            case DOWN -> new Vec3(0.5, 0.0, 0.5);
            case UP -> new Vec3(0.5, 1.0, 0.5);
            case NORTH -> new Vec3(0.5, 0.5, 0.0);
            case SOUTH -> new Vec3(0.5, 0.5, 1.0);
            case WEST -> new Vec3(0.0, 0.5, 0.5);
            case EAST -> new Vec3(1.0, 0.5, 0.5);
        };
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(source(0, 0, 0, face, Kind.GRAVITY, 8)));
        var floor = index.sampleForStanding(point, 0.0);
        var result = StandingConstraints.resolve(State.initial(), new Request(1, false,
                baseline().withFloorSample(Optional.of(floor), true)));
        assertEquals(Optional.of(CardinalFrame.fromFloorFace(face)), result.targetReference());
        assertEquals(floor.forces().gravityAccel(), result.authoritativeForce().orElseThrow().gravityAcceleration());
    }

    @Test
    void unavailableFloorOrBaselineCannotBeUsedAsZeroGravity() {
        var previous = new State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(CardinalFrame.X_POS), Optional.empty(), -1);
        var floor = sample(1.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0));
        for (Evidence unavailable : List.of(baseline().withFloorSample(Optional.empty(), true),
                Evidence.unavailable().withFloorSample(Optional.of(floor), true))) {
            assertEquals(Evidence.unavailable(), unavailable);
            var result = StandingConstraints.resolve(previous, new Request(1, false, unavailable));
            assertEquals(ConstraintPresence.UNAVAILABLE, result.presence());
            assertEquals(previous.acceptedReference(), result.nextState().acceptedReference());
            assertTrue(result.authoritativeForce().isEmpty());
        }
    }

    @Test
    void scaledUnderflowIsNotAnEffectiveMagneticStandingConstraint() {
        var floor = sample(0.5, source(0, 0, 0, Face.UP, Kind.MAGNETIC, Double.MIN_VALUE));
        assertEquals(Set.of(Face.UP), floor.forces().effectiveMagneticFaces());
        var evidence = baseline().withFloorSample(Optional.of(floor), true);
        assertTrue(evidence.faceEvidence().isEmpty());
        assertEquals(ConstraintPresence.ABSENT,
                StandingConstraints.resolve(State.initial(), new Request(1, false, evidence)).presence());
    }

    @Test
    void floorObservationsCannotBeCountedAgainThroughTheirOwnMergedOutput() {
        for (Kind kind : Kind.values()) {
            var floor = sample(1.0, source(0, 0, 0, Face.UP, kind, 32));
            var once = baseline().withFloorSample(Optional.of(floor), true);
            assertEquals(32.0, once.faceEvidence().get(CardinalFrame.Y_POS).preSumReferenceScore());
            assertThrows(IllegalArgumentException.class, () -> once.withFloorSample(Optional.of(floor), true));
            // A different sample also cannot turn this into an incremental accumulator.
            var another = sample(1.0, source(0, 0, 0, Face.UP, kind, 8));
            assertThrows(IllegalArgumentException.class, () -> once.withFloorSample(Optional.of(another), true));
        }
    }

    @Test
    void mergedScoreAndForceOverflowFailFastRatherThanSaturatingOrReturningUnavailable() {
        var enormous = sample(1.0, source(0, 0, 0, Face.UP, Kind.GRAVITY, Double.MAX_VALUE));
        var score = new Evidence(Availability.AVAILABLE, Vec3.ZERO, Vec3.ZERO,
                Map.of(CardinalFrame.Y_POS, new FaceEvidence(Double.MAX_VALUE, Set.of(ConstraintSource.NATURAL_BACKGROUND))),
                List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> score.withFloorSample(Optional.of(enormous), true));
        var force = new Evidence(Availability.AVAILABLE, new Vec3(0, -Double.MAX_VALUE, 0), Vec3.ZERO,
                Map.of(), List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> force.withFloorSample(Optional.of(enormous), true));
    }

    @Test
    void independentFloorAndBootProfilesDecayBeforeCombiningWithGravity() {
        var index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(new Source(0, -1, 0,
                Face.UP, Kind.MAGNETIC, new FloorFieldProfile(16, 0, 2))));
        var surface = new StandingConstraints.Surface(0, -1, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(12, 0, 2), 1.5, 4);
        var boots = List.of(new StandingConstraints.BootCandidate(surface, 1.0, tier));
        var background = new Evidence(Availability.AVAILABLE, new Vec3(0, -32, 0), Vec3.ZERO,
                Map.of(CardinalFrame.Y_POS, new FaceEvidence(32, Set.of(ConstraintSource.NATURAL_BACKGROUND))),
                boots, Optional.empty());
        var merged = background.withFloorSample(Optional.of(index.sampleForStanding(POINT, 1)), true);
        var force = StandingConstraints.resolve(State.initial(), new Request(1, false, merged))
                .authoritativeForce().orElseThrow();
        assertEquals(new Vec3(0, -32, 0), force.gravityAcceleration());
        assertEquals(new Vec3(0, -8, 0), force.magneticAcceleration());
        assertEquals(-6.0, force.bootAcceleration().y(), 0.0);
        assertEquals(new Vec3(0, -46, 0), force.combinedAcceleration());
    }

    @Test
    void opposedMagneticFloorsAndOneBootPullCanCancelWithoutRemovingStandingConstraint() {
        var floor = sample(1, source(0, 0, 0, Face.UP, Kind.MAGNETIC, 3),
                source(0, 2, 0, Face.DOWN, Kind.MAGNETIC, 8));
        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(10, 0, 2), 1.5, 4);
        var background = new Evidence(Availability.AVAILABLE, Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(new StandingConstraints.BootCandidate(surface, 1.0, tier)), Optional.empty());
        var result = StandingConstraints.resolve(State.initial(), new Request(1, false,
                background.withFloorSample(Optional.of(floor), true)));
        var force = result.authoritativeForce().orElseThrow();
        assertEquals(new Vec3(0, 5, 0), force.magneticAcceleration());
        assertEquals(-5.0, force.bootAcceleration().y(), 0.0);
        assertEquals(Vec3.ZERO, force.combinedAcceleration());
        assertEquals(ConstraintPresence.PRESENT, result.presence());
        assertTrue(result.targetReference().isPresent());
        assertTrue(result.actualSupport().isEmpty());
    }

    @Test
    void perpendicularContributionsRemainWorldVectorsDespiteStableUpright() {
        var floor = sample(1, source(1, 1, 0, Face.WEST, Kind.MAGNETIC, 7));
        var surface = new StandingConstraints.Surface(0, 1, -1, Face.SOUTH);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(12, 1, 2), 1.5, 4);
        var background = new Evidence(Availability.AVAILABLE, new Vec3(0, -8, 0), Vec3.ZERO,
                Map.of(CardinalFrame.Y_POS, new FaceEvidence(8, Set.of(ConstraintSource.NATURAL_BACKGROUND))),
                List.of(new StandingConstraints.BootCandidate(surface, 0.5, tier)), Optional.empty());
        var previous = new State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(CardinalFrame.Y_POS), Optional.empty(), -1);
        var result = StandingConstraints.resolve(previous, new Request(1, false,
                background.withFloorSample(Optional.of(floor), true)));
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
        assertEquals(new Vec3(7, -8, -12), result.authoritativeForce().orElseThrow().combinedAcceleration());
    }

    @Test
    void passiveFloorResponseDoesNotCreateAnExtraBootAttraction() {
        // Equipment qualification is supplied by the caller; this test does not sample live boots or NBT.
        var floor = sample(2, source(0, 0, 0, Face.UP, Kind.MAGNETIC, 8));
        var passive = StandingConstraints.resolve(State.initial(), new Request(1, false,
                baseline().withFloorSample(Optional.of(floor), true))).authoritativeForce().orElseThrow();
        assertEquals(Vec3.ZERO, passive.bootAcceleration());
        assertEquals(new Vec3(0, -16, 0), passive.combinedAcceleration());

        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(12, 1, 4), 1.5, 4);
        var outOfReach = new Evidence(Availability.AVAILABLE, Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(new StandingConstraints.BootCandidate(surface, Math.nextUp(1.5), tier)), Optional.empty());
        var inactive = StandingConstraints.resolve(State.initial(), new Request(1, false,
                outOfReach.withFloorSample(Optional.of(floor), true))).authoritativeForce().orElseThrow();
        assertEquals(Vec3.ZERO, inactive.bootAcceleration());
        assertEquals(passive.combinedAcceleration(), inactive.combinedAcceleration());
    }

    @Test
    void multipleBootCandidatesSupplyOnlyOneSelectedPullIncludingRetainedTarget() {
        var stronger = new StandingConstraints.BootCandidate(new StandingConstraints.Surface(0, 1, -1, Face.SOUTH),
                0.5, new StandingConstraints.BootTier(new FloorFieldProfile(12, 1, 2), 1.5, 4));
        var weaker = new StandingConstraints.BootCandidate(new StandingConstraints.Surface(-1, 1, 0, Face.EAST),
                0.5, new StandingConstraints.BootTier(new FloorFieldProfile(5, 1, 2), 1.5, 4));
        var background = new Evidence(Availability.AVAILABLE, new Vec3(0, -4, 0), Vec3.ZERO,
                Map.of(CardinalFrame.Y_POS, new FaceEvidence(4, Set.of(ConstraintSource.NATURAL_BACKGROUND))),
                List.of(weaker, stronger), Optional.empty());
        var floor = sample(1, source(0, 0, 0, Face.UP, Kind.MAGNETIC, 8));
        var evidence = background.withFloorSample(Optional.of(floor), true);
        var preferred = StandingConstraints.resolve(State.initial(), new Request(1, false, evidence));
        var force = preferred.authoritativeForce().orElseThrow();
        assertEquals(Optional.of(CardinalFrame.Y_POS), preferred.targetReference());
        assertEquals(0.0, force.bootAcceleration().x(), 0.0);
        assertEquals(0.0, force.bootAcceleration().y(), 0.0);
        assertEquals(-12.0, force.bootAcceleration().z(), 0.0);
        assertEquals(new Vec3(0, -12, -12), force.combinedAcceleration());

        var retained = new State(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED,
                Optional.of(new StandingConstraints.BootMemory(weaker.surface(), weaker.tier())),
                Optional.of(CardinalFrame.Y_POS), Optional.empty(), -1);
        var result = StandingConstraints.resolve(retained, new Request(2, false, evidence));
        var retainedForce = result.authoritativeForce().orElseThrow();
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference());
        assertEquals(-5.0, retainedForce.bootAcceleration().x(), 0.0);
        assertEquals(0.0, retainedForce.bootAcceleration().y(), 0.0);
        assertEquals(0.0, retainedForce.bootAcceleration().z(), 0.0);
        assertEquals(force.gravityAcceleration(), retainedForce.gravityAcceleration());
        assertEquals(force.magneticAcceleration(), retainedForce.magneticAcceleration());
        assertEquals(new Vec3(-5, -12, 0), retainedForce.combinedAcceleration());
        assertThrows(IllegalArgumentException.class, () -> new Evidence(Availability.AVAILABLE,
                Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(stronger, stronger), Optional.empty()));
    }

    private static Evidence baseline() {
        return new Evidence(Availability.AVAILABLE, Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(), Optional.empty());
    }

    private static StandingSample sample(double response, Source... sources) {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(sources));
        return index.sampleForStanding(POINT, response);
    }

    private static Source source(int x, int y, int z, Face face, Kind kind, double strength) {
        return new Source(x, y, z, face, kind, new FloorFieldProfile(strength, 2.0, 6.0));
    }
}
