package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldProfile;

class StandingConstraintsTest {
    private static final StandingConstraints.BootTier TIER = new StandingConstraints.BootTier(
            new FloorFieldProfile(12.0, 0.5, 4.0), StandingConstraints.DEFAULT_BOOT_CAPTURE_REACH_METRES, 4);
    private static final StandingConstraints.Surface UP_SURFACE = surface(2, 3, 4, Face.UP);
    private static final StandingConstraints.Surface EAST_SURFACE = surface(5, 6, 7, Face.EAST);

    @Test
    void endpointQualificationRejectsDepartedOrReplacedBootDespiteSameFloorAndRetainedMemory() {
        var floor = Map.of(CardinalFrame.Y_POS, new StandingConstraints.FaceEvidence(12,
                Set.of(StandingConstraints.ConstraintSource.FLOOR)));
        var original = available(Vec3.ZERO, Vec3.ZERO, floor,
                List.of(boot(UP_SURFACE, 0.25, TIER)), Optional.empty());
        var first = resolve(StandingConstraints.State.initial(), 1, false, original);
        var departed = available(Vec3.ZERO, Vec3.ZERO, floor, List.of(), Optional.empty());
        var after = resolve(first.nextState(), 2, false, departed);
        assertEquals(first.targetReference(), after.targetReference());
        assertEquals(first.bootMemory(), after.bootMemory(), "history alone cannot prove current qualification");
        assertFalse(PlayerPhysicsStep.FreeStandingQualification.from(original)
                .equals(PlayerPhysicsStep.FreeStandingQualification.from(departed)));
        var replacement = available(Vec3.ZERO, Vec3.ZERO, floor,
                List.of(boot(surface(3, 3, 4, Face.UP), 0.25, TIER)), Optional.empty());
        assertEquals(first.targetReference(), resolve(first.nextState(), 2, false, replacement).targetReference());
        assertFalse(PlayerPhysicsStep.FreeStandingQualification.from(original)
                .equals(PlayerPhysicsStep.FreeStandingQualification.from(replacement)));
        var nearerSameProducer = available(Vec3.ZERO, Vec3.ZERO, floor,
                List.of(boot(UP_SURFACE, 0.125, TIER)), Optional.empty());
        assertEquals(PlayerPhysicsStep.FreeStandingQualification.from(original),
                PlayerPhysicsStep.FreeStandingQualification.from(nearerSameProducer));
    }

    @Test
    void defaultReachAndTierFalloffCaptureAcrossGapWithoutClaimingAttachment() {
        var near = boot(UP_SURFACE, 1.5, TIER);
        var outsideReach = boot(UP_SURFACE, 1.5000001, TIER);
        var decayedAway = boot(UP_SURFACE, 4.0, new StandingConstraints.BootTier(
                new FloorFieldProfile(12.0, 0.5, 4.0), 5.0, 2));
        assertTrue(near.isEffective());
        assertTrue(near.pullMagnitude() > 0.0);
        assertFalse(outsideReach.isEffective());
        assertFalse(decayedAway.isEffective());

        var result = resolve(StandingConstraints.State.initial(), 10, false,
                available(Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(near), Optional.empty()));
        assertEquals(StandingConstraints.ConstraintPresence.PRESENT, result.presence());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
        assertEquals(StandingConstraints.BootPhase.ACQUIRING, result.nextState().bootPhase());
        assertEquals(Optional.empty(), result.actualSupport());
        assertTrue(result.authoritativeForce().orElseThrow().bootAcceleration().y() < 0.0);
    }

    @Test
    void cancellationAndDiagonalForcesKeepTheirScoredCardinalReferenceAndFullForce() {
        var cancelled = resolve(StandingConstraints.State.initial(), 1, false,
                available(Vec3.ZERO, Vec3.ZERO, scores(CardinalFrame.Y_POS, 2.0, CardinalFrame.Y_NEG, 2.0),
                        List.of(), Optional.empty()));
        assertEquals(Optional.of(CardinalFrame.Y_POS), cancelled.targetReference());
        assertEquals(Vec3.ZERO, cancelled.authoritativeForce().orElseThrow().combinedAcceleration());

        Vec3 diagonal = new Vec3(3.0, -7.0, 11.0);
        var diagonalResult = resolve(StandingConstraints.State.initial(), 2, false,
                available(diagonal, Vec3.ZERO, scores(CardinalFrame.Z_NEG, 3.0), List.of(), Optional.empty()));
        assertEquals(Optional.of(CardinalFrame.Z_NEG), diagonalResult.targetReference());
        assertEquals(diagonal, diagonalResult.authoritativeForce().orElseThrow().gravityAcceleration());
    }

    @Test
    void naturalBackgroundCandidateUsesTheSameFaceScoreContract() {
        Map<CardinalFrame, StandingConstraints.FaceEvidence> natural = Map.of(CardinalFrame.X_NEG,
                new StandingConstraints.FaceEvidence(5.0, Set.of(StandingConstraints.ConstraintSource.NATURAL_BACKGROUND)));
        var result = resolve(StandingConstraints.State.initial(), 1, false,
                available(new Vec3(9, 0, 0), Vec3.ZERO, natural, List.of(), Optional.empty()));
        assertEquals(Optional.of(CardinalFrame.X_NEG), result.targetReference());
        assertEquals(new Vec3(9, 0, 0), result.authoritativeForce().orElseThrow().gravityAcceleration());
    }

    @Test
    void legacyMixedReferenceRemainsACompositeFallbackWithoutBecomingPureFloorStrength() {
        var evidence = new StandingConstraints.Evidence(StandingConstraints.Availability.AVAILABLE,
                Vec3.ZERO, Vec3.ZERO,
                Map.of(CardinalFrame.X_POS, new StandingConstraints.FaceEvidence(13.0,
                        Set.of(StandingConstraints.ConstraintSource.FLOOR,
                                StandingConstraints.ConstraintSource.NATURAL_BACKGROUND))),
                List.of(), Optional.empty());

        assertEquals(0.0, evidence.floorGravityStrengths().strength(CardinalFrame.X_POS),
                "the legacy aggregate has no recoverable floor-only magnitude");
        var result = resolve(StandingConstraints.State.initial(), 1, false, evidence);
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference(),
                "a valid legacy composite still retains its historical generic reference behavior");
    }

    @Test
    void actualSupportPrecedesSameClassReferenceContinuityAndScores() {
        var previous = new StandingConstraints.State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(CardinalFrame.Z_POS), Optional.empty(), -1);
        var result = resolve(previous, 3, false, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.Z_POS, 1.0, CardinalFrame.X_POS, 99.0), List.of(), Optional.of(EAST_SURFACE)));
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference());
        assertEquals(Optional.of(EAST_SURFACE), result.actualSupport());
    }

    @Test
    void floorReferenceWinsWithoutFabricatingBootAttachment() {
        var memory = new StandingConstraints.BootMemory(UP_SURFACE, TIER);
        var previous = new StandingConstraints.State(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED, Optional.of(memory),
                Optional.of(CardinalFrame.X_POS), Optional.empty(), -1);
        var result = resolve(previous, 4, false, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.X_POS, 100.0),
                List.of(boot(UP_SURFACE, 1.0, TIER), boot(EAST_SURFACE, 0.0, TIER)), Optional.of(EAST_SURFACE)));
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference());
        assertEquals(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED, result.nextState().bootPhase());
        assertEquals(Optional.of(EAST_SURFACE), result.actualSupport());
    }

    @Test
    void confirmedAttachedBootKeepsItsSupportedAxisAgainstAnotherFloorScoreAndOnJump() {
        var memory = new StandingConstraints.BootMemory(UP_SURFACE, TIER);
        var attached = StandingConstraints.State.attached(memory, CardinalFrame.Y_POS, UP_SURFACE);
        var evidence = available(Vec3.ZERO, Vec3.ZERO, scores(CardinalFrame.X_POS, 100.0),
                List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE));

        var retained = resolve(attached, 9, false, evidence);
        assertEquals(Optional.of(CardinalFrame.Y_POS), retained.targetReference());
        assertEquals(StandingConstraints.BootPhase.ATTACHED, retained.nextState().bootPhase());
        assertEquals(Optional.of(UP_SURFACE), retained.actualSupport());

        var jumped = resolve(attached, 10, true, evidence);
        assertEquals(Optional.of(CardinalFrame.Y_POS), jumped.targetReference());
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, jumped.nextState().bootPhase());
        assertEquals(14, jumped.nextState().protectionUntilServerTick());
        assertEquals(Optional.of(UP_SURFACE), jumped.actualSupport());
    }

    @Test
    void requestedReferenceNeverPromotesItselfToAcceptedPhysicalAuthority() {
        var previous = new StandingConstraints.State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(CardinalFrame.Y_POS), Optional.empty(), -1);
        var result = resolve(previous, 5, false, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.X_POS, 8.0), List.of(boot(EAST_SURFACE, 0.25, TIER)), Optional.empty()));
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.nextState().acceptedReference());
        assertEquals(StandingConstraints.BootPhase.ACQUIRING, result.nextState().bootPhase());
    }

    @Test
    void bootAdhesionIsSeparateFromFullFieldVectorsAndFollowsTierFalloff() {
        Vec3 gravity = new Vec3(3.0, -7.0, 11.0);
        Vec3 magnetic = new Vec3(-2.0, 5.0, 13.0);
        var near = resolve(StandingConstraints.State.initial(), 6, false, available(gravity, magnetic, Map.of(),
                List.of(boot(UP_SURFACE, 0.5, TIER)), Optional.empty())).authoritativeForce().orElseThrow();
        var far = resolve(StandingConstraints.State.initial(), 6, false, available(gravity, magnetic, Map.of(),
                List.of(boot(UP_SURFACE, 1.4, TIER)), Optional.empty())).authoritativeForce().orElseThrow();
        assertEquals(gravity, near.gravityAcceleration());
        assertEquals(magnetic, near.magneticAcceleration());
        assertTrue(near.bootAcceleration().y() < 0.0);
        assertTrue(Math.abs(near.bootAcceleration().y()) > Math.abs(far.bootAcceleration().y()));
        // A zero physical component may carry either IEEE-754 sign after inward scaling.
        assertEquals(0.0, near.bootAcceleration().x(), 0.0);
        assertEquals(0.0, near.bootAcceleration().z(), 0.0);
    }

    @Test
    void magneticFloorPrecedesNaturalContinuityWithoutChangingFieldOrBootForce() {
        Vec3 naturalGravity = new Vec3(0.0, -9.0, 0.0);
        var previous = new StandingConstraints.State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(CardinalFrame.Y_POS), Optional.empty(), -1);
        var result = resolve(previous, 7, false, available(naturalGravity, Vec3.ZERO,
                Map.of(CardinalFrame.Y_POS, new StandingConstraints.FaceEvidence(9.0,
                        Set.of(StandingConstraints.ConstraintSource.NATURAL_BACKGROUND))),
                List.of(boot(EAST_SURFACE, 0.5, TIER)), Optional.empty()));
        var force = result.authoritativeForce().orElseThrow();
        assertEquals(Optional.of(CardinalFrame.X_POS), result.targetReference());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.nextState().acceptedReference());
        assertEquals(naturalGravity, force.gravityAcceleration());
        assertEquals(Vec3.ZERO, force.magneticAcceleration());
        assertTrue(force.bootAcceleration().x() < 0.0);
        assertEquals(0.0, force.bootAcceleration().y(), 0.0);
        assertEquals(0.0, force.bootAcceleration().z(), 0.0);
    }

    @Test
    void attachedJumpReleasesAttachmentButKeepsCurrentPullWithoutFabricatingSupport() {
        var attached = StandingConstraints.State.attached(new StandingConstraints.BootMemory(UP_SURFACE, TIER),
                CardinalFrame.Y_POS, UP_SURFACE);
        var jumped = resolve(attached, 20, true, available(new Vec3(1, 2, 3), Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 2.0), List.of(boot(UP_SURFACE, 1.0, TIER)), Optional.of(UP_SURFACE)));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, jumped.nextState().bootPhase());
        assertEquals(24, jumped.nextState().protectionUntilServerTick());
        assertEquals(Optional.of(CardinalFrame.Y_POS), jumped.targetReference());
        assertEquals(Optional.of(UP_SURFACE), jumped.actualSupport());
        assertBootPull(jumped, CardinalFrame.Y_POS, TIER.profile().strengthAt(1.0));
        assertEquals(new Vec3(1, 2, 3), jumped.authoritativeForce().orElseThrow().gravityAcceleration());

        var airborne = resolve(jumped.nextState(), 21, false, available(new Vec3(1, 2, 3), Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 2.0), List.of(boot(UP_SURFACE, 1.0, TIER)), Optional.empty()));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, airborne.nextState().bootPhase());
        assertEquals(Optional.empty(), airborne.actualSupport());
        assertEquals(Optional.of(CardinalFrame.Y_POS), airborne.targetReference());
        assertBootPull(airborne, CardinalFrame.Y_POS, TIER.profile().strengthAt(1.0));
    }

    @ParameterizedTest
    @EnumSource(Face.class)
    void bootRangeAlonePreservesCardinalConstraintAcrossAirborneJumpProtection(Face face) {
        var selectedSurface = surface(2, 3, 4, face);
        var frame = CardinalFrame.fromFloorFace(face);
        var attached = StandingConstraints.State.attached(new StandingConstraints.BootMemory(selectedSurface, TIER),
                frame, selectedSurface);
        var jumped = resolve(attached, 20, true, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(selectedSurface, 0.0, TIER)), Optional.of(selectedSurface)));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, jumped.nextState().bootPhase());
        assertEquals(StandingConstraints.ConstraintPresence.PRESENT, jumped.presence());
        assertBootPull(jumped, frame, TIER.profile().strengthAt(0.0));

        var airborne = resolve(jumped.nextState(), 21, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(selectedSurface, 1.5, TIER)), Optional.empty()));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, airborne.nextState().bootPhase());
        assertEquals(StandingConstraints.ConstraintPresence.PRESENT, airborne.presence());
        assertEquals(Optional.of(frame), airborne.targetReference());
        assertEquals(Optional.of(frame), airborne.nextState().acceptedReference());
        assertEquals(Optional.empty(), airborne.actualSupport());
        assertBootPull(airborne, frame, TIER.profile().strengthAt(1.5));

        var expired = resolve(airborne.nextState(), 24, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(selectedSurface, 1.5, TIER)), Optional.empty()));
        assertEquals(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED, expired.nextState().bootPhase());
        assertEquals(StandingConstraints.ConstraintPresence.PRESENT, expired.presence());
        assertEquals(Optional.of(frame), expired.targetReference());
        assertEquals(Optional.of(frame), expired.nextState().acceptedReference());
        assertEquals(Optional.empty(), expired.actualSupport());
        var pull = expired.authoritativeForce().orElseThrow().bootAcceleration();
        var up = frame.up();
        assertTrue(pull.x() * up.x() + pull.y() * up.y() + pull.z() * up.z() < 0.0);

        var outside = resolve(expired.nextState(), 25, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(selectedSurface, Math.nextUp(1.5), TIER)), Optional.empty()));
        assertEquals(StandingConstraints.ConstraintPresence.ABSENT, outside.presence());
        assertEquals(StandingConstraints.BootPhase.OUT_OF_RANGE, outside.nextState().bootPhase());
        assertEquals(Optional.empty(), outside.targetReference());
        assertEquals(Optional.of(frame), outside.nextState().acceptedReference());
        assertEquals(Vec3.ZERO, outside.authoritativeForce().orElseThrow().combinedAcceleration());
    }

    @Test
    void rawAttachedStateWithMismatchedAcceptedReferenceCannotReleaseIntoProtection() {
        var inconsistent = new StandingConstraints.State(StandingConstraints.BootPhase.ATTACHED,
                Optional.of(new StandingConstraints.BootMemory(UP_SURFACE, TIER)), Optional.of(CardinalFrame.X_POS),
                Optional.of(UP_SURFACE), -1);
        var result = resolve(inconsistent, 20, true, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 3.0), List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE)));
        assertEquals(StandingConstraints.BootPhase.ACQUIRING, result.nextState().bootPhase());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
        assertTrue(result.authoritativeForce().orElseThrow().bootAcceleration().y() < 0.0);
    }

    @Test
    void protectionSurvivesOutOfRangeAndSurfaceSwitchUntilItsServerTickDeadline() {
        var attached = StandingConstraints.State.attached(new StandingConstraints.BootMemory(UP_SURFACE, TIER),
                CardinalFrame.Y_POS, UP_SURFACE);
        var jumped = resolve(attached, 20, true, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 3.0), List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE)));
        var outside = resolve(jumped.nextState(), 21, false,
                available(Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(), Optional.empty()));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, outside.nextState().bootPhase());
        assertEquals(24, outside.nextState().protectionUntilServerTick());
        assertEquals(Optional.empty(), outside.targetReference());
        assertEquals(Vec3.ZERO, outside.authoritativeForce().orElseThrow().bootAcceleration());

        var switchedSurface = resolve(outside.nextState(), 22, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(EAST_SURFACE, 0.0, TIER)), Optional.of(EAST_SURFACE)));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, switchedSurface.nextState().bootPhase());
        assertEquals(StandingConstraints.ConstraintPresence.PRESENT, switchedSurface.presence());
        assertEquals(Optional.of(CardinalFrame.X_POS), switchedSurface.targetReference());
        assertEquals(Optional.of(CardinalFrame.Y_POS), switchedSurface.nextState().acceptedReference());
        assertEquals(Optional.of(UP_SURFACE), switchedSurface.bootMemory().map(StandingConstraints.BootMemory::surface));
        assertEquals(Optional.of(EAST_SURFACE), switchedSurface.actualSupport());
        assertBootPull(switchedSurface, CardinalFrame.X_POS, TIER.profile().strengthAt(0.0));

        var reentry = resolve(switchedSurface.nextState(), 23, false, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 3.0), List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE)));
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, reentry.nextState().bootPhase());
        assertEquals(Optional.of(UP_SURFACE), reentry.actualSupport());
        assertBootPull(reentry, CardinalFrame.Y_POS, TIER.profile().strengthAt(0.0));

        var expired = resolve(reentry.nextState(), 24, false, available(Vec3.ZERO, Vec3.ZERO,
                scores(CardinalFrame.Y_POS, 3.0), List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE)));
        assertEquals(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED, expired.nextState().bootPhase());
    }

    @Test
    void outOfRangeRequestsNoReferenceAndNeverReturnsCapsulePermission() {
        var previous = new StandingConstraints.State(StandingConstraints.BootPhase.IN_RANGE_UNATTACHED,
                Optional.of(new StandingConstraints.BootMemory(UP_SURFACE, TIER)), Optional.of(CardinalFrame.Y_POS),
                Optional.empty(), -1);
        var result = resolve(previous, 40, false,
                available(Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(boot(UP_SURFACE, 1.6, TIER)), Optional.empty()));
        assertEquals(StandingConstraints.ConstraintPresence.ABSENT, result.presence());
        assertEquals(Optional.empty(), result.targetReference());
        assertEquals(StandingConstraints.BootPhase.OUT_OF_RANGE, result.nextState().bootPhase());
        assertTrue(result.nextState().bootMemory().isPresent());
    }

    @ParameterizedTest
    @EnumSource(Face.class)
    void constantOneGAndWeakerBootTierKeepTheirForceThroughoutJumpProtection(Face face) {
        var selectedSurface = surface(2, 3, 4, face);
        var frame = CardinalFrame.fromFloorFace(face);
        // UG's native-player 1G is 32 m/s虏. No background field is added here.
        // This verifies force continuity, not the still-unwired Minecraft jump/travel lifecycle.
        for (double strength : new double[] {32.0, 8.0}) {
            var tier = new StandingConstraints.BootTier(new FloorFieldProfile(strength, 1.5, 2.0), 1.5, 4);
            var state = StandingConstraints.State.attached(new StandingConstraints.BootMemory(selectedSurface, tier),
                    frame, selectedSurface);
            double[] gaps = {0.0, 0.42, 1.25, 1.5};
            for (int step = 0; step < gaps.length; step++) {
                var result = resolve(state, 20 + step, step == 0,
                        available(Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(boot(selectedSurface, gaps[step], tier)),
                                step == 0 ? Optional.of(selectedSurface) : Optional.empty()));
                assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, result.nextState().bootPhase());
                assertEquals(24, result.nextState().protectionUntilServerTick());
                assertEquals(StandingConstraints.ConstraintPresence.PRESENT, result.presence());
                assertEquals(Optional.of(frame), result.nextState().acceptedReference());
                assertEquals(step == 0 ? Optional.of(selectedSurface) : Optional.empty(), result.actualSupport());
                assertBootPull(result, frame, strength);
                var force = result.authoritativeForce().orElseThrow();
                assertEquals(Vec3.ZERO, force.gravityAcceleration());
                assertEquals(Vec3.ZERO, force.magneticAcceleration());
                assertEquals(strength, -force.combinedAcceleration().dot(
                        new Vec3(frame.up().x(), frame.up().y(), frame.up().z())), 0.0);
                state = result.nextState();
            }
        }
    }

    @Test
    void protectedPullUsesCurrentGapAndTierRatherThanStoredCaptureStrength() {
        var state = StandingConstraints.State.attached(new StandingConstraints.BootMemory(UP_SURFACE, TIER),
                CardinalFrame.Y_POS, UP_SURFACE);
        state = resolve(state, 20, true, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE))).nextState();
        var far = resolve(state, 21, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(UP_SURFACE, 1.4, TIER)), Optional.empty()));
        assertBootPull(far, CardinalFrame.Y_POS, TIER.profile().strengthAt(1.4));
        var near = resolve(far.nextState(), 22, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(UP_SURFACE, 0.5, TIER)), Optional.empty()));
        assertBootPull(near, CardinalFrame.Y_POS, TIER.profile().strengthAt(0.5));
        assertTrue(near.authoritativeForce().orElseThrow().bootAcceleration().y()
                < far.authoritativeForce().orElseThrow().bootAcceleration().y());

        var weakTier = new StandingConstraints.BootTier(new FloorFieldProfile(3.0, 0.5, 4.0), 1.5, 100);
        var weakened = resolve(near.nextState(), 23, false, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(UP_SURFACE, 0.5, weakTier)), Optional.empty()));
        assertBootPull(weakened, CardinalFrame.Y_POS, 3.0);
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, weakened.nextState().bootPhase());
        assertEquals(24, weakened.nextState().protectionUntilServerTick());
        assertEquals(Optional.of(CardinalFrame.Y_POS), weakened.nextState().acceptedReference());
        assertEquals(Optional.empty(), weakened.actualSupport());
    }

    @Test
    void unavailableProtectedSampleNeverReusesHistoricalPull() {
        var state = StandingConstraints.State.attached(new StandingConstraints.BootMemory(UP_SURFACE, TIER),
                CardinalFrame.Y_POS, UP_SURFACE);
        var jumped = resolve(state, 20, true, available(Vec3.ZERO, Vec3.ZERO, Map.of(),
                List.of(boot(UP_SURFACE, 0.0, TIER)), Optional.of(UP_SURFACE)));
        var result = resolve(jumped.nextState(), 21, false, StandingConstraints.Evidence.unavailable());
        assertEquals(StandingConstraints.ConstraintPresence.UNAVAILABLE, result.presence());
        assertTrue(result.authoritativeForce().isEmpty());
        assertEquals(StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED, result.nextState().bootPhase());
        assertEquals(24, result.nextState().protectionUntilServerTick());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.nextState().acceptedReference());
        assertEquals(Optional.empty(), result.actualSupport());
    }

    @Test
    void unavailableRetainsReferenceAndBootKnowledgeButProvidesNoForceOrInventedSupport() {
        var previous = StandingConstraints.State.attached(new StandingConstraints.BootMemory(UP_SURFACE, TIER),
                CardinalFrame.Y_POS, UP_SURFACE);
        var result = resolve(previous, 50, false, StandingConstraints.Evidence.unavailable());
        assertEquals(StandingConstraints.ConstraintPresence.UNAVAILABLE, result.presence());
        assertEquals(Optional.empty(), result.targetReference());
        assertEquals(Optional.of(CardinalFrame.Y_POS), result.nextState().acceptedReference());
        assertEquals(Optional.of(UP_SURFACE), result.bootMemory().map(StandingConstraints.BootMemory::surface));
        assertEquals(Optional.empty(), result.actualSupport());
        assertTrue(result.authoritativeForce().isEmpty());
    }

    @Test
    void tiesUseCardinalEnumOrderAndInputsAreDefensivelyCopied() {
        EnumMap<CardinalFrame, StandingConstraints.FaceEvidence> input = new EnumMap<>(CardinalFrame.class);
        input.put(CardinalFrame.Z_NEG, face(5));
        input.put(CardinalFrame.X_NEG, face(5));
        var evidence = available(Vec3.ZERO, Vec3.ZERO, input, List.of(), Optional.empty());
        input.clear();
        assertEquals(Optional.of(CardinalFrame.X_NEG), resolve(StandingConstraints.State.initial(), 1, false, evidence).targetReference());
        assertThrows(UnsupportedOperationException.class, () -> evidence.faceEvidence().put(CardinalFrame.Y_POS, face(1)));
        assertThrows(UnsupportedOperationException.class, () -> face(1).sources().add(StandingConstraints.ConstraintSource.FLOOR));
    }

    @Test
    void invalidInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new StandingConstraints.BootTier(TIER.profile(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StandingConstraints.BootTier(TIER.profile(), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> boot(UP_SURFACE, -0.1, TIER));
        assertThrows(IllegalArgumentException.class, () -> new StandingConstraints.FaceEvidence(-1, Set.of(StandingConstraints.ConstraintSource.FLOOR)));
        assertThrows(IllegalArgumentException.class, () -> new StandingConstraints.Request(-1, false, StandingConstraints.Evidence.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> new StandingConstraints.Evidence(StandingConstraints.Availability.UNAVAILABLE,
                new Vec3(1, 0, 0), Vec3.ZERO, Map.of(), List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> StandingConstraints.State.attached(
                new StandingConstraints.BootMemory(UP_SURFACE, TIER), CardinalFrame.Y_POS, EAST_SURFACE));
        assertThrows(IllegalArgumentException.class, () -> StandingConstraints.State.attached(
                new StandingConstraints.BootMemory(UP_SURFACE, TIER), CardinalFrame.X_POS, UP_SURFACE));
    }

    private static void assertBootPull(StandingConstraints.Resolution result, CardinalFrame frame, double strength) {
        var pull = result.authoritativeForce().orElseThrow().bootAcceleration();
        assertEquals(-frame.up().x() * strength, pull.x(), 0.0);
        assertEquals(-frame.up().y() * strength, pull.y(), 0.0);
        assertEquals(-frame.up().z() * strength, pull.z(), 0.0);
    }

    private static StandingConstraints.Resolution resolve(StandingConstraints.State state, long tick, boolean jump,
                                                           StandingConstraints.Evidence evidence) {
        return StandingConstraints.resolve(state, new StandingConstraints.Request(tick, jump, evidence));
    }

    private static StandingConstraints.Evidence available(Vec3 gravity, Vec3 magnetic,
                                                          Map<CardinalFrame, StandingConstraints.FaceEvidence> scores,
                                                          List<StandingConstraints.BootCandidate> boots,
                                                          Optional<StandingConstraints.Surface> support) {
        return new StandingConstraints.Evidence(StandingConstraints.Availability.AVAILABLE, gravity, magnetic, scores, boots, support);
    }

    private static Map<CardinalFrame, StandingConstraints.FaceEvidence> scores(Object... values) {
        EnumMap<CardinalFrame, StandingConstraints.FaceEvidence> result = new EnumMap<>(CardinalFrame.class);
        for (int index = 0; index < values.length; index += 2) {
            result.put((CardinalFrame) values[index], face(((Number) values[index + 1]).doubleValue()));
        }
        return result;
    }

    private static StandingConstraints.FaceEvidence face(double score) {
        return new StandingConstraints.FaceEvidence(score, Set.of(StandingConstraints.ConstraintSource.FLOOR));
    }

    private static StandingConstraints.BootCandidate boot(StandingConstraints.Surface surface, double gap,
                                                           StandingConstraints.BootTier tier) {
        return new StandingConstraints.BootCandidate(surface, gap, tier);
    }

    private static StandingConstraints.Surface surface(int x, int y, int z, Face face) {
        return new StandingConstraints.Surface(x, y, z, face);
    }
}
