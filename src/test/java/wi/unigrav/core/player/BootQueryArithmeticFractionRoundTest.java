package wi.unigrav.core.player;

import java.util.concurrent.atomic.AtomicBoolean;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootQueryArithmeticFractionRoundTest {
    private static BootQueryArithmetic capability() {
        return BootQueryArithmetic.fromSameWork(new StoredDyadic.Work());
    }
    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @Test void exactSignedFractionsAndCanonicalZeroUseTheSameWork() {
        var work = new StoredDyadic.Work();
        work.charge(17);
        var a = BootQueryArithmetic.fromSameWork(work);
        for (double value : new double[]{0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, .75, -.75, 1, -1}) {
            var fraction = a.fraction(a.multiply(a.exact(value), a.exact(4)), a.exact(4));
            bits(value == 0 ? 0.0 : value, a.roundNearest(fraction));
        }
        bits(-.75, a.roundNearest(a.fraction(a.exact(3), a.exact(-4))));
        assertTrue(work.units() > 17);
        assertEquals(work.units(), a.usage().units());
    }

    @Test void positiveAndNegativeMidpointsChooseTheEvenNeighbor() {
        for (double lower : new double[]{0, Double.MIN_VALUE, Math.nextDown(1.0), 1, Math.nextUp(1.0), 2}) {
            var a = capability();
            double upper = Math.nextUp(lower);
            var sum = a.add(a.exact(lower), a.exact(upper));
            var two = a.exact(2);
            double even = (Double.doubleToRawLongBits(lower) & 1) == 0 ? lower : upper;
            bits(even, a.roundNearest(a.fraction(sum, two)));
            bits(-even, a.roundNearest(a.fraction(a.negate(sum), two)));
        }
    }

    @Test void subnormalHalfUnderflowAndBothSidesAreExactFractions() {
        var a = capability();
        var tiny = a.exact(Double.MIN_VALUE);
        var two = a.exact(2);
        bits(0.0, a.roundNearest(a.fraction(tiny, two)));
        bits(-0.0, a.roundNearest(a.fraction(a.negate(tiny), two)));
        var belowHalf = a.fraction(tiny, a.exact(4));
        bits(0.0, a.roundNearest(belowHalf));
        bits(-0.0, a.roundNearest(a.fraction(a.negate(tiny), a.exact(4))));
        var threeTiny = a.multiply(a.exact(3), tiny);
        bits(Double.MIN_VALUE, a.roundNearest(a.fraction(threeTiny, a.exact(4))));
        bits(-Double.MIN_VALUE, a.roundNearest(a.fraction(a.negate(threeTiny), a.exact(4))));
        bits(2 * Double.MIN_VALUE, a.roundNearest(a.fraction(threeTiny, two)));
        bits(-2 * Double.MIN_VALUE, a.roundNearest(a.fraction(a.negate(threeTiny), two)));
    }

    @Test void nonDyadicThirdHasActualNearestEvenBracket() {
        var a = capability();
        var one = a.exact(1); var three = a.exact(3);
        var third = a.fraction(one, three);
        double rounded = a.roundNearest(third);
        bits(.3333333333333333, rounded);
        var lowMid = a.fraction(a.add(a.exact(Math.nextDown(rounded)), a.exact(rounded)), a.exact(2));
        var highMid = a.fraction(a.add(a.exact(rounded), a.exact(Math.nextUp(rounded))), a.exact(2));
        assertTrue(a.compareFractions(lowMid, third) < 0);
        assertTrue(a.compareFractions(third, highMid) < 0);
        bits(-.3333333333333333, a.roundNearest(a.fraction(a.negate(one), three)));
    }

    @Test void largestFiniteAndOutsideFiniteFailWithoutSaturation() {
        var a = capability();
        var maximum = a.exact(Double.MAX_VALUE); var two = a.exact(2);
        bits(Double.MAX_VALUE, a.roundNearest(a.fraction(a.multiply(maximum, two), two)));
        bits(-Double.MAX_VALUE, a.roundNearest(a.fraction(a.negate(maximum), a.exact(1))));
        var outside = a.add(maximum, a.exact(1));
        assertThrows(IllegalArgumentException.class, () -> a.roundNearest(a.fraction(outside, a.exact(1))));
        assertThrows(IllegalArgumentException.class, () -> a.roundNearest(a.fraction(a.negate(outside), a.exact(1))));
    }

    @Test void foreignCapabilityAndInvalidDenominatorCannotEnterRounding() {
        var work = new StoredDyadic.Work();
        var a = BootQueryArithmetic.fromSameWork(work); var b = BootQueryArithmetic.fromSameWork(work);
        var other = b.fraction(b.exact(1), b.exact(3));
        long spent = work.units();
        assertThrows(IllegalArgumentException.class, () -> a.roundNearest(other));
        assertEquals(spent, work.units(), "foreign rejection must not start a numeric probe");
        var one = a.exact(1); var zero = a.exact(0);
        assertThrows(IllegalArgumentException.class, () -> a.fraction(one, zero));
    }

    @Test void currentFailureRetainsItsOriginalCauseBeforeNumericWork() {
        var work = new StoredDyadic.Work(); var alive = new AtomicBoolean(true);
        var failure = new IllegalStateException("fraction owner capability is no longer current");
        var a = BootQueryArithmetic.fromSameWork(work, () -> { if (!alive.get()) throw failure; });
        var third = a.fraction(a.exact(1), a.exact(3));
        long spent = work.units(); alive.set(false);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> a.roundNearest(third)));
        assertEquals(spent, work.units());
    }

    @Test void exhaustionDuringActualProbePreservesPartialSpentWorkWithoutRefund() {
        var work = StoredDyadic.Work.boundedIngress(StoredDyadic.MAX_WORK_UNITS);
        var a = BootQueryArithmetic.fromSameWork(work);
        var third = a.fraction(a.exact(1), a.exact(3));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 5);
        long before = work.units();
        var failure = assertThrows(NumericWorkExhaustedException.class, () -> a.roundNearest(third));
        assertTrue(work.units() > before, "the actual ratio operation must spend before its rejection");
        assertEquals(work.units(), failure.used());
        assertEquals(StoredDyadic.MAX_WORK_UNITS, failure.limit());
        assertTrue(failure.requested() > 0);
        assertNull(failure.getCause());
        assertThrows(NumericWorkExhaustedException.class, () -> a.roundNearest(third));
        assertTrue(work.units() >= failure.used());
    }

    // Regression source only: user owns runtime acceptance; this test has not been run for C44.
    @Test void repeatedRotatedEyeMappingSharesOneUnchangedIngressWindow() {
        var work = StoredDyadic.Work.boundedIngress(StoredDyadic.MAX_WORK_UNITS);
        var arithmetic = BootQueryArithmetic.fromSameWork(work);
        var center = new Vec3d(31.037567794136528, 132.20407477274063, 105.91834824733665);
        var anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center,
                center.add(new Vec3d(0, .72, 0)), new Rotation3d(.15, .2, .3, .7));
        for (int tick = 0; tick < 20; tick++) {
            var attitude = new Rotation3d(.6, -.1, .2 + tick * .001, .7);
            var mapped = anchor.at(center, attitude, arithmetic);
            assertTrue(Double.isFinite(mapped.x()) && Double.isFinite(mapped.y()) && Double.isFinite(mapped.z()));
        }
        assertTrue(work.units() > 0);
        assertTrue(work.units() < StoredDyadic.MAX_WORK_UNITS);
        assertEquals(work.units(), arithmetic.usage().units());
    }
}
