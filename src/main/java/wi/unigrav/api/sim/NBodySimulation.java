package wi.unigrav.api.sim;

import java.util.ArrayList;
import java.util.List;

import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.integrator.SymplecticMethod;
import wi.unigrav.api.math.Vec3;

/**
 * Fixed-step translational N-body simulation: a set of massive bodies that
 * attract each other pairwise (Newtonian superposition), plus any number of
 * massless probes that feel the massive bodies but affect nothing.
 *
 * <p>Each probe may additionally be coupled to an external
 * {@link GravityField} (e.g. the global
 * {@link wi.unigrav.api.field.GravityFields#get()} registry): its
 * acceleration is then the superposition of the sim-internal massive bodies
 * and the external field evaluated at the probe's position and the current
 * stage time. Probes without an external field feel the sim-internal bodies
 * only, so both modes can be mixed freely within one simulation.
 *
 * <p>Advancement uses the kick-drift composition of the configured
 * {@link SymplecticMethod} applied to the whole coupled phase space: at
 * each stage every body's velocity is kicked with the accelerations
 * evaluated at the current positions, then every position drifts. This is
 * the same scheme as {@code SymplecticIntegrator}, lifted to a composite
 * state.
 *
 * <p>This class is pure API: it knows nothing about Minecraft ticks and
 * can be driven with any step size for offline integration. Units: SI
 * throughout (m, s, kg); the massive-body coupling uses the gravitational
 * constant {@link #GRAVITATIONAL_CONSTANT}.
 *
 * <p>TODO(milestone-2): softened potentials / collision handling.
 */
public final class NBodySimulation {

    /** Newtonian gravitational constant, m³·kg⁻¹·s⁻². */
    public static final double GRAVITATIONAL_CONSTANT = 6.674e-11;

    private final SymplecticMethod method;
    private final double step;

    private final List<BodyState> bodies = new ArrayList<>();
    private final List<BodyState> probes = new ArrayList<>();
    /** Per-probe external field, aligned with {@link #probes}; null = internal only. */
    private final List<GravityField> probeFields = new ArrayList<>();
    private double time;

    public NBodySimulation(SymplecticMethod method, double step) {
        if (step <= 0.0 || !Double.isFinite(step)) {
            throw new IllegalArgumentException("step must be positive and finite");
        }
        this.method = method;
        this.step = step;
    }

    /** Adds a massive body; its {@link BodyState#time()} sets the simulation time if this is the first entry. */
    public void addBody(BodyState body) {
        if (body.isProbe()) {
            throw new IllegalArgumentException("use addProbe for massless bodies");
        }
        if (bodies.isEmpty() && probes.isEmpty()) {
            time = body.time();
        }
        bodies.add(body);
    }

    /** Adds a massless probe that feels only the sim-internal massive bodies. */
    public void addProbe(BodyState probe) {
        addProbe(probe, null);
    }

    /**
     * Adds a massless probe whose acceleration is the superposition of the
     * sim-internal massive bodies and {@code externalField} (which may be
     * {@code null}, equivalent to {@link #addProbe(BodyState)}).
     */
    public void addProbe(BodyState probe, GravityField externalField) {
        if (!probe.isProbe()) {
            throw new IllegalArgumentException("use addBody for massive bodies");
        }
        if (bodies.isEmpty() && probes.isEmpty()) {
            time = probe.time();
        }
        probes.add(probe);
        probeFields.add(externalField);
    }

    public double time() {
        return time;
    }

    public SymplecticMethod method() {
        return method;
    }

    public double step() {
        return step;
    }

    /** Snapshot of the massive bodies' current states. */
    public List<BodyState> bodies() {
        return List.copyOf(bodies);
    }

    /** Snapshot of the probes' current states. */
    public List<BodyState> probes() {
        return List.copyOf(probes);
    }

    /**
     * Advances the whole system to {@code tFinal} ({@code >= time()}),
     * taking steps of {@link #step()} with a shortened final step to land
     * exactly on {@code tFinal}.
     */
    public void advanceTo(double tFinal) {
        if (tFinal < time) {
            throw new IllegalArgumentException("cannot integrate backwards: " + tFinal + " < " + time);
        }
        int nb = bodies.size();
        int np = probes.size();
        int total = nb + np;
        if (total == 0) {
            time = tFinal;
            return;
        }

        double[] mass = new double[total];
        Vec3[] q = new Vec3[total];
        Vec3[] v = new Vec3[total];
        GravityField[] fields = new GravityField[total];
        for (int i = 0; i < nb; i++) {
            mass[i] = bodies.get(i).mass();
            q[i] = bodies.get(i).position();
            v[i] = bodies.get(i).velocity();
        }
        for (int i = 0; i < np; i++) {
            q[nb + i] = probes.get(i).position();
            v[nb + i] = probes.get(i).velocity();
            fields[nb + i] = probeFields.get(i);
        }

        int stages = method.stages();
        double[] prefix = prefixDriftSums();
        double t = time;
        while (t < tFinal) {
            double h = Math.min(step, tFinal - t);
            for (int s = 0; s < stages; s++) {
                double b = method.kick(s);
                if (b != 0.0) {
                    Vec3[] acc = computeAccelerations(q, mass, fields, nb, total, t + h * prefix[s]);
                    double hb = h * b;
                    for (int i = 0; i < total; i++) {
                        v[i] = v[i].add(acc[i].scale(hb));
                    }
                }
                double a = method.drift(s);
                if (a != 0.0) {
                    double ha = h * a;
                    for (int i = 0; i < total; i++) {
                        q[i] = q[i].add(v[i].scale(ha));
                    }
                }
            }
            t += h;
        }

        for (int i = 0; i < nb; i++) {
            bodies.set(i, bodies.get(i).at(q[i], v[i], tFinal));
        }
        for (int i = 0; i < np; i++) {
            probes.set(i, probes.get(i).at(q[nb + i], v[nb + i], tFinal));
        }
        time = tFinal;
    }

    /**
     * Newtonian accelerations by superposition: massive bodies attract each
     * other pairwise; probes (indices {@code nb..total-1}) feel the massive
     * bodies but attract nothing, and additionally feel their external
     * {@link GravityField} if one is attached.
     */
    private static Vec3[] computeAccelerations(Vec3[] q, double[] mass, GravityField[] fields,
                                               int nb, int total, double stageTime) {
        Vec3[] acc = new Vec3[total];
        for (int i = 0; i < total; i++) {
            acc[i] = Vec3.ZERO;
        }
        for (int j = 0; j < nb; j++) {
            double gmj = GRAVITATIONAL_CONSTANT * mass[j];
            for (int i = 0; i < total; i++) {
                if (i == j) {
                    continue;
                }
                Vec3 offset = q[i].sub(q[j]);
                double r2 = offset.normSquared();
                double r = Math.sqrt(r2);
                acc[i] = acc[i].add(offset.scale(-gmj / (r2 * r)));
            }
        }
        for (int i = nb; i < total; i++) {
            GravityField field = fields[i];
            if (field != null) {
                acc[i] = acc[i].add(field.accelerationAt(q[i], stageTime));
            }
        }
        return acc;
    }

    /** Sum of drift coefficients a[0..stage-1], for the stage time of a kick. Cached. */
    private double[] prefixDriftSums() {
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
        return prefix;
    }

    private volatile double[] prefixDriftSums;
}
