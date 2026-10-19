package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalJointRnDomainTest {
    private static final double H = 0x1.333332p-1, REAL = 0x1.333331ffffffcp-1;
    private static final Vec3d CENTER = new Vec3d(40.5, 88.89999997615814, 1.8999999761581416);

    @Test void actualProfileObtainsExactWholeDomainCertificate() {
        var work = new StoredDyadic.Work();
        var proof = CardinalJointRnDomain.prove(CENTER, H, REAL, work);
        assertSame(work.identity(), proof.workIdentity());
        assertEquals(17, proof.enumeratedValues());
        assertEquals(1 + 0x1p-48, proof.basisBound());
        System.out.println("joint RN exact rational + binade certificate Work=" + work.units());
    }

    @Test void scalarAndCoordinateChangesAreExplicitlyRejected() {
        var work = new StoredDyadic.Work();
        assertThrows(CardinalRealizedArcRecipe.DomainException.class,
                () -> CardinalJointRnDomain.prove(CENTER, H, Math.nextUp(REAL), work));
        assertThrows(CardinalRealizedArcRecipe.DomainException.class,
                () -> CardinalJointRnDomain.prove(CENTER, Math.nextUp(H), REAL, work));
        assertThrows(CardinalRealizedArcRecipe.DomainException.class,
                () -> CardinalJointRnDomain.prove(new Vec3d(0, CENTER.y(), CENTER.z()), H, REAL, work));
    }

    @Test void basisDomainBoundariesAndSignedComponentsRespectOriginalLimits() {
        var work = new StoredDyadic.Work();
        for (double v : new double[] {.5, 1, Math.nextUp(1.0), CardinalJointRnDomain.BASIS_BOUND})
            for (double sign : new double[] {-1, 1})
                for (double c : new double[] {CENTER.x(), CENTER.y(), CENTER.z()})
                    assertTrue(CardinalJointRnDomain.componentFits(c, sign * v, H, REAL, work));
        assertFalse(CardinalJointRnDomain.componentFits(CENTER.z(),
                Math.nextUp(CardinalJointRnDomain.BASIS_BOUND), H, REAL, work));
    }

    @Test void evenGridTranslationAndCrossBinadeMidpointsRespectTheAuditedLemma() {
        var work = new StoredDyadic.Work();
        var g = StoredDyadic.of(0x1p-53, work);
        var delta = g.scalePow2(2, work);
        var half = StoredDyadic.of(.5, work);
        var maximum = delta.add(g.scalePow2(-1, work), work);
        for (int quarterSteps = -24; quarterSteps <= 24; quarterSteps++) {
            var offset = StoredDyadic.of(quarterSteps, work).multiply(g.scalePow2(-2, work), work);
            var x = half.add(offset, work);
            double nominal = x.roundNearest(work);
            double realized = x.subtract(delta, work).roundNearest(work);
            var difference = StoredDyadic.of(nominal, work).subtract(StoredDyadic.of(realized, work), work);
            assertTrue(difference.compare(maximum, work) <= 0);
            if (nominal < .5) assertTrue(difference.compare(delta, work) <= 0);
        }
    }

    @Test void oddIntegerTranslationIsNotAValidTieLemma() {
        // Quantization in units of g: round(.5)=0 but round(1.5)=2.
        assertEquals(0, Math.rint(.5));
        assertEquals(2, Math.rint(1.5));
        assertTrue(Math.rint(1.5) - Math.rint(.5) > 1);
    }

    @Test void rationalProofChargesOriginalBudgetAndPreservesExhaustion() {
        var work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        assertThrows(NumericWorkExhaustedException.class,
                () -> CardinalJointRnDomain.prove(CENTER, H, REAL, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }
}
