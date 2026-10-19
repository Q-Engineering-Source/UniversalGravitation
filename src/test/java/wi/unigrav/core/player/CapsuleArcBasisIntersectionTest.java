package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleArcBasisIntersectionTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void actualR24NegativeXClearFaceUsesCertifiedWholeDomainBound() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(new Vec3d(7.1, 64.69999997615814, 8.5), CardinalFrame.Y_POS,
                CardinalFrame.X_NEG, work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN, prepared.path().fixedDomainProof());
        long before = work.units();
        boolean safe = prepared.path().provesAxisHalfSpace(0, -1, 8, 0, 1, work);
        long delta = work.units() - before;
        System.out.println("R24_NEGATIVE_X wholeFace=" + safe + " work=" + delta);
        assertTrue(safe);
        assertTrue(delta < 1000, "whole-domain bound should avoid subdivision, work=" + delta);
    }

    @Test void nominalIdentityObtainsIndependentNormalizationBoundAndKeepsWorkIdentity() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(new Vec3d(0, 65.89999997615814, 8), CardinalFrame.X_POS,
                CardinalFrame.Y_POS, work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, prepared.path().fixedDomainProof());
        long before = work.units();
        var path = CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Y_POS.up(), work);
        assertEquals(work.units() - before, path.numericWorkDelta());
        assertTrue(path.provesAxisHalfSpace(2, 1, 6, 0, 1, work));
        assertThrows(IllegalArgumentException.class,
                () -> path.provesAxisHalfSpace(2, 1, 6, 0, 1, new StoredDyadic.Work()));
        assertFalse(path.provesAxisHalfSpace(-1, 1, 6, 0, 1, work));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 1);
        var failure = assertThrows(NumericWorkExhaustedException.class,
                () -> CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Y_POS.up(), work));
        assertNull(failure.getCause());
    }

    @Test void boundDoesNotGrantAPlaneBeyondActualSourceOrAnInvalidInterval() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(new Vec3d(7.1, 64.69999997615814, 8.5), CardinalFrame.Y_POS,
                CardinalFrame.X_NEG, work);
        assertFalse(prepared.path().provesAxisHalfSpace(0, -1, 7, 0, 1, work));
        assertFalse(prepared.path().provesAxisHalfSpace(0, -1, 8, .75, .25, work));
        assertFalse(prepared.path().provesAxisHalfSpace(0, -1, Double.NaN, 0, 1, work));
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(Vec3d center, CardinalFrame frame,
            CardinalFrame target, StoredDyadic.Work work) {
        var placement = new PlayerBodyPlacement(center, frame.attitude(), new PlayerBodyShape.Cardinal(frame, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 591),
                new UUID(0, 592), 0, 2), 1024, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), target, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
}