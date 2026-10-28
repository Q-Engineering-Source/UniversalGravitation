package wi.unigrav.core.player;

import java.util.Objects;

/** Shared exact numeric work for boot geometry; no owner, query or installation authority. */
public final class BootQueryArithmetic {
    private static final long MAX_FINITE_BITS = 0x7fefffffffffffffL;
    private static final long ONE_BITS = Double.doubleToRawLongBits(1.0);
    private final StoredDyadic.Work work;
    private final Runnable current;

    private BootQueryArithmetic(StoredDyadic.Work work) { this(work, () -> { }); }
    private BootQueryArithmetic(StoredDyadic.Work work, Runnable current) {
        this.work = Objects.requireNonNull(work); this.current = Objects.requireNonNull(current);
    }
    static BootQueryArithmetic fromSameWork(StoredDyadic.Work work) { return new BootQueryArithmetic(work); }
    static BootQueryArithmetic fromSameWork(StoredDyadic.Work work, Runnable current) {
        return new BootQueryArithmetic(work, current);
    }
    public record Usage(long units, int peakBits) { }
    // Three binary64 sign multiplications and three finite component checks in the face-direction mapping.
    void chargeOppositeFaceDirection() { current.run(); work.charge(6); }
    public Usage usage() { current.run(); return new Usage(work.units(), work.peakBits()); }
    public boolean sameCapability(BootQueryArithmetic other) { return this == other; }

    public static final class Number {
        private final BootQueryArithmetic capability;
        private final StoredDyadic value;
        private Number(BootQueryArithmetic capability, StoredDyadic value) {
            this.capability = capability; this.value = value;
        }
    }
    public static final class Fraction {
        private final BootQueryArithmetic capability;
        private final Number numerator;
        private final Number denominator;
        private Fraction(BootQueryArithmetic capability, Number numerator, Number denominator) {
            this.capability = capability; this.numerator = numerator; this.denominator = denominator;
        }
        public Number numerator() { return numerator; }
        public Number denominator() { return denominator; }
    }
    /** Finite enclosing square-root endpoints and round-to-nearest, ties-to-even value. */
    public record SquareRoot(double lower, double upper, double nearest) { }
    /** Each component is RN of the exact input divided by its exact Euclidean length; not a normal certificate. */
    public record Direction(double x, double y, double z) { }

    private StoredDyadic require(Number number) {
        current.run();
        Objects.requireNonNull(number);
        if (number.capability != this) throw new IllegalArgumentException("foreign boot arithmetic operand");
        return number.value;
    }
    private Fraction require(Fraction fraction) {
        current.run();
        Objects.requireNonNull(fraction);
        if (fraction.capability != this) throw new IllegalArgumentException("foreign boot arithmetic fraction");
        return fraction;
    }
    private Number wrap(StoredDyadic value) { return new Number(this, value); }
    public Number exact(double value) { current.run(); return wrap(StoredDyadic.of(value, work)); }
    public Number add(Number a, Number b) { return wrap(require(a).add(require(b), work)); }
    public Number subtract(Number a, Number b) { return wrap(require(a).subtract(require(b), work)); }
    public Number multiply(Number a, Number b) { return wrap(require(a).multiply(require(b), work)); }
    public Number negate(Number a) { return wrap(require(a).negate(work)); }
    public Number abs(Number a) { return wrap(require(a).abs(work)); }
    public Number scalePow2(Number a, int exponent) { return wrap(require(a).scalePow2(exponent, work)); }
    public int sign(Number a) { var value = require(a); work.charge(1); return value.signum(); }
    public int compare(Number a, Number b) { return require(a).compare(require(b), work); }
    public double roundDown(Number a) { return require(a).roundDown(work); }
    public double roundUp(Number a) { return require(a).roundUp(work); }
    public double roundNearest(Number a) { return require(a).roundNearest(work); }
    /**
     * RN of the exact fraction, ties to even, using this capability's existing work.
     * Exact zero is canonical +0; a negative nonzero fraction rounding to zero keeps -0.
     * Values beyond the finite binary64 interval fail rather than saturating or overflowing.
     */
    public double roundNearest(Fraction fraction) {
        require(fraction);
        int sign = sign(fraction.numerator);
        if (sign == 0) { current.run(); return 0.0; }
        Number numerator = abs(fraction.numerator);
        Number denominator = fraction.denominator; // fraction() guarantees a positive denominator.
        if (compare(numerator, multiply(exact(Double.MAX_VALUE), denominator)) > 0)
            throw new IllegalArgumentException("boot fraction is outside finite binary64");
        Double direct = require(fraction.numerator).roundRatioDirect(require(denominator), work);
        if (direct != null) { current.run(); return direct; }
        // Positive finite raw encodings are ordered. Each probe compares c*d with |n| exactly;
        // the existing floor search takes at most 63 iterations and charges this same Work.
        long lowerBits = floor(MAX_FINITE_BITS,
                candidate -> compare(multiply(candidate, denominator), numerator));
        double lower = Double.longBitsToDouble(lowerBits);
        Number lowerValue = exact(lower);
        double magnitude;
        if (compare(multiply(lowerValue, denominator), numerator) == 0) magnitude = lower;
        else {
            work.charge(3);
            double upper = Double.longBitsToDouble(lowerBits + 1);
            Number upperValue = exact(upper);
            if (compare(multiply(upperValue, denominator), numerator) <= 0)
                throw new IllegalStateException("boot fraction enclosure invariant failed");
            // Compare n/d to (lower+upper)/2 without an inexact division or rounded midpoint.
            int side = compare(scalePow2(numerator, 1), multiply(add(lowerValue, upperValue), denominator));
            magnitude = nearest(lower, upper, lowerBits, side);
        }
        work.charge(1);
        current.run();
        return sign < 0 ? -magnitude : magnitude;
    }

    public Fraction fraction(Number numerator, Number denominator) {
        require(numerator); require(denominator);
        int sign = sign(denominator);
        if (sign == 0) throw new IllegalArgumentException("zero boot path denominator");
        if (sign < 0) { numerator = negate(numerator); denominator = negate(denominator); }
        return new Fraction(this, numerator, denominator);
    }
    /**
     * Rebinds an immutable exact fraction to another capability over this same Work only.
     * This is an identity-preserving owner handoff, not numeric conversion; foreign Work remains rejected.
     */
    Fraction rebindSameWork(Fraction fraction) {
        current.run();
        Objects.requireNonNull(fraction, "fraction");
        BootQueryArithmetic source = fraction.capability;
        if (source.work != work || fraction.numerator.capability != source
                || fraction.denominator.capability != source)
            throw new IllegalArgumentException("foreign boot arithmetic work cannot be rebound");
        return new Fraction(this, wrap(fraction.numerator.value), wrap(fraction.denominator.value));
    }
    /** Reduced exact rational for bounded local solvers that repeatedly form ratios. */
    Fraction fractionReduced(Number numerator, Number denominator) {
        require(numerator); require(denominator);
        int sign = sign(denominator);
        if (sign == 0) throw new IllegalArgumentException("zero boot path denominator");
        if (sign < 0) { numerator = negate(numerator); denominator = negate(denominator); }
        StoredDyadic[] reduced = StoredDyadic.reduceRatio(require(numerator), require(denominator), work);
        current.run();
        return new Fraction(this, wrap(reduced[0]), wrap(reduced[1]));
    }
    public int compareFractions(Fraction a, Fraction b) {
        require(a); require(b);
        return compare(multiply(a.numerator, b.denominator), multiply(b.numerator, a.denominator));
    }

    public SquareRoot sqrt(Number square) {
        require(square);
        int sign = sign(square);
        if (sign < 0) throw new IllegalArgumentException("negative boot squared distance");
        if (sign == 0) return new SquareRoot(0, 0, 0);
        Number maximum = exact(Double.MAX_VALUE);
        if (compare(square, multiply(maximum, maximum)) > 0)
            throw new IllegalArgumentException("boot square root has no finite upper bound");
        long floorBits = floor(MAX_FINITE_BITS, candidate -> compare(multiply(candidate, candidate), square));
        double lower = Double.longBitsToDouble(floorBits);
        Number lowerValue = exact(lower);
        if (compare(multiply(lowerValue, lowerValue), square) == 0) return new SquareRoot(lower, lower, lower);
        // The finite maximum test ensures a non-exact floor cannot be MAX_FINITE_BITS.
        work.charge(3);
        double upper = Double.longBitsToDouble(floorBits + 1);
        Number upperValue = exact(upper);
        if (compare(multiply(upperValue, upperValue), square) <= 0)
            throw new IllegalStateException("boot square-root enclosure invariant failed");
        Number midpoint = scalePow2(add(lowerValue, upperValue), -1);
        int side = compare(square, multiply(midpoint, midpoint));
        return new SquareRoot(lower, upper, nearest(lower, upper, floorBits, side));
    }

    public Direction normalize(Number x, Number y, Number z) {
        require(x); require(y); require(z);
        Number xSquare = multiply(x, x), ySquare = multiply(y, y), zSquare = multiply(z, z);
        Number total = add(add(xSquare, ySquare), zSquare);
        if (sign(total) == 0) throw new IllegalArgumentException("zero boot pull direction");
        // Both comparison sides receive the same positive exact power of two. Binary64 squares have
        // normalized exponents at least -2148, and their positive sum has top exponent at most 2049.
        // Scaling by 2^1074 bounds total exponents by [-1074,3123]. Even a half-subnormal
        // midpoint squared (-2150) gives product exponents in [-3224,3123]. Bit/work caps remain.
        Number scaledTotal = scalePow2(total, 1074);
        return new Direction(component(x, scalePow2(xSquare, 1074), scaledTotal),
                component(y, scalePow2(ySquare, 1074), scaledTotal),
                component(z, scalePow2(zSquare, 1074), scaledTotal));
    }
    private double component(Number value, Number square, Number total) {
        int sign = sign(value);
        if (sign == 0) return 0;
        if (compare(square, total) == 0) return sign < 0 ? -1 : 1;
        long floorBits = floor(ONE_BITS, candidate -> compare(multiply(multiply(candidate, candidate), total), square));
        double lower = Double.longBitsToDouble(floorBits);
        Number lowerValue = exact(lower);
        double magnitude;
        if (compare(multiply(multiply(lowerValue, lowerValue), total), square) == 0) magnitude = lower;
        else {
            work.charge(3);
            double upper = Double.longBitsToDouble(floorBits + 1);
            Number upperValue = exact(upper);
            if (compare(multiply(multiply(upperValue, upperValue), total), square) <= 0)
                throw new IllegalStateException("boot normalization enclosure invariant failed");
            Number midpoint = scalePow2(add(lowerValue, upperValue), -1);
            int side = compare(square, multiply(multiply(midpoint, midpoint), total));
            magnitude = nearest(lower, upper, floorBits, side);
        }
        work.charge(1);
        return sign < 0 ? -magnitude : magnitude;
    }
    @FunctionalInterface private interface Probe { int compare(Number candidate); }
    private long floor(long maximumBits, Probe probe) {
        long low = 0, high = maximumBits;
        while (low < high) {
            // Fixed raw-bit/control primitives; variable-size arithmetic is charged in each actual probe.
            work.charge(6);
            long middle = low + (high - low) / 2 + 1;
            int comparison = probe.compare(exact(Double.longBitsToDouble(middle)));
            if (comparison <= 0) low = middle;
            else high = middle - 1;
        }
        return low;
    }
    private double nearest(double lower, double upper, long lowerBits, int squareSide) {
        work.charge(2);
        return squareSide < 0 || squareSide == 0 && (lowerBits & 1) == 0 ? lower : upper;
    }
}



