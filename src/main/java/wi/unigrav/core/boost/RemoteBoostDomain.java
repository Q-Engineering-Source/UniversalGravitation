package wi.unigrav.core.boost;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Eligibility boundary for remote Creative custody.
 *
 * <p>This deliberately does not inspect terrain, materials, entities, loaded chunks, vanilla
 * flight state, or gravity. Native {@code EntityPlayerMP.move} remains the sole collision pass;
 * the separately bounded loaded-column clipper protects the native query envelope.
 */
final class RemoteBoostDomain {
    enum Phase { CURRENT, FLAGS }
    enum Subreason {
        NONE, CONFIG_DISABLED, NON_DEDICATED, REMOTE_WORLD, DEAD, SPECTATOR, NOT_CREATIVE,
        ABSENT_ACTOR
    }

    record Verdict(BoostProtocol.Reason reason, Phase phase, Subreason subreason) {
        static Verdict pass(Phase phase) {
            return new Verdict(BoostProtocol.Reason.NONE, phase, Subreason.NONE);
        }
    }

    private RemoteBoostDomain() {
    }

    static Verdict current(EntityPlayerMP player) {
        Subreason reason = firstFlag(player);
        return reason == Subreason.NONE ? Verdict.pass(Phase.CURRENT)
                : new Verdict(BoostProtocol.Reason.UNSUPPORTED_STATE, Phase.FLAGS, reason);
    }

    private static Subreason firstFlag(EntityPlayerMP player) {
        if (player == null) return Subreason.ABSENT_ACTOR;
        if (player.world == null || player.world.isRemote) return Subreason.REMOTE_WORLD;
        if (player.isDead) return Subreason.DEAD;
        if (player.isSpectator()) return Subreason.SPECTATOR;
        if (!player.interactionManager.isCreative()) return Subreason.NOT_CREATIVE;
        return Subreason.NONE;
    }
}
