package wi.unigrav.core;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntitySheep;

/**
 * Physical bulk properties of a bound entity: mass (kg) and volume (m³).
 *
 * <p>Gravity itself is mass-independent (equivalence principle) — mass only
 * matters for non-gravitational forces (thrust, impulses via
 * {@link GravityEntityBinder#setForce}/{@link GravityEntityBinder#addImpulse},
 * where a = F/m) and for buoyancy (the displaced-water mass ratio
 * ρw·V/m decides whether the entity floats, hovers, or sinks).
 *
 * <p>The default {@link #estimate(Entity)} first checks the species preset table
 * (per-species base mass + relative variance, looked up along the class
 * hierarchy so subclasses inherit the nearest ancestor's preset), then
 * falls back to deriving volume from the entity's bounding box (width² ×
 * height, times a fill factor for the non-rectangular body) and mass from
 * volume × {@code default_body_density}. Preset masses vary per individual
 * by ±variance, the factor drawn sequentially from a pseudo-random pool
 * pre-generated at game load and cached per entity, so refreshes never
 * reshuffle. Downstream mods that care about exact numbers should bind
 * with explicit values instead of the estimate.
 *
 * <p>The preset table is data-driven: populated at preInit by
 * {@code MassPresetsLoader} from {@code config/unigrav/mass_presets.json}.
 */
public record BodyPhysics(double massKg, double volumeM3) {

    /**
     * Fraction of the bounding-box volume counted as body volume. 0.1
     * matches a real humanoid: a body is mostly empty space inside its
     * AABB (a ~70 kg adult in a 0.6×0.6×1.8 box fills about 10%).
     */
    private static final double BOUNDING_BOX_FILL = 0.1;

    /** A sheared sheep has lost its fleece (~10 kg of wool). */
    private static double shearedSheepMass = 110.0;
    private static double shearedSheepVariance = 0.1;

    /**
     * Pool of pseudo-random variance factors in [-1, 1), generated once at
     * class (game) load; spawns draw from it sequentially, wrapping around.
     */
    private static final double[] VARIANCE_POOL = new double[4096];
    private static int varianceCursor;
    /**
     * The pool value assigned to each entity, cached so repeated estimates
     * (bind, periodic refresh, HUD) never reshuffle an individual's mass.
     * Weak keys: dead entities drop out with the GC.
     */
    private static final Map<Entity, Double> ASSIGNED_VARIANCE = new WeakHashMap<>();

    static {
        Random random = new Random();
        for (int i = 0; i < VARIANCE_POOL.length; i++) {
            VARIANCE_POOL[i] = random.nextDouble() * 2.0 - 1.0;
        }
    }

    /**
     * Per-species preset, looked up by entity class (nearest superclass
     * wins, so e.g. Mooshroom inherits the cow preset). Each individual's
     * mass is base × (1 ± relVariance), with a pseudo-random factor drawn
     * from the pre-generated pool. Volume is still the bounding-box
     * estimate, so a preset mass implies a density.
     *
     * <p>{@code swims}: false means the species cannot swim (iron golem)
     * and sinks in water at full gravity, without the buoyancy term.
     * {@code affectedByGravity}: false means the species keeps its own
     * movement physics (vanilla flyers: ghast, blaze, …) and is excluded
     * from the global gravity takeover entirely. {@code zeroGPathing}:
     * true means the species can navigate while weightless (flyers), so
     * the zero-G AI pause leaves it alone; ground mobs default to false
     * and get NoAI while floating weightless (their navigator would
     * otherwise spin them in place).
     *
     * <p>Slimes/magma cubes are deliberately unpreset: their bounding box
     * scales with size, so the formula estimate already tracks growth.
     */
    public record SpeciesPreset(double massKg, double relVariance, boolean swims,
                                boolean affectedByGravity, boolean zeroGPathing) {
    }

    private static final SpeciesPhysicsCatalog SPECIES_PRESETS =
            new SpeciesPhysicsCatalog();

    public BodyPhysics {
        if (massKg <= 0.0) {
            throw new IllegalArgumentException("mass must be positive");
        }
        if (volumeM3 < 0.0) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
    }

    /**
     * Registers or overrides a species preset (called by
     * {@code MassPresetsLoader} from the JSON config).
     */
    public static void registerPreset(Class<? extends Entity> type, double massKg,
                                      double relVariance, boolean swims,
                                      boolean affectedByGravity, boolean zeroGPathing) {
        registerPreset(type, massKg, relVariance, swims, affectedByGravity,
                zeroGPathing, 0.0);
    }

    /**
     * Registers or completely replaces a species preset and its passive
     * magnetic response. The response is dimensionless: {@code 0.0} means
     * that magnetic floors do not affect this species.
     */
    public static void registerPreset(Class<? extends Entity> type, double massKg,
                                      double relVariance, boolean swims,
                                      boolean affectedByGravity, boolean zeroGPathing,
                                      double magneticResponse) {
        SPECIES_PRESETS.replace(type, new SpeciesPreset(massKg, relVariance, swims,
                affectedByGravity, zeroGPathing), magneticResponse);
    }

    /** Sets the sheared-sheep mass (from the JSON config's sheep entry). */
    public static void setShearedSheepMass(double massKg, double relVariance) {
        shearedSheepMass = massKg;
        shearedSheepVariance = relVariance;
    }

    /**
     * Estimate from the preset table (with the sheared-sheep special case),
     * else from the bounding box and the configured density. State-dependent
     * (a sheared sheep estimates lighter), which is why estimated bindings
     * are periodically refreshed — see
     * {@link GravityEntityBinder#refreshIfEstimated}. Preset masses include
     * a per-individual variance drawn from the pre-generated random pool and
     * cached per entity, so repeated
     * estimate of the same entity is stable (the drawn factor is cached
     * per entity; note client and server mirrors draw independently, so a
     * client-side label can differ slightly from the server's value).
     */
    public static BodyPhysics estimate(Entity entity) {
        double volume = entity.width * entity.width * entity.height * BOUNDING_BOX_FILL;
        double mass;
        if (entity instanceof EntitySheep && ((EntitySheep) entity).getSheared()) {
            mass = vary(shearedSheepMass, shearedSheepVariance, entity);
        } else {
            SpeciesPreset preset = speciesPreset(entity);
            mass = preset != null
                    ? vary(preset.massKg(), preset.relVariance(), entity)
                    : volume * UnigravConfig.defaultBodyDensity;
        }
        return new BodyPhysics(mass, volume);
    }

    /**
     * The preset applying to this entity (nearest superclass wins), or null
     * when no preset matches (formula-estimated entities).
     */
    public static SpeciesPreset speciesPreset(Entity entity) {
        SpeciesPhysicsCatalog.Entry registered = registeredSpecies(entity);
        return registered != null ? registered.preset() : null;
    }

    /**
     * The passive magnetic response of the nearest applicable mass-preset row.
     * Entities without a preset are non-magnetic.
     */
    public static double magneticResponse(Entity entity) {
        SpeciesPhysicsCatalog.Entry registered = registeredSpecies(entity);
        return registered != null ? registered.magneticResponse() : 0.0;
    }

    private static SpeciesPhysicsCatalog.Entry registeredSpecies(Entity entity) {
        return SPECIES_PRESETS.nearest(entity.getClass());
    }

    /** Whether the entity's species can swim (default true when unpreset). */
    public static boolean swims(Entity entity) {
        SpeciesPreset preset = speciesPreset(entity);
        return preset == null || preset.swims();
    }

    /**
     * Whether the entity's species is subject to the gravity field at all
     * (default true when unpreset). Vanilla flyers (ghast, blaze, …) carry
     * {@code "gravity": false} and are excluded from the takeover.
     */
    public static boolean affectedByGravity(Entity entity) {
        SpeciesPreset preset = speciesPreset(entity);
        return preset == null || preset.affectedByGravity();
    }

    /**
     * Whether the entity's species can navigate while weightless (default
     * false when unpreset — ground navigators spin in place in zero-G, so
     * they get the AI pause).
     */
    public static boolean pathsInZeroG(Entity entity) {
        SpeciesPreset preset = speciesPreset(entity);
        return preset != null && preset.zeroGPathing();
    }

    /**
     * Per-individual mass variance: base × (1 ± relVariance), with the
     * factor drawn sequentially from the pre-generated pool on first sight
     * of the entity and cached thereafter.
     */
    private static double vary(double base, double relVariance, Entity entity) {
        synchronized (ASSIGNED_VARIANCE) {
            Double factor = ASSIGNED_VARIANCE.get(entity);
            if (factor == null) {
                factor = VARIANCE_POOL[varianceCursor];
                varianceCursor = (varianceCursor + 1) % VARIANCE_POOL.length;
                ASSIGNED_VARIANCE.put(entity, factor);
            }
            return base * (1.0 + relVariance * factor);
        }
    }

    /**
     * Human-readable mass with an SI-ish unit: mg below 1 g, g below 1 kg,
     * kg below 1 t, t (metric tonnes) below 1e6 t, scientific notation in
     * tonnes beyond. The number keeps {@code mass_display_decimals} decimal
     * places (default 2): "120.00 kg", "50.00 g", "4.17 t".
     */
    public static String formatMass(double massKg) {
        if (massKg < 1e-3) {
            return plain(massKg * 1e6, "mg");
        }
        if (massKg < 1.0) {
            return plain(massKg * 1000.0, "g");
        }
        if (massKg < 1e3) {
            return plain(massKg, "kg");
        }
        double tonnes = massKg / 1e3;
        if (tonnes < 1e6) {
            return plain(tonnes, "t");
        }
        return String.format(Locale.ROOT, "%.4g t", tonnes);
    }

    /** Fixed-decimals formatting in the given unit, per the display config. */
    private static String plain(double value, String unit) {
        return String.format(Locale.ROOT, "%." + UnigravConfig.massDisplayDecimals
                + "f %s", value, unit);
    }
}
