package wi.unigrav.core.player;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact bounded dyadic arithmetic for stored-capsule representation proofs.
 * This is UG numeric work, not CHB narrow-phase work or movement authority.
 */
final class StoredDyadic {
    static final int MAX_BITS = 4096;
    static final int MAX_EXPONENT = 4096;
    static final long MAX_WORK_UNITS = 131_072;

    private static final BigInteger ONE = BigInteger.ONE;
    private static final long SIGN_MASK = Long.MIN_VALUE;
    private static final long FRACTION_MASK = (1L << 52) - 1;
    private static final int EXPONENT_BIAS = 1023;

    private final BigInteger significand;
    private final int exponent;

    private StoredDyadic(BigInteger significand, int exponent) {
        this.significand = significand;
        this.exponent = exponent;
    }

    /**
     * Monotonic caller-owned accounting. Movement records units without a cutoff;
     * the network receive window explicitly requests its independent ingress bound.
     * Successful proof objects keep only their invocation delta.
     */
    static final class Work {
        private final Object identity = new Object();
        private final long maximumUnits;
        private long units;
        private int peakBits;

        Work() { maximumUnits = -1; }

        private Work(long maximumUnits) {
            if (maximumUnits <= 0) throw new IllegalArgumentException("numeric ingress bound must be positive");
            this.maximumUnits = maximumUnits;
        }

        static Work boundedIngress(long maximumUnits) { return new Work(maximumUnits); }

        Object identity() { return identity; }

        long units() {
            return units;
        }

        int peakBits() {
            return peakBits;
        }

        void charge(long amount, int... bitWidths) {
            if (amount < 0) {
                throw new IllegalArgumentException("stored-capsule dyadic work cannot be negative");
            }
            if (maximumUnits >= 0 && amount > maximumUnits - units)
                throw new NumericWorkExhaustedException(units, amount, maximumUnits);
            units = Math.addExact(units, amount);
            for (int bits : bitWidths) {
                if (bits < 0 || bits > MAX_BITS) {
                    throw new IllegalArgumentException("stored-capsule dyadic bit-width limit exceeded");
                }
                peakBits = Math.max(peakBits, bits);
            }
        }
    }

    static StoredDyadic of(double value, Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("dyadic input must be finite");
        }
        long bits = Double.doubleToRawLongBits(value);
        boolean negative = (bits & SIGN_MASK) != 0;
        int exponentBits = (int) ((bits >>> 52) & 0x7ff);
        long fraction = bits & FRACTION_MASK;
        if (exponentBits == 0 && fraction == 0) return zero();

        long mantissa;
        int binaryExponent;
        if (exponentBits == 0) {
            mantissa = fraction;
            binaryExponent = -1074;
        } else {
            mantissa = fraction | (1L << 52);
            binaryExponent = exponentBits - EXPONENT_BIAS - 52;
        }
        BigInteger integer = BigInteger.valueOf(mantissa);
        if (negative) integer = integer.negate();
        return normalized(integer, binaryExponent, work);
    }

    static StoredDyadic zero() {
        return new StoredDyadic(BigInteger.ZERO, 0);
    }

    static StoredDyadic one(Work work) {
        return of(1, work);
    }

    static StoredDyadic lambdaHalf(Work work) {
        return of(Double.MIN_VALUE, work).scalePow2(-1, work);
    }

    StoredDyadic add(StoredDyadic other, Work work) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (significand.signum() == 0) return other;
        if (other.significand.signum() == 0) return this;
        int commonExponent = Math.min(exponent, other.exponent);
        BigInteger left = shift(significand, exponent - commonExponent, work);
        BigInteger right = shift(other.significand, other.exponent - commonExponent, work);
        int resultBits = Math.max(left.abs().bitLength(), right.abs().bitLength()) + 1;
        work.charge(words(resultBits), resultBits);
        return normalized(left.add(right), commonExponent, work);
    }

    StoredDyadic subtract(StoredDyadic other, Work work) {
        Objects.requireNonNull(other, "other");
        return add(other.negate(work), work);
    }

    StoredDyadic multiply(StoredDyadic other, Work work) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (significand.signum() == 0 || other.significand.signum() == 0) return zero();
        long resultBitsLong = (long) significand.abs().bitLength() + other.significand.abs().bitLength();
        if (resultBitsLong > MAX_BITS) {
            throw new IllegalArgumentException("stored-capsule dyadic bit-width limit exceeded");
        }
        int resultBits = (int) resultBitsLong;
        long units = (long) words(significand.abs().bitLength()) * words(other.significand.abs().bitLength());
        work.charge(Math.max(1, units), resultBits);
        int resultExponent = checkedExponent((long) exponent + other.exponent);
        return normalized(significand.multiply(other.significand), resultExponent, work);
    }

    StoredDyadic scalePow2(int power, Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (significand.signum() == 0) return this;
        int resultExponent = checkedExponent((long) exponent + power);
        work.charge(1, significand.abs().bitLength());
        return new StoredDyadic(significand, resultExponent);
    }

    StoredDyadic negate(Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (significand.signum() == 0) return this;
        work.charge(words(significand.abs().bitLength()), significand.abs().bitLength());
        return new StoredDyadic(significand.negate(), exponent);
    }

    StoredDyadic abs(Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        return significand.signum() < 0 ? negate(work) : this;
    }

    int signum() {
        return significand.signum();
    }

    double roundNearest(Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (significand.signum() == 0) return 0.0;
        BigInteger magnitude = significand.abs();
        int topExponent = checkedExponent((long) exponent + magnitude.bitLength() - 1);
        if (topExponent > 1023) {
            throw new IllegalArgumentException("dyadic value is outside finite binary64");
        }
        int quantum = topExponent >= -1022 ? topExponent - 52 : -1074;
        int shift = exponent - quantum;
        BigInteger rounded;
        if (shift >= 0) {
            int bits = magnitude.bitLength() + shift;
            work.charge(words(bits), bits);
            rounded = magnitude.shiftLeft(shift);
        } else {
            rounded = roundRightShift(magnitude, -shift, work);
        }
        if (rounded.signum() == 0) return signedZero();
        if (topExponent >= -1022 && rounded.bitLength() > 53) {
            work.charge(words(rounded.bitLength()), rounded.bitLength());
            rounded = rounded.shiftRight(1);
            quantum++;
        }
        if (quantum == -1074 && rounded.bitLength() > 53) {
            throw new IllegalStateException("subnormal rounding exceeded binary64 significand");
        }
        double result = Math.scalb(rounded.doubleValue(), quantum);
        if (significand.signum() < 0) result = -result;
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("dyadic value is outside finite binary64");
        }
        return result == 0 ? signedZero() : result;
    }

    double roundDown(Work work) {
        double nearest = roundNearest(work);
        StoredDyadic candidate = of(nearest, work);
        if (candidate.compare(this, work) > 0) {
            nearest = Math.nextDown(nearest);
            if (!Double.isFinite(nearest)) throw new IllegalArgumentException("downward dyadic rounding overflowed");
        }
        return nearest;
    }

    double roundUp(Work work) {
        double nearest = roundNearest(work);
        StoredDyadic candidate = of(nearest, work);
        if (candidate.compare(this, work) < 0) {
            nearest = Math.nextUp(nearest);
            if (!Double.isFinite(nearest)) throw new IllegalArgumentException("upward dyadic rounding overflowed");
        }
        return nearest;
    }

    boolean isExactlyRepresentable(Work work) {
        double rounded = roundNearest(work);
        return of(rounded, work).compare(this, work) == 0;
    }

    static StoredDyadic gap64(double value, Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("gap input must be finite");
        }
        return of(Math.ulp(Math.abs(value)), work);
    }

    int compare(StoredDyadic other, Work work) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(work, "work");
        work.charge(1, significand.abs().bitLength(), other.significand.abs().bitLength());
        int leftSign = significand.signum();
        int rightSign = other.significand.signum();
        if (leftSign != rightSign) return Integer.compare(leftSign, rightSign);
        if (leftSign == 0) return 0;
        BigInteger leftMagnitude = significand.abs();
        BigInteger rightMagnitude = other.significand.abs();
        long leftTop = (long) exponent + leftMagnitude.bitLength();
        long rightTop = (long) other.exponent + rightMagnitude.bitLength();
        int magnitudeComparison;
        if (leftTop != rightTop) {
            magnitudeComparison = Long.compare(leftTop, rightTop);
        } else {
            int commonExponent = Math.min(exponent, other.exponent);
            BigInteger left = shift(leftMagnitude, exponent - commonExponent, work);
            BigInteger right = shift(rightMagnitude, other.exponent - commonExponent, work);
            int bits = Math.max(left.bitLength(), right.bitLength());
            work.charge(words(bits), bits);
            magnitudeComparison = left.compareTo(right);
        }
        return leftSign < 0 ? -magnitudeComparison : magnitudeComparison;
    }

    private BigInteger roundRightShift(BigInteger value, int shift, Work work) {
        if (shift <= 0) return value;
        if (shift > value.bitLength() + 1) return BigInteger.ZERO;
        int outputBits = value.bitLength();
        work.charge(words(outputBits) * 3L + words(shift), outputBits, shift);
        BigInteger quotient = value.shiftRight(shift);
        BigInteger remainder = value.subtract(quotient.shiftLeft(shift));
        BigInteger half = ONE.shiftLeft(shift - 1);
        int comparison = remainder.compareTo(half);
        if (comparison > 0 || (comparison == 0 && quotient.testBit(0))) {
            work.charge(words(quotient.bitLength() + 1), quotient.bitLength() + 1);
            quotient = quotient.add(ONE);
        }
        return quotient;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredDyadic that
                && exponent == that.exponent && significand.equals(that.significand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(significand, exponent);
    }
    private double signedZero() {
        return significand.signum() < 0 ? -0.0 : 0.0;
    }

    private static StoredDyadic normalized(BigInteger value, int exponent, Work work) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(work, "work");
        int bits = value.abs().bitLength();
        if (bits > MAX_BITS) {
            throw new IllegalArgumentException("stored-capsule dyadic bit-width limit exceeded");
        }
        int checkedExponent = checkedExponent(exponent);
        if (value.signum() == 0) return zero();
        work.charge(words(bits), bits);
        int trailingZeros = value.abs().getLowestSetBit();
        if (trailingZeros == 0) return new StoredDyadic(value, checkedExponent);
        int normalizedExponent = checkedExponent((long) checkedExponent + trailingZeros);
        int normalizedBits = bits - trailingZeros;
        work.charge(words(normalizedBits), normalizedBits);
        return new StoredDyadic(value.shiftRight(trailingZeros), normalizedExponent);
    }

    /** Reduces only common odd significand factors; powers of two remain in the exact exponents. */
    static StoredDyadic[] reduceRatio(StoredDyadic numerator, StoredDyadic denominator, Work work) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        Objects.requireNonNull(work, "work");
        if (denominator.significand.signum() <= 0)
            throw new IllegalArgumentException("reduced ratio denominator must be positive");
        if (numerator.significand.signum() == 0) return new StoredDyadic[] { zero(), one(work) };
        int numeratorBits = numerator.significand.abs().bitLength();
        int denominatorBits = denominator.significand.bitLength();
        work.charge((long) words(numeratorBits) * words(denominatorBits), numeratorBits, denominatorBits);
        BigInteger common = numerator.significand.abs().gcd(denominator.significand);
        if (common.equals(ONE)) return new StoredDyadic[] { numerator, denominator };
        BigInteger reducedNumerator = numerator.significand.divide(common);
        BigInteger reducedDenominator = denominator.significand.divide(common);
        work.charge(words(numeratorBits) + (long) words(denominatorBits),
                reducedNumerator.abs().bitLength(), reducedDenominator.bitLength());
        return new StoredDyadic[] {
                normalized(reducedNumerator, numerator.exponent, work),
                normalized(reducedDenominator, denominator.exponent, work)
        };
    }

    private static BigInteger shift(BigInteger value, int amount, Work work) {
        if (amount < 0) throw new IllegalArgumentException("negative dyadic alignment");
        if (amount == 0 || value.signum() == 0) return value;
        long bitsLong = (long) value.abs().bitLength() + amount;
        if (bitsLong > MAX_BITS) {
            throw new IllegalArgumentException("stored-capsule dyadic bit-width limit exceeded");
        }
        int bits = (int) bitsLong;
        work.charge(words(bits), bits);
        return value.shiftLeft(amount);
    }

    private static int checkedExponent(long exponent) {
        if (exponent < -MAX_EXPONENT || exponent > MAX_EXPONENT) {
            throw new IllegalArgumentException("stored-capsule dyadic exponent limit exceeded");
        }
        return (int) exponent;
    }

    private static int words(int bits) {
        return Math.max(1, (bits + 63) / 64);
    }
}
