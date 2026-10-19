package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import wi.unigrav.core.player.FreeCapsulePlacementV1.AxisPlane;
import wi.unigrav.core.player.FreeCapsulePlacementV1.Nominal;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawAabb;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawQuaternion;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawVec3;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPlacementCodecTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75);

    @Test
    void exportUsesTheAcceptedNominalOrAxisPlaneRelationAfterTranslation() {
        Rotation3d nominalAttitude = new Rotation3d(0.1, 0.2, 0.3, 0.9);
        Vec3d nominalReference = new Vec3d(2.25, -3.5, 7.75);
        PlayerBodyPlacement nominalSource = new PlayerBodyPlacement(nominalReference, nominalAttitude,
                new PlayerBodyShape.FreeCapsule(SIZE));
        Vec3d nominalCurrent = new Vec3d(0x1.0p24 + 0.125, -3.25, 8.0);
        PlayerBodyPlacement translatedNominal = nominalSource.withTranslatedCenter(nominalCurrent);
        FreeCapsulePlacementV1 nominal = PlayerPlacementCodec.exportFreeV1(translatedNominal);

        assertInstanceOf(Nominal.class, nominal.proof());
        assertRawVector(nominalCurrent, new Vec3d(nominal.currentCenter().x(), nominal.currentCenter().y(),
                nominal.currentCenter().z()));
        assertRawVector(nominalReference, new Vec3d(nominal.centerReference().x(), nominal.centerReference().y(),
                nominal.centerReference().z()));
        assertRawVector(translatedNominal.storedCapsuleGeometry().storedHalfLine(),
                new Vec3d(nominal.storedHalfLine().x(), nominal.storedHalfLine().y(), nominal.storedHalfLine().z()));
        assertNotEquals(nominal.currentCenter(), nominal.centerReference(),
                "translation preserves the nominal proof's original C_ref instead of reclassifying current C");

        Rotation3d axisAttitude = Rotation3d.identity();
        Vec3d axisReference = new Vec3d(Math.nextDown(0x1.0p24), 1.125, 0.0);
        Vec3d realized = new Vec3d(axisReference.x(), Math.nextUp(axisReference.y()), axisReference.z());
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(axisReference, axisAttitude, SIZE, sourceWork);
        Vec3d storedHalfLine = new Vec3d(limits.nominalHalfLine().x(), realized.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, realized, storedHalfLine, sourceWork);
        StoredCapsuleGeometry originalGeometry = StoredCapsuleGeometry.fromRecipe(
                realized, axisAttitude, SIZE, storedHalfLine, relation);
        Vec3d axisCurrent = new Vec3d(0x1.0p24 + 0.125, realized.y(), realized.z());
        StoredCapsuleGeometry translatedGeometry = originalGeometry.withTranslatedCenter(axisCurrent);
        Aabb proxy = expanded(translatedGeometry.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(axisCurrent.x(), proxy.min().y(), axisCurrent.z());
        PlayerBodyPlacement acceptedAxis = PlayerBodyPlacement.fromAcceptedFreeGeometry(axisCurrent,
                axisAttitude, new PlayerBodyShape.FreeCapsule(SIZE), translatedGeometry, proxy, transport);
        FreeCapsulePlacementV1 axis = PlayerPlacementCodec.exportFreeV1(acceptedAxis);

        assertRawVector(axisCurrent, new Vec3d(axis.currentCenter().x(), axis.currentCenter().y(), axis.currentCenter().z()));
        assertRawVector(axisReference,
                new Vec3d(axis.centerReference().x(), axis.centerReference().y(), axis.centerReference().z()));
        AxisPlane axisProof = assertInstanceOf(AxisPlane.class, axis.proof());
        assertRawVector(realized,
                new Vec3d(axisProof.realizedCenter().x(), axisProof.realizedCenter().y(), axisProof.realizedCenter().z()));
        assertEquals(1, axisProof.modifiedAxis());
        assertEquals(1, axisProof.outwardSign());
        assertEquals(Double.doubleToRawLongBits(0.125), axisProof.supportCoordinateBits());
        assertRawVector(storedHalfLine,
                new Vec3d(axis.storedHalfLine().x(), axis.storedHalfLine().y(), axis.storedHalfLine().z()));
        assertNotEquals(axis.centerReference(), axisProof.realizedCenter());
        assertNotEquals(axisProof.realizedCenter(), axis.currentCenter());
        assertNotEquals(axis.centerReference(), axis.currentCenter());
    }

    @Test
    void nominalRestoreReconstructsTheOriginalRecipeAndKeepsExpandedNativeProxy() {
        Rotation3d sourceAttitude = new Rotation3d(0.1, 0.2, 0.3, 0.9);
        Vec3d center = new Vec3d(2.25, -3.5, 7.75);
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits sourceLimits = StoredRealizationLimits.derive(center, sourceAttitude, SIZE, sourceWork);
        RealizationRelation sourceRelation = RealizationRelation.nominal(sourceLimits);
        StoredCapsuleGeometry sourceGeometry = StoredCapsuleGeometry.fromRecipe(
                center, sourceAttitude, SIZE, sourceLimits.nominalHalfLine(), sourceRelation);
        Aabb expandedProxy = expanded(sourceGeometry.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(center.x() + 0.125, expandedProxy.min().y(), center.z() - 0.25);

        PlayerBodyPlacement restored = restore(wire(sourceGeometry, center), sourceAttitude,
                expandedProxy, transport, new StoredDyadic.Work());

        assertTrue(restored.storedCapsuleGeometry().matchesRecipe(center, sourceAttitude, SIZE));
        assertTrue(RawQuaternion.from(sourceAttitude).exactlyMatches(restored.attitude()),
                "the exact quaternion bit pattern is retained");
        assertEquals(sourceGeometry.relation(), restored.storedCapsuleGeometry().relation());
        assertRawVector(sourceGeometry.storedHalfLine(), restored.storedCapsuleGeometry().storedHalfLine());
        assertEquals(sourceGeometry.capsule(), restored.capsule());
        assertEquals(expandedProxy, restored.bounds(), "the accepted owner proxy remains unchanged");
        assertEquals(transport, restored.transport());
    }

    @Test
    void axisPlaneRestoreKeepsThreeDistinctCentersAcrossABinadeAndRetainsProofBits() {
        Rotation3d attitude = Rotation3d.identity();
        Vec3d reference = new Vec3d(Math.nextDown(0x1.0p24), 1.125, 0.0);
        Vec3d realized = new Vec3d(reference.x(), Math.nextUp(reference.y()), reference.z());
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(reference, attitude, SIZE, sourceWork);
        Vec3d storedHalfLine = new Vec3d(limits.nominalHalfLine().x(), realized.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, realized, storedHalfLine, sourceWork);
        StoredCapsuleGeometry original = StoredCapsuleGeometry.fromRecipe(
                realized, attitude, SIZE, storedHalfLine, relation);
        Vec3d current = new Vec3d(0x1.0p24 + 0.125, realized.y(), realized.z());
        StoredCapsuleGeometry translated = original.withTranslatedCenter(current);
        Aabb expandedProxy = expanded(translated.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(current.x(), expandedProxy.min().y(), current.z());

        PlayerBodyPlacement restored = restore(wire(translated, current), attitude,
                expandedProxy, transport, new StoredDyadic.Work());
        StoredCapsuleGeometry actual = restored.storedCapsuleGeometry();

        assertNotEquals(reference, realized, "the relation's candidate center differs from its original C_ref");
        assertNotEquals(realized, current, "the live C differs from the relation's candidate center");
        assertNotEquals(reference, current);
        assertTrue(reference.x() < 0x1.0p24);
        assertTrue(current.x() > 0x1.0p24, "the restored current center crosses the 2^24 binade");
        assertRawVector(reference, actual.relation().limits().centerReference());
        assertRawVector(realized, actual.relation().realizedCenter());
        assertRawVector(current, actual.center());
        assertTrue(RawQuaternion.from(attitude).exactlyMatches(restored.attitude()));
        assertEquals(relation, actual.relation(), "proof equality includes the original reference and support witness");
        assertRawVector(storedHalfLine, actual.storedHalfLine());
        assertEquals(translated.capsule(), actual.capsule());
        assertEquals(expandedProxy, restored.bounds());
        assertEquals(transport, restored.transport());
    }

    @Test
    void exactQuaternionRestorePreservesLegalRawBitsAndRejectsTheNoncanonicalNegatedSign() {
        Rotation3d attitude = new Rotation3d(0.1, 0.2, 0.3, 0.9);
        Vec3d center = new Vec3d(-4.25, 8.5, 2.75);
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, SIZE, sourceWork);
        StoredCapsuleGeometry geometry = StoredCapsuleGeometry.fromRecipe(center, attitude, SIZE,
                limits.nominalHalfLine(), RealizationRelation.nominal(limits));
        FreeCapsulePlacementV1 wire = wire(geometry, center);
        Aabb proxy = expanded(geometry.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(center.x(), proxy.min().y(), center.z());
        RawQuaternion legalBits = RawQuaternion.from(attitude);
        PlayerBodyPlacement legalRestore = PlayerPlacementCodec.restoreFreeV1(wire, legalBits, SIZE,
                RawAabb.from(proxy), RawVec3.from(transport), packet(new StoredDyadic.Work()));
        assertTrue(legalBits.exactlyMatches(legalRestore.attitude()));

        RawQuaternion negativeSign = new RawQuaternion(legalBits.xBits() ^ Long.MIN_VALUE,
                legalBits.yBits() ^ Long.MIN_VALUE, legalBits.zBits() ^ Long.MIN_VALUE,
                legalBits.wBits() ^ Long.MIN_VALUE);
        StoredDyadic.Work work = new StoredDyadic.Work();
        PhysicsDecodeBudget budget = packet(work);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PlayerPlacementCodec.restoreFreeV1(wire, negativeSign, SIZE,
                        RawAabb.from(proxy), RawVec3.from(transport), budget));

        assertEquals(1, budget.quaternionReconstructionCalls(),
                "the actual pinned exact constructor was invoked and rejected the sign");
        assertEquals(108, budget.reservedQuaternionVerificationSlots());
        assertTrue(work.units() > 0, "the external rejection retains raw-field and reservation costs");
        assertEquals(0, budget.chbNarrowPhaseQueryWork());
        assertTrue(hasExactRotationValidationFrame(failure),
                "the original CHB validation stack is preserved at this responsible boundary");
    }

    @Test
    void receiverBudgetIsCumulativeAndThisCodecReportsNoChbQueryWork() {
        Fixture fixture = axisFixture();
        StoredDyadic.Work sharedWork = new StoredDyadic.Work();
        PhysicsDecodeBudget firstBudget = packet(sharedWork);
        restore(fixture.wire(), fixture.attitude(), fixture.proxy(), fixture.transport(), firstBudget);
        long firstTotal = sharedWork.units();

        PhysicsDecodeBudget nextPacketBudget = packet(sharedWork);
        restore(fixture.wire(), fixture.attitude(), fixture.proxy(), fixture.transport(), nextPacketBudget);

        assertTrue(sharedWork.units() > firstTotal, "a new packet view does not reset caller-owned proof work");
        assertEquals(1, firstBudget.quaternionReconstructionCalls());
        assertEquals(108, firstBudget.reservedQuaternionVerificationSlots());
        assertEquals(0, firstBudget.chbNarrowPhaseQueryWork());
        assertEquals(0, nextPacketBudget.chbNarrowPhaseQueryWork());
        assertTrue(firstBudget.peakNumericBits() > 0);
    }

    @Test
    void rawNegativeZeroIsRejectedBeforeQuaternionOrPlacementConstruction() {
        Fixture fixture = axisFixture();
        FreeCapsulePlacementV1 invalidWire = new FreeCapsulePlacementV1(
                new RawVec3(Long.MIN_VALUE, fixture.wire().currentCenter().yBits(), fixture.wire().currentCenter().zBits()),
                fixture.wire().storedHalfLine(), fixture.wire().centerReference(), fixture.wire().proof());
        StoredDyadic.Work work = new StoredDyadic.Work();
        PhysicsDecodeBudget budget = packet(work);

        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                invalidWire, RawQuaternion.from(fixture.attitude()), SIZE,
                RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), budget));

        assertTrue(budget.rawFieldReads() > 0, "raw fields remain charged after rejection");
        assertEquals(0, budget.quaternionReconstructionCalls(), "invalid raw bits fail before exact Q reconstruction");
        assertEquals(0, budget.chbNarrowPhaseQueryWork());
    }

    @Test
    void unsupportedDimensionsAndNegativeZeroEyeHeightFailBeforeQuaternionReconstruction() {
        Fixture fixture = axisFixture();
        for (PlayerBodyShape.Dimensions invalid : new PlayerBodyShape.Dimensions[]{
                new PlayerBodyShape.Dimensions(0.6, 0.5, 0.4),
                new PlayerBodyShape.Dimensions(0.5, 2.0, -0.0)}) {
            StoredDyadic.Work work = new StoredDyadic.Work();
            PhysicsDecodeBudget budget = packet(work);
            assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                    fixture.wire(), RawQuaternion.from(fixture.attitude()), invalid,
                    RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), budget));
            assertTrue(work.units() > 0, "field-read charges remain after invalid profile rejection");
            assertEquals(0, budget.quaternionReconstructionCalls());
            assertEquals(0, budget.chbNarrowPhaseQueryWork());
        }
    }

    @Test
    void zeroAndNonUnitQuaternionTuplesFailAtTheExactChbBoundaryWithReservedCost() {
        Fixture fixture = axisFixture();
        RawQuaternion[] invalid = {
                new RawQuaternion(0L, 0L, 0L, 0L),
                new RawQuaternion(Double.doubleToRawLongBits(2.0), 0L, 0L, 0L)
        };
        for (RawQuaternion raw : invalid) {
            StoredDyadic.Work work = new StoredDyadic.Work();
            PhysicsDecodeBudget budget = packet(work);
            assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                    fixture.wire(), raw, SIZE, RawAabb.from(fixture.proxy()),
                    RawVec3.from(fixture.transport()), budget));
            assertTrue(work.units() > 0);
            assertEquals(1, budget.quaternionReconstructionCalls());
            assertEquals(108, budget.reservedQuaternionVerificationSlots());
            assertEquals(0, budget.chbNarrowPhaseQueryWork());
        }
    }

    @Test
    void axisProofAndActualProxyRejectOneUlpEscapeAndTransportMinYMismatch() {
        Fixture fixture = axisFixture();
        Aabb tooSmall = new Aabb(new Vec3d(Math.nextUp(fixture.geometry().capsule().bounds().min().x()),
                fixture.proxy().min().y(), fixture.proxy().min().z()), fixture.proxy().max());
        assertThrows(IllegalArgumentException.class, () -> restore(fixture.wire(), fixture.attitude(),
                tooSmall, fixture.transport(), new StoredDyadic.Work()));

        Vec3d wrongTransport = new Vec3d(fixture.transport().x(), Math.nextUp(fixture.transport().y()),
                fixture.transport().z());
        assertThrows(IllegalArgumentException.class, () -> restore(fixture.wire(), fixture.attitude(),
                fixture.proxy(), wrongTransport, new StoredDyadic.Work()));
    }

    @Test
    void invalidProofAxisAndQuaternionFailExplicitlyAndKeepConsumedWork() {
        Fixture fixture = axisFixture();
        AxisPlane valid = (AxisPlane) fixture.wire().proof();
        FreeCapsulePlacementV1 invalidAxis = new FreeCapsulePlacementV1(fixture.wire().currentCenter(),
                fixture.wire().storedHalfLine(), fixture.wire().centerReference(),
                new AxisPlane(valid.realizedCenter(), 3, valid.outwardSign(), valid.supportCoordinateBits()));
        StoredDyadic.Work failedProofWork = new StoredDyadic.Work();
        PhysicsDecodeBudget failedProofBudget = packet(failedProofWork);
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                invalidAxis, RawQuaternion.from(fixture.attitude()), SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), failedProofBudget));
        assertTrue(failedProofWork.units() > 0, "the rejected axis proof keeps its already spent numeric work");
        assertEquals(1, failedProofBudget.quaternionReconstructionCalls());
        assertEquals(0, failedProofBudget.chbNarrowPhaseQueryWork());

        FreeCapsulePlacementV1 invalidHalfLine = new FreeCapsulePlacementV1(fixture.wire().currentCenter(),
                new RawVec3(fixture.wire().storedHalfLine().xBits(),
                        Double.doubleToRawLongBits(Math.nextUp(fixture.wire().storedHalfLine().y())),
                        fixture.wire().storedHalfLine().zBits()), fixture.wire().centerReference(), valid);
        StoredDyadic.Work invalidHalfLineWork = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                invalidHalfLine, RawQuaternion.from(fixture.attitude()), SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), packet(invalidHalfLineWork)));
        assertTrue(invalidHalfLineWork.units() > 0, "proof mismatch retains numeric work already spent");

        StoredDyadic.Work failedQuaternionWork = new StoredDyadic.Work();
        PhysicsDecodeBudget failedQuaternionBudget = packet(failedQuaternionWork);
        RawQuaternion negativeZeroQ = new RawQuaternion(Long.MIN_VALUE, 0L, 0L,
                Double.doubleToRawLongBits(1.0));
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                fixture.wire(), negativeZeroQ, SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), failedQuaternionBudget));
        assertTrue(failedQuaternionWork.units() > 0);
        assertEquals(0, failedQuaternionBudget.quaternionReconstructionCalls(),
                "non-canonical Q fails before reserving the exact reconstruction");
    }

    @Test
    void unknownProofTagAndExhaustedSharedWorkAreNotConvertedToSuccess() {
        assertThrows(IllegalArgumentException.class, () -> FreeCapsulePlacementV1.decodeProofTag(
                9, null, 0, 0, 0L));

        Fixture fixture = axisFixture();
        StoredDyadic.Work nearlySpent = new StoredDyadic.Work();
        nearlySpent.charge(StoredDyadic.MAX_WORK_UNITS - 180L);
        long before = nearlySpent.units();
        PhysicsDecodeBudget budget = packet(nearlySpent);
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                fixture.wire(), RawQuaternion.from(fixture.attitude()), SIZE,
                RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), budget));
        assertTrue(nearlySpent.units() > before, "attempted work remains visible when a later bound is exhausted");
        assertEquals(0, budget.chbNarrowPhaseQueryWork());
    }

    @Test
    void failedDecodeChargesAndPeakCarryAcrossNewPacketBudgetsOnTheSameWork() {
        Fixture fixture = axisFixture();
        AxisPlane axisPlane = (AxisPlane) fixture.wire().proof();
        StoredDyadic.Work sharedWork = new StoredDyadic.Work();

        FreeCapsulePlacementV1 invalidAxis = new FreeCapsulePlacementV1(fixture.wire().currentCenter(),
                fixture.wire().storedHalfLine(), fixture.wire().centerReference(),
                new AxisPlane(axisPlane.realizedCenter(), 3, axisPlane.outwardSign(), axisPlane.supportCoordinateBits()));
        PhysicsDecodeBudget firstPacket = packet(sharedWork);
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                invalidAxis, RawQuaternion.from(fixture.attitude()), SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), firstPacket));
        long afterFirstFailure = sharedWork.units();
        int peakAfterFirstFailure = sharedWork.peakBits();

        FreeCapsulePlacementV1 invalidHalfLine = new FreeCapsulePlacementV1(fixture.wire().currentCenter(),
                new RawVec3(fixture.wire().storedHalfLine().xBits(),
                        Double.doubleToRawLongBits(Math.nextUp(fixture.wire().storedHalfLine().y())),
                        fixture.wire().storedHalfLine().zBits()), fixture.wire().centerReference(), axisPlane);
        PhysicsDecodeBudget secondPacket = packet(sharedWork);
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                invalidHalfLine, RawQuaternion.from(fixture.attitude()), SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), secondPacket));
        long afterSecondFailure = sharedWork.units();
        int peakAfterSecondFailure = sharedWork.peakBits();

        PhysicsDecodeBudget thirdPacket = packet(sharedWork);
        PlayerBodyPlacement restored = PlayerPlacementCodec.restoreFreeV1(fixture.wire(),
                RawQuaternion.from(fixture.attitude()), SIZE, RawAabb.from(fixture.proxy()),
                RawVec3.from(fixture.transport()), thirdPacket);

        assertTrue(sharedWork.units() > afterFirstFailure);
        assertTrue(afterSecondFailure > afterFirstFailure,
                "a new packet's invalid proof adds to the same cumulative numeric Work");
        assertTrue(sharedWork.units() > afterSecondFailure,
                "a later successful packet also pays its full proof cost");
        assertTrue(peakAfterSecondFailure >= peakAfterFirstFailure);
        assertTrue(sharedWork.peakBits() >= peakAfterSecondFailure);
        assertEquals(fixture.geometry().center(), restored.center());
        assertEquals(1, thirdPacket.quaternionReconstructionCalls());
        assertEquals(0, firstPacket.chbNarrowPhaseQueryWork());
        assertEquals(0, secondPacket.chbNarrowPhaseQueryWork());
        assertEquals(0, thirdPacket.chbNarrowPhaseQueryWork());
    }

    private static PlayerBodyPlacement restore(FreeCapsulePlacementV1 wire, Rotation3d attitude,
                                               Aabb proxy, Vec3d transport, StoredDyadic.Work work) {
        return restore(wire, attitude, proxy, transport, packet(work));
    }

    private static PlayerBodyPlacement restore(FreeCapsulePlacementV1 wire, Rotation3d attitude,
                                               Aabb proxy, Vec3d transport, PhysicsDecodeBudget budget) {
        return PlayerPlacementCodec.restoreFreeV1(wire, RawQuaternion.from(attitude), SIZE,
                RawAabb.from(proxy), RawVec3.from(transport), budget);
    }

    private static PhysicsDecodeBudget packet(StoredDyadic.Work callerOwnedWork) {
        return new PhysicsDecodeBudget(256, callerOwnedWork);
    }

    private static FreeCapsulePlacementV1 wire(StoredCapsuleGeometry geometry, Vec3d currentCenter) {
        RealizationRelation relation = geometry.relation();
        FreeCapsulePlacementV1.Proof proof = switch (relation.kind()) {
            case NOMINAL -> new Nominal();
            case CONTAINED_CARDINAL, FIXED_HALF_LENGTH -> throw new IllegalArgumentException(
                    "proposed contained source has no FreeV1 accepted-wire proof");
            case AXIS_PLANE -> new AxisPlane(RawVec3.from(relation.realizedCenter()),
                    relation.modifiedAxis(), relation.outwardSign(),
                    Double.doubleToRawLongBits(relation.supportCoordinate()));
        };
        return new FreeCapsulePlacementV1(RawVec3.from(currentCenter),
                RawVec3.from(geometry.storedHalfLine()), RawVec3.from(relation.limits().centerReference()), proof);
    }

    private static Fixture axisFixture() {
        Rotation3d attitude = Rotation3d.identity();
        Vec3d reference = new Vec3d(Math.nextDown(0x1.0p24), 1.125, 0.0);
        Vec3d realized = new Vec3d(reference.x(), Math.nextUp(reference.y()), reference.z());
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(reference, attitude, SIZE, work);
        Vec3d storedHalfLine = new Vec3d(limits.nominalHalfLine().x(), realized.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, realized, storedHalfLine, work);
        StoredCapsuleGeometry original = StoredCapsuleGeometry.fromRecipe(
                realized, attitude, SIZE, storedHalfLine, relation);
        Vec3d current = new Vec3d(0x1.0p24 + 0.125, realized.y(), realized.z());
        StoredCapsuleGeometry currentGeometry = original.withTranslatedCenter(current);
        Aabb proxy = expanded(currentGeometry.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(current.x(), proxy.min().y(), current.z());
        return new Fixture(attitude, currentGeometry, wire(currentGeometry, current), proxy, transport);
    }

    private static Aabb expanded(Aabb bounds, double margin) {
        return new Aabb(bounds.min().subtract(new Vec3d(margin, margin, margin)),
                bounds.max().add(new Vec3d(margin, margin, margin)));
    }

    private static void assertRawVector(Vec3d expected, Vec3d actual) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()));
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()));
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()));
    }

    private static boolean hasExactRotationValidationFrame(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().contains("ExactRotationReconstruction")) return true;
        }
        return false;
    }

    private record Fixture(Rotation3d attitude, StoredCapsuleGeometry geometry,
                           FreeCapsulePlacementV1 wire, Aabb proxy, Vec3d transport) { }
}
