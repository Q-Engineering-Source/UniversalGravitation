package wi.unigrav.api.integrator;

import java.util.Arrays;

/**
 * Coefficient table of a symplectic partitioned Runge-Kutta (kick-drift)
 * method for separable Hamiltonian systems q&#775; = v, v&#775; = a(q, t).
 *
 * <p>One step of size {@code h} is the composition of evolutions
 * (rightmost applied first)
 * <pre>
 *   exp(a[s-1] h D) exp(b[s-1] h K) ... exp(a[0] h D) exp(b[0] h K)
 * </pre>
 * where {@code K} is the kick operator (v &#8592; v + h a(q, t)) and
 * {@code D} is the drift operator (q &#8592; q + h v). This is the
 * convention of Principia's
 * {@code integrators/symplectic_partitioned_runge_kutta_integrator.hpp}.
 *
 * <p>All methods registered here are time-symmetric and
 * first-same-as-last (FSAL): {@code a} has one more entry than there are
 * force evaluations, {@code a[last] == 0}, and both arrays are palindromic.
 * Because the last drift vanishes, the trailing kick of one step and the
 * leading kick of the next act at the same position and stage time;
 * {@link SymplecticIntegrator} merges them into a single force evaluation
 * as a pure performance optimization.
 *
 * <p>The kick {@code b[i]} acts on the position reached after the drifts
 * {@code a[0..i-1]}, so for a time-dependent force its time argument is
 * {@code t + h * (a[0] + ... + a[i-1])}.
 */
public final class SymplecticMethod {

    private final String name;
    private final int order;
    /** Drift coefficients; {@code a[a.length - 1] == 0} for FSAL methods. */
    private final double[] a;
    /** Kick coefficients, same length as {@code a}. */
    private final double[] b;

    public SymplecticMethod(String name, int order, double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("a and b must have the same length");
        }
        if (a.length < 2) {
            throw new IllegalArgumentException("need at least one force evaluation");
        }
        this.name = name;
        this.order = order;
        this.a = Arrays.copyOf(a, a.length);
        this.b = Arrays.copyOf(b, b.length);
    }

    public String name() {
        return name;
    }

    /** Formal order of the method. */
    public int order() {
        return order;
    }

    /** Number of entries in each coefficient array (force evaluations + 1 for FSAL methods). */
    public int stages() {
        return a.length;
    }

    public double drift(int stage) {
        return a[stage];
    }

    public double kick(int stage) {
        return b[stage];
    }
}
