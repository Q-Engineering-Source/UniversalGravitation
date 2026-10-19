package wi.unigrav.core.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootQueryArithmeticTest {
    private BootQueryArithmetic capability() { return BootQueryArithmetic.fromSameWork(new StoredDyadic.Work()); }
    private void cost(String label, BootQueryArithmetic arithmetic) {
        System.out.println("BOOT_NUMERIC_COST " + label + " " + arithmetic.usage());
        assertTrue(arithmetic.usage().units() > 0);
        assertTrue(arithmetic.usage().peakBits() <= StoredDyadic.MAX_BITS);
    }
    @Test void exactDyadicAndRationalOperationsUseExistingLedger() {
        var work = new StoredDyadic.Work(); work.charge(17);
        var a = BootQueryArithmetic.fromSameWork(work);
        var three = a.exact(3); var four = a.exact(4);
        assertEquals(7, a.roundNearest(a.add(three,four)));
        assertEquals(-1, a.roundNearest(a.subtract(three,four)));
        assertEquals(12, a.roundNearest(a.multiply(three,four)));
        assertEquals(3, a.roundNearest(a.abs(a.negate(three))));
        var fraction = a.fraction(three,a.negate(four));
        assertEquals(-3,a.roundNearest(fraction.numerator()));
        assertEquals(4,a.roundNearest(fraction.denominator()));
        assertEquals(0,a.compareFractions(fraction,a.fraction(a.exact(-6),a.exact(8))));
        assertTrue(a.compareFractions(fraction,a.fraction(a.exact(-1),a.exact(2)))<0);
        var tiny = a.scalePow2(a.exact(Double.MIN_VALUE),-1);
        assertEquals(0,a.roundDown(tiny)); assertEquals(Double.MIN_VALUE,a.roundUp(tiny));
        assertEquals(0,a.roundNearest(tiny));
        assertThrows(IllegalArgumentException.class,()->a.fraction(three,a.exact(0)));
        assertTrue(work.units()>17); assertEquals(work.units(),a.usage().units()); cost("exact-fraction",a);
    }
    @Test void foreignOperandsAndFractionsCannotCrossCapabilityEvenOnSameWork() {
        var work=new StoredDyadic.Work();
        var a=BootQueryArithmetic.fromSameWork(work); var b=BootQueryArithmetic.fromSameWork(work);
        var own=a.exact(1); var foreign=b.exact(1); long before=work.units();
        assertThrows(IllegalArgumentException.class,()->a.add(own,foreign));
        assertEquals(before,work.units()); assertFalse(a.sameCapability(b)); assertTrue(a.sameCapability(a));
        var fraction=b.fraction(foreign,foreign); var local=a.fraction(own,own);
        before=work.units();
        assertThrows(IllegalArgumentException.class,()->a.compareFractions(local,fraction));
        assertEquals(before,work.units());
        var other=capability().exact(1); assertThrows(IllegalArgumentException.class,()->a.roundDown(other));
        cost("foreign",a);
    }
    @Test void sqrtExactZeroBinadeAndSubnormalAreCertifiedByActualSquares() {
        for(double value:new double[]{0,Double.MIN_VALUE,Double.MIN_NORMAL,Math.nextDown(1.0),1,Math.nextUp(1.0),2,Double.MAX_VALUE}) {
            var a=capability(); var v=a.exact(value); var square=a.multiply(v,v); var result=a.sqrt(square);
            assertEquals(value,result.lower()); assertEquals(value,result.upper()); assertEquals(value,result.nearest());
            cost("sqrt-exact-"+Double.toHexString(value),a);
        }
    }
    @Test void sqrtBracketAndMidpointTiesUseValueMidpointNotSquaredDistances() {
        for(double lower:new double[]{0,Double.MIN_VALUE,Math.nextDown(1.0),1,Math.nextUp(1.0),2}) {
            var a=capability(); double upper=Math.nextUp(lower);
            var midpoint=a.scalePow2(a.add(a.exact(lower),a.exact(upper)),-1);
            var square=a.multiply(midpoint,midpoint); var root=a.sqrt(square);
            assertEquals(lower,root.lower()); assertEquals(upper,root.upper());
            assertEquals((Double.doubleToRawLongBits(lower)&1)==0?lower:upper,root.nearest());
            assertTrue(a.compare(a.multiply(a.exact(root.lower()),a.exact(root.lower())),square)<0);
            assertTrue(a.compare(a.multiply(a.exact(root.upper()),a.exact(root.upper())),square)>0);
            cost("sqrt-tie-"+Double.toHexString(lower),a);
        }
        var a=capability(); var root=a.sqrt(a.exact(2));
        assertEquals(Math.sqrt(2),root.nearest()); // Independent expected value; production never trusts Math.sqrt.
        assertTrue(root.lower()<root.upper()); cost("sqrt-two",a);
    }
    @Test void sqrtNegativeAndFiniteOverflowFailExplicitly() {
        var a=capability(); assertThrows(IllegalArgumentException.class,()->a.sqrt(a.exact(-1)));
        var max=a.exact(Double.MAX_VALUE); var tooLarge=a.add(a.multiply(max,max),a.exact(1));
        assertThrows(IllegalArgumentException.class,()->a.sqrt(tooLarge)); cost("sqrt-overflow",a);
    }
    @Test void normalizationKnownVectorsSignsAndEqualComponentsUseOriginalDelta() {
        for(double[] vector:new double[][]{{3,4,0},{-3,4,-0.0},{1,1,1},{0,-Double.MIN_VALUE,0},{2,0,0}}) {
            var a=capability(); var direction=a.normalize(a.exact(vector[0]),a.exact(vector[1]),a.exact(vector[2]));
            if(Math.abs(vector[0])==3) {assertEquals(Math.copySign(.6,vector[0]),direction.x()); assertEquals(.8,direction.y()); assertEquals(0,direction.z());}
            else if(vector[0]==1) {proveThirdRootRounding(a,direction.x()); assertEquals(.5773502691896257,direction.x()); assertEquals(direction.x(),direction.y()); assertEquals(direction.x(),direction.z());}
            else if(vector[1]<0) assertEquals(-1,direction.y());
            else assertEquals(1,direction.x());
            assertTrue(Double.isFinite(direction.x())); assertTrue(Double.isFinite(direction.y())); assertTrue(Double.isFinite(direction.z()));
            cost("normalize-"+java.util.Arrays.toString(vector),a);
        }
    }
    @Test void normalizationSubnormalUnderflowAndZeroRejectAreNotFallbacks() {
        for(double main:new double[]{1,2,Math.nextDown(2.0)}) {
            var a=capability(); var direction=a.normalize(a.exact(-Double.MIN_VALUE),a.exact(main),a.exact(0));
            assertEquals(main==1?-Double.MIN_VALUE:main==2?-0.0:-Double.MIN_VALUE,direction.x());
            assertEquals(1,direction.y()); cost("normalize-subnormal-"+main,a);
        }
        var a=capability(); var zero=a.exact(0);
        assertThrows(IllegalArgumentException.class,()->a.normalize(zero,zero,zero)); cost("normalize-zero",a);
    }
    private void proveThirdRootRounding(BootQueryArithmetic a, double value) {
        var lowerMid = a.scalePow2(a.add(a.exact(Math.nextDown(value)), a.exact(value)), -1);
        var upperMid = a.scalePow2(a.add(a.exact(value), a.exact(Math.nextUp(value))), -1);
        var three = a.exact(3); var one = a.exact(1);
        assertTrue(a.compare(a.multiply(a.multiply(lowerMid, lowerMid), three), one) < 0);
        assertTrue(a.compare(a.multiply(a.multiply(upperMid, upperMid), three), one) > 0);
    }
    @Test void actualLargestBinadeAndFiniteVectorsNormalizeWithinUnchangedExponentProfile() {
        for(double magnitude : new double[]{Math.scalb(1.0,1023), Double.MAX_VALUE}) {
            for(int components : new int[]{1,2,3}) {
                var a=capability();
                var direction=a.normalize(a.exact(magnitude),a.exact(components>1?magnitude:0),a.exact(components>2?magnitude:0));
                if(components==1) assertEquals(1,direction.x());
                else if(components==2) {
                    assertEquals(.7071067811865476,direction.x()); assertEquals(direction.x(),direction.y());
                    var lowerMid=a.scalePow2(a.add(a.exact(Math.nextDown(direction.x())),a.exact(direction.x())),-1);
                    var upperMid=a.scalePow2(a.add(a.exact(direction.x()),a.exact(Math.nextUp(direction.x()))),-1);
                    assertTrue(a.compare(a.scalePow2(a.multiply(lowerMid,lowerMid),1),a.exact(1))<0);
                    assertTrue(a.compare(a.scalePow2(a.multiply(upperMid,upperMid),1),a.exact(1))>0);
                } else {assertEquals(.5773502691896257,direction.x()); proveThirdRootRounding(a,direction.x());}
                cost("normalize-max-"+Double.toHexString(magnitude)+"-"+components,a);
            }
        }
    }
    @Test void exhaustionAtActualOperationsPreservesPartialSpentOriginalException() {
        for(String phase:new String[]{"of","multiply","fraction","sqrt","normalize"}) {
            var work=new StoredDyadic.Work(); var a=BootQueryArithmetic.fromSameWork(work);
            var three=a.exact(3); var four=a.exact(4); var two=a.exact(2);
            var left=a.fraction(three,four); var right=a.fraction(two,three);
            long alreadySpent=work.units(); assertTrue(alreadySpent>0);
            work.charge(StoredDyadic.MAX_WORK_UNITS-work.units()-(phase.equals("of")?0:2));
            long before=work.units();
            var failure=assertThrows(NumericWorkExhaustedException.class,()->{
                switch(phase) {
                    case "of" -> a.exact(1);
                    case "multiply" -> a.multiply(three,four);
                    case "fraction" -> a.compareFractions(left,right);
                    case "sqrt" -> a.sqrt(two);
                    case "normalize" -> a.normalize(three,four,two);
                    default -> throw new AssertionError();
                }
            });
            assertEquals(work.units(),failure.used()); assertTrue(failure.used()>=before); assertTrue(failure.requested()>0);
            assertEquals(StoredDyadic.MAX_WORK_UNITS,failure.limit()); assertNull(failure.getCause());
            assertThrows(NumericWorkExhaustedException.class,()->a.exact(1));
            cost("exhaust-"+phase,a);
        }
    }
}


