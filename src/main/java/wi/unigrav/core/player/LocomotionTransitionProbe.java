package wi.unigrav.core.player;

import java.util.Objects;

/** Bounds diagnostic output only; never grants or rejects movement or samples native state. */
public final class LocomotionTransitionProbe {
    private PlayerPhysicalSnapshot.Scope scope;
    private boolean sneak, held, flying, personalG;
    private long lastTick = Long.MIN_VALUE;
    private long lastEmission = Long.MIN_VALUE;
    private int remaining;

    /** One tick of existing observations: 20 samples after an edge, then one per 20 ticks while held. */
    public boolean shouldEmit(PlayerPhysicalSnapshot.Scope current, long tick,
            PlayerPhysicsProtocol.Controls controls, boolean nativeFlying, boolean gActive) {
        boolean changedScope = !Objects.equals(scope, current);
        if (!changedScope && lastTick == tick) return false;
        boolean jumpHeld = controls.mobility().held();
        boolean edge = changedScope || sneak != controls.sneak() || flying != nativeFlying
                || personalG != gActive || held != jumpHeld;
        if (changedScope || tick < lastTick) lastEmission = Long.MIN_VALUE;
        scope = current; lastTick = tick; sneak = controls.sneak(); held = jumpHeld;
        flying = nativeFlying; personalG = gActive;
        if (edge) remaining = 20;
        boolean active = sneak || flying && held;
        boolean summary = active && (lastEmission == Long.MIN_VALUE || tick - lastEmission >= 20);
        if (remaining == 0 && !summary) return false;
        if (remaining > 0) remaining--;
        lastEmission = tick;
        return true;
    }
}
