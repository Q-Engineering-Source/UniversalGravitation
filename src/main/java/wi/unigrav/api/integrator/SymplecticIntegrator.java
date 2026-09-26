package wi.unigrav.api.integrator;

import wi.unigrav.api.math.Vec3;

/**
 * Fixed-step symplectic integrator for separable Hamiltonian systems
 * q&#775; = v, v&#775; = a(q, t), using a {@link SymplecticMethod}
 * kick-drift coefficient table.
 *
 * <p>Units: positions in m, velocities in m/s, times in s.
 *
 * <p>{@link #advance} takes steps of the configured size; the final step is
 * shortened so that the trajectory lands exactly on {@code tFinal}. A
 * shortened step is still symplectic (the step size enters each kick/drift
 * linearly), it merely is not time-symmetric with respect to its neighbours.
 *
 * <p>TODO(milestone-2): Principia compensates floating-point error with
 * double-double (two-word) arithmetic in the state update; we deliberately
 * use plain doubles plus {@link Math#fma} for the first milestone.
 */
public final class SymplecticIntegrator {

    /** Immutable phase-space state. Times in s, positions in m, velocities in m/s. */
    public record State(Vec3 position, Vec3 velocity, double time) {
    }

    private final SymplecticMethod method;
    private final double step;

    public SymplecticIntegrator(SymplecticMethod method, double step) {
        if (step <= 0.0 || !Double.isFinite(step)) {
            throw new IllegalArgumentException("step must be positive and finite");
        }
        this.method = method;
        this.step = step;
    }

    public SymplecticMethod method() {
        return method;
    }

    public double step() {
        return step;
    }

    /**
     * Advances the state from {@code start.time()} to {@code tFinal}.
     * Works in both time directions; {@code tFinal == start.time()} returns
     * {@code start} unchanged.
     */
    public State advance(State start, double tFinal, AccelerationFunction acceleration) {
        Vec3 q = start.position();
        Vec3 v = start.velocity();
        double t = start.time();
        double direction = Math.signum(tFinal - t);
        int stages = method.stages();
        // For FSAL methods (a[last] == 0) the trailing kick of one step and
        // the leading kick of the next act at the same position and the same
        // stage time, so they are merged into a single force evaluation.
        // This is a pure performance optimization; the trajectory is
        // mathematically identical (up to one possible last-ulp difference
        // from evaluating b0+b_last at once instead of sequentially).
        boolean fsal = method.drift(stages - 1) == 0.0;
        boolean firstStep = true;
        double hPrev = 0.0;
        while (direction * (tFinal - t) > 0.0) {
            double remaining = direction * (tFinal - t);
            boolean lastStep = remaining <= step;
            double h = Math.min(step, remaining) * direction;
            for (int i = 0; i < stages; i++) {
                if (fsal && i == stages - 1 && !lastStep) {
                    // Folded into the next step's first kick; the last drift
                    // is zero anyway.
                    break;
                }
                double b = method.kick(i);
                if (fsal && i == 0 && !firstStep) {
                    // Merged kick: one force evaluation for this step's
                    // b0·h plus the previous step's trailing b_last·h_prev
                    // (the weights differ when the previous step was
                    // shortened, so they cannot be summed blindly).
                    double stageTime = t + h * prefixDriftSum(i);
                    Vec3 acc = acceleration.at(q, stageTime);
                    v = v.add(acc.scale(Math.fma(method.kick(stages - 1), hPrev, b * h)));
                } else if (b != 0.0) {
                    double stageTime = t + h * prefixDriftSum(i);
                    Vec3 acc = acceleration.at(q, stageTime);
                    v = v.add(acc.scale(h * b));
                }
                double a = method.drift(i);
                if (a != 0.0) {
                    q = q.add(v.scale(h * a));
                }
            }
            t += h;
            hPrev = h;
            firstStep = false;
        }
        return new State(q, v, tFinal);
    }

    /** Sum of a[0..stage-1], used for the time argument of a kick. Cached. */
    private double prefixDriftSum(int stage) {
        double[] prefix = prefixDriftSums;
        if (prefix == null) {
            prefix = new double[method.stages()];
            double sum = 0.0;
            for (int i = 0; i < prefix.length; i++) {
                prefix[i] = sum;
                sum += method.drift(i);
            }
            prefixDriftSums = prefix;
        }
        return prefix[stage];
    }

    private volatile double[] prefixDriftSums;
}
