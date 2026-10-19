package wi.unigrav.core;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;

/**
 * Mod configuration ({@code config/unigrav.cfg}). All values are plain
 * statics, synced by Forge's {@link Config} annotation processor.
 */
@Config(modid = Reference.MOD_ID, name = Reference.MOD_ID)
public final class UnigravConfig {
    public enum NativeBodyProfileEnvironment { UNKNOWN, VANILLA_UNSCALED }

    @Config.Comment({"Explicitly verified native body-size compatibility environment; UNKNOWN never infers unscaled body length.",
            "VANILLA_UNSCALED is for a verified vanilla-size environment without an external size provider; Aqua uses its own producer."})
    @Config.Name("native_body_profile_environment")
    public static NativeBodyProfileEnvironment nativeBodyProfileEnvironment = NativeBodyProfileEnvironment.UNKNOWN;

    @Config.Comment("Fresh owner simulation ticks in one Space press before detach; nominally 1 s at 20 TPS, independent of jump cooldown.")
    @Config.Name("long_space_detach_ticks")
    @Config.RangeInt(min = 1)
    public static int longSpaceDetachTicks = 20;

    @Config.Comment("Client-side continuous G hold in milliseconds before opening the pose backpack debug GUI.")
    @Config.Name("pose_backpack_gui_hold_millis")
    @Config.RangeInt(min = 1)
    public static int poseBackpackGuiHoldMillis = 3000;

    @Config.Comment("Bounded hover braking in blocks/tick^2; 0.08 equals 32 m/s^2 at 20 ticks/s.")
    @Config.Name("hover_braking_blocks_per_tick_squared")
    @Config.RangeDouble(min = 0)
    public static double hoverBrakingBlocksPerTickSquared = .08;

    @Config.Comment({
            "Default downward strength of the world gravity field, in m/s^2,",
            "used when a world has no persisted setting yet (first load).",
            "1G = " + "32 m/s^2 matches the vanilla player/living gravity",
            "(0.08 block/tick^2).",
    })
    @Config.Name("world_gravity_strength")
    @Config.RangeDouble(min = 0.0)
    public static double worldGravityStrength = 32.0;

    @Config.Comment({
            "Default body density (kg/m^3) used to estimate a bound entity's",
            "mass from its bounding-box volume when no explicit BodyPhysics",
            "is given. Default 1140 matches a ~120 kg sheep; water_sink_bias",
            "below is calibrated against this so default bodies still sink",
            "at 0.25 g like vanilla. Lower values float, higher sink faster.",
    })
    @Config.Name("default_body_density")
    @Config.RangeDouble(min = 1.0)
    public static double defaultBodyDensity = 1140.0;

    @Config.Comment({
            "Global gravity takeover: automatically bind every entity in",
            "REPLACE mode so the gravity field fully replaces vanilla gravity.",
            "Explicit entities.json rules take precedence; the exclusion list",
            "below is honored. When disabled, only explicit bindings apply.",
    })
    @Config.Name("global_takeover")
    public static boolean globalTakeover = true;

    @Config.Comment("When personal G is active, suppress force from ordinary gravity floors.")
    @Config.Name("personal_g_suppresses_floor_gravity")
    public static boolean personalGSuppressesFloorGravity = false;

    @Config.Comment("When personal G is active, suppress standing references from ordinary gravity floors.")
    @Config.Name("personal_g_suppresses_floor_reference")
    public static boolean personalGSuppressesFloorReference = false;

    @Config.Comment({
            "Entity registry names (e.g. \"minecraft:boat\") excluded from the",
            "global takeover; they keep vanilla gravity. VANILLA rules in",
            "entities.json are the per-type alternative.",
    })
    @Config.Name("takeover_exclusions")
    public static String[] takeoverExclusions = {};

    @Config.Comment({
            "Safety clamp on the total speed (m/s) of any entity bound to the",
            "gravity field, applied after each tick's delta-v. 0 disables.",
            "Rationale: vanilla Entity.move() sweeps the collision box",
            "block-by-block, so its cost is proportional to speed; a",
            "near-singular gravity source (e.g. an unsoftened point mass)",
            "could otherwise accelerate an entity to thousands of m/s and a",
            "single move() would sweep hundreds of thousands of blocks,",
            "effectively hanging the tick loop (observed live). 500 m/s is a",
            "25-block sweep per tick — cheap, and far beyond normal gameplay",
            "speeds. This is a runtime safety clamp, not a physical model.",
    })
    @Config.Name("max_entity_speed")
    @Config.RangeDouble(min = 0.0)
    public static double maxEntitySpeed = 500.0;

    @Config.Comment("Opt in to experimental, server-permitted Creative personal-G remote movement.")
    @Config.Name("remote_creative_boost")
    public static boolean remoteCreativeBoost = false;

    @Config.Comment("Finite remote movement safety ceiling in m/s; no more than 500. Not acceleration.")
    @Config.Name("remote_boost_maximum_speed")
    @Config.RangeDouble(min = 1.0, max = 500.0)
    public static double remoteBoostMaximumSpeed = 500.0;

    @Config.Comment({
            "Apply a vanilla-style ground friction (block slipperiness x 0.91)",
            "to the horizontal motion of bound entities standing on the",
            "ground, after each tick's delta-v. Our delta-v lands at END",
            "phase, after vanilla's own friction ran, so without this bound",
            "entities slowly creep/drift across the ground.",
    })
    @Config.Name("ground_friction")
    public static boolean groundFriction = true;

    @Config.Comment({
            "Pause pathfinding (navigator path cleared each tick) for",
            "airborne living entities in a weightless field: ground",
            "navigators spin in place trying to walk on air. Look/idle AI",
            "and knockback drift are NOT paused. Species flagged",
            "\"zeroGPathing\": true in mass_presets.json (flyers) are exempt.",
    })
    @Config.Name("zerog_pause_ai")
    public static boolean zeroGPauseAi = true;

    @Config.Comment({
            "Extra downward fraction of g applied to bodies in water on top",
            "of the density buoyancy term, reproducing vanilla's slow water",
            "sinking (vanilla sinks at 0.02 block/tick^2 = 0.25 of its land",
            "gravity 0.08). Calibrated so a body at default_body_density",
            "(1140) sinks at 0.25 g: bias = 0.25 + 1000/1140 - 1 ~ 0.13.",
            "Bodies with density < 1000/(1+bias) ~ 885 float, denser sink.",
            "Swimming up (space) still wins: vanilla adds +0.8 m/s per tick.",
    })
    @Config.Name("water_sink_bias")
    @Config.RangeDouble(min = 0.0, max = 1.0)
    public static double waterSinkBias = 0.13;

    @Config.Comment({
            "Decimal places used when displaying masses (HUD, entity labels,",
            "command feedback): e.g. 2 shows \"120.00 kg\", \"4.17 t\".",
    })
    @Config.Name("mass_display_decimals")
    @Config.RangeInt(min = 0, max = 6)
    public static int massDisplayDecimals = 2;

    @Config.Comment({
            "Mass (kg) at which a bound entity becomes a real gravity source",
            "(softened point mass, mu = G*mass) pulling nearby entities.",
            "Uses the real G = 6.674e-11: 1e12 kg -> mu ~ 67 m^3/s^2, ~2.7",
            "m/s^2 at 5 m. Default 1e12; set via /unigrav mass <kg>.",
    })
    @Config.Name("entity_gravity_threshold")
    @Config.RangeDouble(min = 1.0)
    public static double entityGravityThreshold = 1.0e12;

    @Config.Comment({
            "Velocity-based impact damage takeover: bound living entities",
            "take damage from hard collisions in ANY direction (landing,",
            "ceiling, wall) based on impact speed, replacing vanilla's",
            "distance-based fall damage (which charges full damage for slow",
            "low-G descents and nothing for sideways falls).",
    })
    @Config.Name("impact_damage")
    public static boolean impactDamage = true;

    @Config.Comment({
            "Impact speed (m/s) below which no damage is taken. 14 m/s",
            "matches the speed of a vanilla 3-block fall (the vanilla",
            "damage-free height).",
    })
    @Config.Name("impact_safe_speed")
    @Config.RangeDouble(min = 0.0)
    public static double impactSafeSpeed = 14.0;

    @Config.Comment({
            "Impact damage points = scale * (v^2 - safe^2). With the default",
            "0.021: a 4-block fall ~ 1 pt, 10 blocks ~ 7 pt, 23 blocks",
            "~ 20 pt (lethal) — the vanilla fall curve.",
    })
    @Config.Name("impact_damage_scale")
    @Config.RangeDouble(min = 0.0)
    public static double impactDamageScale = 0.021;

    @Config.Comment({
            "Entity-entity ramming damage: overlapping entities with high",
            "relative speed damage each other, weighted by the other side's",
            "mass share (heavy wrecking light). Uses impact_safe_speed and",
            "impact_damage_scale.",
    })
    @Config.Name("collision_damage")
    public static boolean collisionDamage = true;

    @Config.Comment({
            "Minimum relative speed (m/s) for entity-entity ramming damage.",
            "6 m/s is just above sprint speed, so brushing past mobs while",
            "walking/running is harmless.",
    })
    @Config.Name("collision_min_speed")
    @Config.RangeDouble(min = 0.0)
    public static double collisionMinSpeed = 6.0;

    @Config.Comment({
            "Let impact/ramming damage hurt creative-mode players (vanilla",
            "creative is fully damage-immune). Default false = vanilla",
            "semantics; enable for testing zero-G flight impacts without",
            "switching to survival.",
    })
    @Config.Name("impact_damage_creative")
    public static boolean impactDamageCreative = false;

    @Config.Comment({
            "Enable Revo UI's full pitch/yaw/roll camera while the local",
            "player is in personal or natural zero gravity. This is a",
            "client presentation feature; ordinary non-zero gravity keeps",
            "the vanilla camera. Requires Revo UI Flight API v9 or newer.",
    })
    @Config.Name("six_dof_camera")
    public static boolean sixDofCamera = true;

    @Config.Comment({
            "Show UG's read-only 360-degree attitude, speed and control-gear HUD",
            "while the six-DOF camera is active. Does not enable UIE's built-in",
            "HUD hiding policies or change camera, input, thrust or roll inertia.",
    })
    @Config.Name("six_dof_hud")
    public static boolean sixDofHud = true;

    @Config.Comment({
            "Maximum field acceleration (m/s^2) treated as natural zero-G",
            "for the Revo UI six-degree-of-freedom camera. Personal creative",
            "zero-G always activates the camera regardless of field strength.",
    })
    @Config.Name("six_dof_camera_gravity_threshold")
    @Config.RangeDouble(min = 0.0)
    public static double sixDofCameraGravityThreshold = 0.5;

    @Config.Comment({
            "Free-drift acceleration (m/s^2) for the local creative player",
            "while personal G zero-gravity is active. WASD uses camera-local",
            "forward/right axes; Space and Shift use camera-local up/down.",
    })
    @Config.Name("six_dof_drift_acceleration")
    @Config.RangeDouble(min = 0.0)
    public static double sixDofDriftAcceleration = 8.0;

    @Config.Comment({
            "Maximum speed (m/s) that free-drift controls may accelerate",
            "towards. Reverse input may still brake at or above this speed.",
            "Set to 0 to disable the gameplay speed limit; the global entity",
            "safety clamp remains independent.",
    })
    @Config.Name("six_dof_drift_speed_limit")
    @Config.RangeDouble(min = 0.0)
    public static double sixDofDriftSpeedLimit = 20.0;

    private UnigravConfig() {
    }

    /** Syncs the config file back into the statics when it changes in-game. */
    @Mod.EventBusSubscriber(modid = Reference.MOD_ID)
    public static final class SyncHandler {

        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(Reference.MOD_ID)) {
                ConfigManager.sync(Reference.MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
