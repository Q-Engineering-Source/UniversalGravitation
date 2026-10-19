package wi.unigrav.core.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleArcPlanePolynomialTest {
    @Test
    void initialEqualityAndOutwardCubicAreCertifiedContinuously() {
        var work = new StoredDyadic.Work();
        var polynomial = polynomial(work, 0, 0, 0, 1);
        assertTrue(polynomial.certifiesNonnegative(0, 1, work));
        assertTrue(polynomial.certifiesNonnegative(0, 0.125, work));
        assertTrue(polynomial.certifiesNonnegative(0.875, 1, work));
    }

    @Test
    void positiveEndpointsDoNotConcealInteriorPenetration() {
        var work = new StoredDyadic.Work();
        // p(0)=p(1)=1/8, p(1/2)=-1/8.
        var polynomial = polynomial(work, 0.125, -1, 1, 0);
        assertFalse(polynomial.certifiesNonnegative(0, 1, work));
        assertFalse(polynomial.certifiesNonnegative(0.375, 0.625, work));
        assertTrue(polynomial.certifiesNonnegative(0, 0.0625, work));
    }

    @Test
    void inconclusiveWholeIntervalCanBeProvedBySubdivision() {
        var work = new StoredDyadic.Work();
        // (t-1/2)^2 is nonnegative but its degree-3 Bernstein hull crosses zero.
        var polynomial = polynomial(work, 0.25, -1, 1, 0);
        assertFalse(polynomial.certifiesNonnegative(0, 1, work));
        assertTrue(polynomial.certifiesNonnegative(0, 0.5, work));
        assertTrue(polynomial.certifiesNonnegative(0.5, 1, work));
    }

    @Test
    void degenerateIntervalEvaluatesTheActualPolynomial() {
        var work = new StoredDyadic.Work();
        var polynomial = polynomial(work, -0.125, 0, 0, 1);
        assertFalse(polynomial.certifiesNonnegative(0.25, 0.25, work));
        assertTrue(polynomial.certifiesNonnegative(0.5, 0.5, work));
        assertTrue(polynomial.certifiesNonnegative(1, 1, work));
    }

    @Test
    void attemptsUseTheSameWorkAndExhaustionIsNotAFalseCertificate() {
        var work = new StoredDyadic.Work();
        var polynomial = polynomial(work, 0, 1, 0, 0);
        long before = work.units();
        assertTrue(polynomial.certifiesNonnegative(0, 1, work));
        assertTrue(work.units() > before);
        long first = work.units();
        assertTrue(polynomial.certifiesNonnegative(0.25, 0.75, work));
        assertTrue(work.units() > first);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        assertThrows(NumericWorkExhaustedException.class,
                () -> polynomial.certifiesNonnegative(0, 1, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test
    void invalidIntervalsAreChargedAndRejected() {
        var work = new StoredDyadic.Work();
        var polynomial = polynomial(work, 1, 0, 0, 0);
        for (double[] interval : new double[][] {
                {-0.125, 1}, {0.75, 0.25}, {0, 1.125}, {Double.NaN, 1}}) {
            long before = work.units();
            assertThrows(IllegalArgumentException.class,
                    () -> polynomial.certifiesNonnegative(interval[0], interval[1], work));
            assertTrue(work.units() > before);
        }
    }

    private static CapsuleArcPlanePolynomial polynomial(StoredDyadic.Work work,
            double constant, double linear, double quadratic, double cubic) {
        return new CapsuleArcPlanePolynomial(StoredDyadic.of(constant, work),
                StoredDyadic.of(linear, work), StoredDyadic.of(quadratic, work),
                StoredDyadic.of(cubic, work));
    }
}
