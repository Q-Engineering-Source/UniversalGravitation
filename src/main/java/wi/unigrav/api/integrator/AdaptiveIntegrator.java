package wi.unigrav.api.integrator;

import wi.unigrav.api.math.Vec3;

/**
 * Dormand-Prince 5(4) embedded explicit Runge-Kutta integrator with
 * adaptive step-size control, for the first-order system obtained by
 * splitting q&#776; = a(q, t) into q&#775; = v, v&#775; = a(q, t).
 *
 * <p>This is intended for non-conservative segments (e.g. thrust, drag)
 * where a symplectic method has no advantage and error control matters
 * more than geometric structure.
 *
 * <p>Coefficients: Dormand &amp; Prince RK5(4)7FC, [DP86] Dormand and
 * Prince, "A family of embedded Runge-Kutta formulae", J. Comput. Appl.
 * Math. 6(1), 1980, and Dormand &amp; Prince 1986. Transcribed from
 * Principia (MIT), integrators/methods.hpp
 * ({@code DormandPrince1986RK547FC}). Note that the 7FC variant's 5th-order
 * weights differ from the more widely known DOPRI5 weights; the last stage
 * is still first-same-as-last (its row equals the 5th-order weights).
 *
 * <p>Units: positions in m, velocities in m/s, times in s. The error test
 * uses a mixed absolute/relative tolerance applied per phase-space block
 * (position and velocity separately).
 */
public final class AdaptiveIntegrator {

    /** Immutable phase-space state. */
    public record State(Vec3 position, Vec3 velocity, double time) {
    }

    /** Outcome of an integration, including step statistics. */
    public record Result(State state, int acceptedSteps, int rejectedSteps) {
    }

    // c, a, bHat (5th order), b (4th order); stages = 7, FSAL.
    // Coefficients transcribed from Principia (MIT), integrators/methods.hpp
    // (DormandPrince1986RK547FC).
    private static final double[] C = {
            0.0, 1.0 / 5.0, 3.0 / 10.0, 6.0 / 13.0, 2.0 / 3.0, 1.0, 1.0};
    private static final double[][] A = {
            {},
            {1.0 / 5.0},
            {3.0 / 40.0, 9.0 / 40.0},
            {264.0 / 2197.0, -90.0 / 2197.0, 840.0 / 2197.0},
            {932.0 / 3645.0, -14.0 / 27.0, 3256.0 / 5103.0, 7436.0 / 25515.0},
            {-367.0 / 513.0, 30.0 / 19.0, 9940.0 / 5643.0, -29575.0 / 8208.0, 6615.0 / 3344.0},
            {35.0 / 432.0, 0.0, 8500.0 / 14553.0, -28561.0 / 84672.0, 405.0 / 704.0, 19.0 / 196.0}};
    private static final double[] B_HAT = {
            35.0 / 432.0, 0.0, 8500.0 / 14553.0, -28561.0 / 84672.0,
            405.0 / 704.0, 19.0 / 196.0, 0.0};
    private static final double[] B = {
            11.0 / 108.0, 0.0, 6250.0 / 14553.0, -2197.0 / 21168.0,
            81.0 / 176.0, 171.0 / 1960.0, 1.0 / 40.0};
    private static final int STAGES = 7;

    private static final double SAFETY = 0.9;
    private static final double MIN_FACTOR = 0.2;
    private static final double MAX_FACTOR = 10.0;

    private final double absTol;
    private final double relTol;
    private final double minStep;
    private final double maxStep;

    public AdaptiveIntegrator(double absTol, double relTol) {
        this(absTol, relTol, 1.0e-12, Double.MAX_VALUE);
    }

    public AdaptiveIntegrator(double absTol, double relTol, double minStep, double maxStep) {
        if (absTol <= 0.0 || relTol <= 0.0) {
            throw new IllegalArgumentException("tolerances must be positive");
        }
        this.absTol = absTol;
        this.relTol = relTol;
        this.minStep = minStep;
        this.maxStep = maxStep;
    }

    /**
     * Integrates from {@code start.time()} to {@code tFinal} (either
     * direction), landing exactly on {@code tFinal}.
     */
    public Result integrate(State start, double tFinal, AccelerationFunction acceleration) {
        Vec3 q = start.position();
        Vec3 v = start.velocity();
        double t = start.time();
        double direction = Math.signum(tFinal - t);
        if (direction == 0.0) {
            return new Result(start, 0, 0);
        }

        // Derivative of the previous accepted step's endpoint (FSAL reuse).
        Vec3 kq0 = v;
        Vec3 kv0 = acceleration.at(q, t);

        double h = direction * Math.min(maxStep, initialStepGuess(q, v, kq0, kv0));
        int accepted = 0;
        int rejected = 0;

        while (direction * (tFinal - t) > 0.0) {
            if (direction * (t + h - tFinal) > 0.0) {
                h = tFinal - t;
            }
            double absH = Math.abs(h);
            if (absH < minStep) {
                throw new ArithmeticException(
                        "step size " + absH + " underflow below minStep " + minStep
                                + " at t=" + t);
            }

            Vec3[] kq = new Vec3[STAGES];
            Vec3[] kv = new Vec3[STAGES];
            kq[0] = kq0;
            kv[0] = kv0;
            for (int i = 1; i < STAGES; i++) {
                Vec3 qs = q;
                Vec3 vs = v;
                double[] row = A[i];
                for (int j = 0; j < row.length; j++) {
                    double hij = h * row[j];
                    if (hij != 0.0) {
                        qs = qs.add(kq[j].scale(hij));
                        vs = vs.add(kv[j].scale(hij));
                    }
                }
                kq[i] = vs;
                kv[i] = acceleration.at(qs, t + h * C[i]);
            }

            // 5th-order solution and 5th-minus-4th error estimate.
            Vec3 qNew = q;
            Vec3 vNew = v;
            Vec3 errQ = Vec3.ZERO;
            Vec3 errV = Vec3.ZERO;
            for (int i = 0; i < STAGES; i++) {
                if (B_HAT[i] != 0.0) {
                    qNew = qNew.add(kq[i].scale(h * B_HAT[i]));
                    vNew = vNew.add(kv[i].scale(h * B_HAT[i]));
                }
                double diff = B_HAT[i] - B[i];
                if (diff != 0.0) {
                    errQ = errQ.add(kq[i].scale(h * diff));
                    errV = errV.add(kv[i].scale(h * diff));
                }
            }

            double qScale = absTol + relTol * Math.max(q.norm(), qNew.norm());
            double vScale = absTol + relTol * Math.max(v.norm(), vNew.norm());
            double errQn = errQ.norm() / qScale;
            double errVn = errV.norm() / vScale;
            double errNorm = Math.sqrt(0.5 * (errQn * errQn + errVn * errVn));

            if (errNorm <= 1.0) {
                // Accept. The last stage evaluated f at the new point: reuse it.
                t += h;
                q = qNew;
                v = vNew;
                kq0 = kq[STAGES - 1];
                kv0 = kv[STAGES - 1];
                accepted++;
            } else {
                rejected++;
            }

            double factor = errNorm == 0.0
                    ? MAX_FACTOR
                    : SAFETY * Math.pow(errNorm, -0.2);
            factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
            if (rejected > 0 && errNorm > 1.0) {
                factor = Math.min(factor, 1.0);
            }
            h = Math.copySign(Math.min(maxStep, Math.abs(h) * factor), direction);
        }

        return new Result(new State(q, v, tFinal), accepted, rejected);
    }

    private double initialStepGuess(Vec3 q, Vec3 v, Vec3 kq0, Vec3 kv0) {
        double d0 = Math.sqrt(0.5 * (Math.pow(q.norm(), 2) + Math.pow(v.norm(), 2)));
        double d1 = Math.sqrt(0.5 * (Math.pow(kq0.norm(), 2) + Math.pow(kv0.norm(), 2)));
        if (d0 < 1.0e-5 || d1 < 1.0e-5) {
            return 1.0e-6;
        }
        return 0.01 * d0 / d1;
    }
}
