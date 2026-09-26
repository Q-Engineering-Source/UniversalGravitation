package wi.unigrav.core;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.config.EntityBindingsLoader;

/**
 * The global gravity takeover: one catch-all REPLACE predicate binding that
 * routes every entity's gravity through the gravity field. Registered at
 * server start after {@link EntityBindingsLoader}, so explicit
 * {@code entities.json} rules always win (already-bound entities are never
 * re-bound), and only the gaps are caught.
 *
 * <p>Exclusions, checked by the predicate:
 * <ul>
 *   <li>types with a {@code "VANILLA"} rule in entities.json;
 *   <li>registry names in {@link UnigravConfig#takeoverExclusions}.
 * </ul>
 * Players are bound like everything else, but a per-tick filter suppresses
 * the &#916;v while a player is flying or in spectator mode (mid-air
 * abilities that must not fight the field). Creative players on foot get
 * normal gravity; their personal zero-G toggle is the client-side G key
 * handled by {@code ClientGravityTicker}.
 */
public final class GlobalGravityTakeover {

    /** Per-tick suppression for spectator/flying players. */
    private static final Predicate<Entity> PLAYER_TICK_FILTER = entity ->
            !(entity instanceof EntityPlayer player)
                    || !(player.isSpectator() || player.capabilities.isFlying);

    private GravityEntityBinder.Registration registration;

    /** Registers the catch-all binding if the takeover is enabled in the config. */
    public void enable(EntityBindingsLoader bindingsLoader) {
        if (!UnigravConfig.globalTakeover) {
            UnigravMod.LOGGER.info("Global gravity takeover disabled by config");
            return;
        }
        List<Class<? extends Entity>> vanillaExempt = bindingsLoader.vanillaExemptClasses();
        Set<String> exclusions = Set.of(UnigravConfig.takeoverExclusions);
        Predicate<Entity> predicate = entity -> {
            if (vanillaExempt.contains(entity.getClass())) {
                return false;
            }
            ResourceLocation key = EntityList.getKey(entity);
            if (key != null && exclusions.contains(key.toString())) {
                return false;
            }
            // Species flagged "gravity": false in mass_presets.json (vanilla
            // flyers: ghast, blaze, …) keep their own movement physics.
            return BodyPhysics.affectedByGravity(entity);
        };
        registration = UnigravMod.entityBinder.bindPredicate(
                predicate, GravityMode.REPLACE, PLAYER_TICK_FILTER);
        UnigravMod.LOGGER.info(
                "Global gravity takeover active: all entities REPLACE-bound "
                        + "({} VANILLA exemption(s), {} config exclusion(s))",
                vanillaExempt.size(), exclusions.size());
    }

    /** Closes the takeover binding, restoring noGravity on everything it bound. */
    public void disable() {
        if (registration != null) {
            registration.close();
            registration = null;
        }
    }
}
