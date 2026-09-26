package wi.unigrav.core;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;

/**
 * Directional, mass-scaled knockback. Vanilla {@code knockBack} is purely
 * horizontal (plus a fixed 0.4 block/tick upward pop), so in zero-G an
 * attacked mob just slides along the XZ plane. This handler cancels the
 * vanilla impulse and instead applies an impulse along the attacker's look
 * vector (pitch included — omni-directional), scaled by the ratio of the
 * target's <em>reference</em> mass to its actual mass:
 *
 * <pre>Δv = strength × 20 m/s × m_ref/m</pre>
 *
 * <p>where {@code m_ref} is what {@link BodyPhysics#estimate} would give the
 * entity at the configured default density. An unmodified mob therefore
 * keeps vanilla knockback magnitude, while a mob heavied via
 * {@code /unigrav mass} barely budges and a lightened one goes flying —
 * mass finally has an on-land gameplay effect (inertia). Applied through
 * {@link GravityEntityBinder#addImpulse}, so it composes with the gravity
 * field instead of fighting it.
 */
public final class KnockbackHandler {

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = false)
    public void onKnockBack(LivingKnockBackEvent event) {
        EntityLivingBase target = event.getEntityLiving();
        Entity attacker = event.getAttacker();
        if (attacker == null || target.world.isRemote) {
            return; // attacker-less knockback keeps vanilla behaviour
        }
        Vec3d look = attacker.getLook(1.0F);
        if (look.lengthSquared() < 1.0e-9) {
            return;
        }
        // Δv in m/s: vanilla strength is blocks/tick along the horizontal.
        double dv = event.getStrength() * GravityEntityBinder.TICKS_PER_SECOND;
        // Impulse for the reference mass, so addImpulse's ÷m yields the
        // inertia scaling above.
        double refMass = BodyPhysics.estimate(target).massKg();
        UnigravMod.entityBinder.addImpulse(target,
                new Vec3(look.x, look.y, look.z).normalize().scale(dv * refMass));
        event.setCanceled(true); // suppress the vanilla horizontal knockback
    }
}
