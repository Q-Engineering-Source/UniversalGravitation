package wi.unigrav.api.integrator;

/**
 * Static table of symplectic partitioned Runge-Kutta methods.
 *
 * <p>Coefficients transcribed from Principia (MIT),
 * {@code integrators/methods.hpp}, with the paper references noted per
 * method. Each array was checked digit by digit against that file; the
 * consistency conditions {@code sum(a) == 1} and {@code sum(b) == 1} were
 * verified after transcription.
 */
public final class SymplecticMethods {

    /**
     * Störmer-Verlet / leapfrog, 2nd order, 1 force evaluation per step.
     * Appears in Newton (1687), Delambre (1790), Störmer (1907) and Verlet
     * (1967); see [HLW03] section 1.3 (Historical remarks). This is the
     * velocity-Verlet (KDK) form.
     * Coefficients transcribed from Principia (MIT), integrators/methods.hpp
     * ({@code NewtonDelambreStørmerVerletLeapfrog}).
     */
    public static final SymplecticMethod STORMER_VERLET = new SymplecticMethod(
            "NewtonDelambreStørmerVerletLeapfrog",
            2,
            new double[] {1.0, 0.0},
            new double[] {0.5, 0.5});

    /**
     * McLachlan (1995) S4, 4th order, 4 force evaluations per step.
     * [McL95] McLachlan, "On the numerical integration of ordinary
     * differential equations by symmetric composition methods",
     * SIAM J. Sci. Comput. 16(1), 1995.
     * Coefficients transcribed from Principia (MIT), integrators/methods.hpp
     * ({@code McLachlan1995S4}).
     */
    public static final SymplecticMethod MCLACHLAN_1995_S4 = new SymplecticMethod(
            "McLachlan1995S4",
            4,
            new double[] {
                    +0.54545454545454545455,
                    -0.045454545454545454545,
                    -0.045454545454545454545,
                    +0.54545454545454545455,
                    +0.0},
            new double[] {
                    +0.16913927992207204518,
                    -0.29918620390405079951,
                    +1.2600938479639575087,
                    -0.29918620390405079951,
                    +0.16913927992207204518});

    /**
     * Blanes &amp; Moan (2002) S6, 6 force evaluations per step.
     * [BM02] Blanes and Moan, "Practical symplectic partitioned Runge–Kutta
     * and Runge–Kutta–Nyström methods", J. Comput. Appl. Math. 142, 2002.
     * Coefficients transcribed from Principia (MIT), integrators/methods.hpp
     * ({@code BlanesMoan2002S6}).
     *
     * <p>NOTE: despite the suggestive name, S6 is a <b>4th-order</b> method
     * (the "6" counts the force evaluations, not the order); it has an
     * exceptionally small error constant, which is why it is the workhorse
     * here. For a true 6th-order method see {@link #BLANES_MOAN_2002_S10}.
     */
    public static final SymplecticMethod BLANES_MOAN_2002_S6 = new SymplecticMethod(
            "BlanesMoan2002S6",
            4,
            new double[] {
                    +0.20951510661336200,
                    -0.14385177317981800,
                    +0.43433666656645600,
                    +0.43433666656645600,
                    -0.14385177317981800,
                    +0.20951510661336200,
                    +0.0},
            new double[] {
                    +0.079203696431195700,
                    +0.35317290604977400,
                    -0.042065080357719500,
                    +0.21937695575349960,
                    -0.042065080357719500,
                    +0.35317290604977400,
                    +0.079203696431195700});

    /**
     * Blanes &amp; Moan (2002) S10, true 6th order, 10 force evaluations
     * per step. Same reference [BM02] as above.
     * Coefficients transcribed from Principia (MIT), integrators/methods.hpp
     * ({@code BlanesMoan2002S10}).
     */
    public static final SymplecticMethod BLANES_MOAN_2002_S10 = new SymplecticMethod(
            "BlanesMoan2002S10",
            6,
            new double[] {
                    +0.14881644790104200,
                    -0.13238586576778400,
                    +0.067307604692185000,
                    +0.43266640257817500,
                    -0.01640458940361800,
                    -0.01640458940361800,
                    +0.43266640257817500,
                    +0.067307604692185000,
                    -0.13238586576778400,
                    +0.14881644790104200,
                    +0.0},
            new double[] {
                    +0.050262764400392200,
                    +0.41351430042834400,
                    +0.045079889794397700,
                    -0.18805485381956900,
                    +0.54196067845078000,
                    -0.7255255585086898,
                    +0.54196067845078000,
                    -0.18805485381956900,
                    +0.045079889794397700,
                    +0.41351430042834400,
                    +0.050262764400392200});

    private SymplecticMethods() {
    }
}
