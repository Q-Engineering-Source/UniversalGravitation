package wi.unigrav.core.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.BodyGravitySource;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.HarmonicDamping;
import wi.unigrav.api.gravity.KeplerianOrbit;
import wi.unigrav.api.gravity.SphericalHarmonics;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.AmbientWorldFields;
import net.minecraft.world.World;

/**
 * Data-driven gravity-source loading: reads every {@code *.json} file in
 * {@code config/unigrav/sources} at server start and registers the sources
 * into the global {@link GravityFields} registry. A file that fails to
 * parse is logged and skipped without aborting the others.
 *
 * <p>Schema (one source per file):
 * <pre>
 * {
 *   "dimension": 0,                      // optional integer; absent means all worlds
 *   "id": "unigrav:example_planet",      // namespace:path (namespace defaults to "unigrav")
 *   "mu": 3.986e14,                      // gravitational parameter, m³/s² (required)
 *   "kind": "static" | "orbit",          // (required)
 *   "position": [x, y, z],               // kind=static, metres
 *   "orbit": {                            // kind=orbit
 *     "center": "unigrav:star" | [x,y,z],// source id (resolved dynamically) or fixed point
 *     "centralMu": 1.3e20,               // required when center is a position; optional
 *                                        // override when center is an id (default: that
 *                                        // source's mu at load time)
 *     "semiMajorAxis": 1.5e11,           // m (required)
 *     "eccentricity": 0.0,
 *     "inclinationDeg": 0.0, "raanDeg": 0.0, "argPeriapsisDeg": 0.0,
 *     "meanAnomalyAtEpochDeg": 0.0, "epoch": 0.0
 *   },
 *   "harmonics": {                        // optional; fully-normalized coefficients
 *     "referenceRadius": 6.371e6,         // m
 *     "degree": 2,                        // max degree; must match the c/s array sizes
 *     "c": [[0],[0,0],[-4.8417e-4,0,0]],  // c[n][m]: row = degree n, column = order m;
 *     "s": [[0],[0,0],[0,0,0]],           // ragged rows allowed, missing entries = 0
 *     "dampingInnerRadius": 1.0e7         // optional, m; outer = 3 × inner (HarmonicDamping)
 *   },
 *   "spin": { "rateRadPerSec": 7.292e-5,  // optional
 *             "axis": [0,0,1], "phaseRad": 0.0 }
 * }
 * </pre>
 *
 * <p>Sources registered here are removed again by {@link #unloadAll()} at
 * server stop.
 */
public final class GravitySourcesLoader {

    /** Directory (relative to the game config dir) that holds the source files. */
    public static final String SOURCES_SUBDIR = "unigrav/sources";

    private final List<GravityField.Registration> registrations = new ArrayList<>();

    /** One parsed file, pending construction. */
    private record Pending(GravityField.Id id, JsonObject root, String fileName) {
    }

    /**
     * Loads all sources from {@code dir}. Returns the number of sources
     * successfully registered. Construction proceeds in rounds so that
     * {@code kind=orbit} sources may reference centers defined by other
     * files in the same batch; sources whose center id cannot be resolved
     * (missing or cyclic reference) are logged and skipped.
     */
    public int loadAll(File dir) {
        if (!dir.isDirectory()) {
            if (!dir.mkdirs()) {
                UnigravMod.LOGGER.warn("Could not create gravity sources dir {}", dir);
                return 0;
            }
            UnigravMod.LOGGER.info("Created empty gravity sources dir {}", dir);
            return 0;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            UnigravMod.LOGGER.info("No gravity source files in {}", dir);
            return 0;
        }

        List<Pending> pending = new ArrayList<>();
        for (File file : files) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file.toPath()))
                        .getAsJsonObject();
                pending.add(new Pending(parseId(requiredString(root, "id", file.getName())),
                        root, file.getName()));
            } catch (IOException | JsonParseException | IllegalStateException e) {
                UnigravMod.LOGGER.error("Skipping gravity source file {}: {}", file.getName(), e);
            }
        }

        int loaded = 0;
        // Sources whose center is another source's id are built only once
        // that center is registered; iterate until no more progress.
        List<Pending> remaining = new ArrayList<>(pending);
        while (!remaining.isEmpty()) {
            List<Pending> nextRound = new ArrayList<>();
            boolean progress = false;
            for (Pending p : remaining) {
                try {
                    GravitySource source = buildSource(p);
                    if (source == null) {
                        nextRound.add(p); // center not registered yet
                        continue;
                    }
                    Integer dimension = parseDimension(p.root(), p.fileName());
                    registrations.add(GravityFields.get().register(p.id(), dimension == null
                            ? source : new DimensionSource(dimension, source)));
                    UnigravMod.LOGGER.info("Registered gravity source {} from {}", p.id(), p.fileName());
                    loaded++;
                    progress = true;
                } catch (IllegalArgumentException | JsonParseException e) {
                    UnigravMod.LOGGER.error("Skipping gravity source {} from {}: {}",
                            p.id(), p.fileName(), e);
                    progress = true; // permanently failed: do not retry
                }
            }
            if (!progress) {
                for (Pending p : nextRound) {
                    UnigravMod.LOGGER.error(
                            "Skipping gravity source {} from {}: center id is missing or cyclic",
                            p.id(), p.fileName());
                }
                break;
            }
            remaining = nextRound;
        }
        return loaded;
    }

    /** Unregisters everything {@link #loadAll} registered. */
    public void unloadAll() {
        for (GravityField.Registration registration : registrations) {
            registration.close();
        }
        registrations.clear();
    }

    /**
     * Builds the source described by one parsed file. Returns {@code null}
     * if it is an id-centered orbit whose center is not registered yet.
     */
    private static GravitySource buildSource(Pending p) {
        JsonObject root = p.root();
        String file = p.fileName();
        double mu = requiredDouble(root, "mu", file);
        String kind = requiredString(root, "kind", file);

        BodyGravitySource source;
        switch (kind) {
            case "static" -> {
                source = BodyGravitySource.fixed(mu, vector(required(root, "position", file), file));
            }
            case "orbit" -> {
                JsonObject orbit = required(root, "orbit", file).getAsJsonObject();
                JsonElement center = required(orbit, "center", file);
                java.util.function.Function<Double, Vec3> centerPosition;
                double centralMu;
                if (center.isJsonArray()) {
                    Vec3 fixed = vector(center, file);
                    centerPosition = t -> fixed;
                    centralMu = requiredDouble(orbit, "centralMu", file);
                } else {
                    GravityField.Id centerId = parseId(center.getAsString());
                    GravitySource centerSource = GravityFields.get().snapshot().get(centerId);
                    if (centerSource == null) {
                        return null; // try again in the next round
                    }
                    centerPosition = t -> {
                        GravitySource current = GravityFields.get().snapshot().get(centerId);
                        if (current == null) {
                            throw new IllegalStateException(
                                    "gravity center " + centerId + " is no longer registered");
                        }
                        return current.position(t);
                    };
                    centralMu = orbit.has("centralMu")
                            ? orbit.get("centralMu").getAsDouble()
                            : centerSource.gravitationalParameter();
                }
                KeplerianOrbit kepler = new KeplerianOrbit(
                        requiredDouble(orbit, "semiMajorAxis", file),
                        optionalDouble(orbit, "eccentricity", 0.0),
                        Math.toRadians(optionalDouble(orbit, "inclinationDeg", 0.0)),
                        Math.toRadians(optionalDouble(orbit, "raanDeg", 0.0)),
                        Math.toRadians(optionalDouble(orbit, "argPeriapsisDeg", 0.0)),
                        Math.toRadians(optionalDouble(orbit, "meanAnomalyAtEpochDeg", 0.0)),
                        optionalDouble(orbit, "epoch", 0.0),
                        centralMu);
                source = BodyGravitySource.orbiting(mu, kepler, centerPosition);
            }
            default -> throw new IllegalArgumentException(
                    "unknown kind \"" + kind + "\" (expected static|orbit)");
        }

        if (root.has("harmonics")) {
            source = source.withHarmonics(parseHarmonics(root.getAsJsonObject("harmonics"), file));
        }
        if (root.has("spin")) {
            JsonObject spin = root.getAsJsonObject("spin");
            source = source.withSpin(
                    requiredDouble(spin, "rateRadPerSec", file),
                    spin.has("axis") ? vector(spin.get("axis"), file) : new Vec3(0.0, 0.0, 1.0),
                    optionalDouble(spin, "phaseRad", 0.0));
        }
        if (root.has("softening")) {
            source = source.withSoftening(optionalDouble(root, "softening", 0.0));
        }
        return source;
    }

    /** Missing dimension preserves the original all-world semantics. */
    private static Integer parseDimension(JsonObject root, String file) {
        if (!root.has("dimension")) return null;
        JsonElement dimension = root.get("dimension");
        if (!dimension.isJsonPrimitive() || !dimension.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("dimension must be an integer in " + file);
        try { return dimension.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException failure) {
            throw new IllegalArgumentException("dimension must be an integer in " + file, failure);
        }
    }

    private record DimensionSource(int dimension, GravitySource source)
            implements GravitySource, AmbientWorldFields.Selection {
        @Override public boolean appliesTo(World world) { return world.provider.getDimension() == dimension; }
        @Override public double gravitationalParameter() { return source.gravitationalParameter(); }
        @Override public Vec3 position(double time) { return source.position(time); }
        @Override public Vec3 accelerationAt(Vec3 position, double time) { return source.accelerationAt(position, time); }
        @Override public double softeningLength() { return source.softeningLength(); }
        @Override public SphericalHarmonics harmonics() { return source.harmonics(); }
        @Override public double angularVelocity() { return source.angularVelocity(); }
        @Override public Vec3 rotationAxis() { return source.rotationAxis(); }
        @Override public double initialPhase() { return source.initialPhase(); }
    }

    private static SphericalHarmonics parseHarmonics(JsonObject h, String file) {
        double referenceRadius = requiredDouble(h, "referenceRadius", file);
        double[][] c = h.has("c") ? matrix(h.getAsJsonArray("c"), file) : new double[1][1];
        double[][] s = h.has("s") ? matrix(h.getAsJsonArray("s"), file) : new double[1][1];
        int degree = Math.max(c.length, s.length) - 1;
        if (h.has("degree") && h.get("degree").getAsInt() != degree) {
            throw new IllegalArgumentException("harmonics.degree " + h.get("degree").getAsInt()
                    + " does not match c/s array sizes (implied degree " + degree + ")");
        }
        HarmonicDamping damping = h.has("dampingInnerRadius")
                ? new HarmonicDamping(h.get("dampingInnerRadius").getAsDouble())
                : null;
        return new SphericalHarmonics(referenceRadius, square(c, degree), square(s, degree), damping);
    }

    /** Reads a ragged rows=degree, columns=order matrix. */
    private static double[][] matrix(JsonArray rows, String file) {
        double[][] result = new double[rows.size()][];
        for (int n = 0; n < rows.size(); n++) {
            JsonArray row = rows.get(n).getAsJsonArray();
            result[n] = new double[row.size()];
            for (int m = 0; m < row.size(); m++) {
                result[n][m] = row.get(m).getAsDouble();
            }
        }
        return result;
    }

    /** Pads/truncates a ragged matrix to a square of side degree+1, filling zeros. */
    private static double[][] square(double[][] ragged, int degree) {
        double[][] result = new double[degree + 1][degree + 1];
        for (int n = 0; n <= degree && n < ragged.length; n++) {
            System.arraycopy(ragged[n], 0, result[n], 0, Math.min(ragged[n].length, degree + 1));
        }
        return result;
    }

    /** Parses "namespace:path"; a missing namespace defaults to "unigrav". */
    private static GravityField.Id parseId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0
                ? new GravityField.Id(id.substring(0, colon), id.substring(colon + 1))
                : new GravityField.Id("unigrav", id);
    }

    private static Vec3 vector(JsonElement element, String file) {
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException("expected a 3-element vector in " + file);
        }
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static JsonElement required(JsonObject object, String member, String file) {
        JsonElement element = object.get(member);
        if (element == null) {
            throw new IllegalArgumentException("missing required member \"" + member + "\" in " + file);
        }
        return element;
    }

    private static String requiredString(JsonObject object, String member, String file) {
        return required(object, member, file).getAsString();
    }

    private static double requiredDouble(JsonObject object, String member, String file) {
        return required(object, member, file).getAsDouble();
    }

    private static double optionalDouble(JsonObject object, String member, double defaultValue) {
        return object.has(member) ? object.get(member).getAsDouble() : defaultValue;
    }
}
