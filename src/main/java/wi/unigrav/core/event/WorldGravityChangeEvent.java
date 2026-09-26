package wi.unigrav.core.event;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

import wi.unigrav.api.math.Vec3;

/**
 * Fired when the per-world uniform gravity is about to change (or has
 * changed) through the unified entry point
 * {@code WorldGravityManager.setWorldGravity} — used by the
 * {@code /unigrav world} command and available to downstream mods.
 *
 * <p>This is the designated hook for integrations: a future CraftTweaker
 * bridge (or any downstream mod) listens for {@link Pre} to veto or rewrite
 * world-gravity changes, and for {@link Post} to react to them.
 */
public abstract class WorldGravityChangeEvent extends Event {

    private final Vec3 previousAcceleration;
    private final boolean previousEnabled;

    WorldGravityChangeEvent(Vec3 previousAcceleration, boolean previousEnabled) {
        this.previousAcceleration = previousAcceleration;
        this.previousEnabled = previousEnabled;
    }

    /** The acceleration in effect before the change, in m/s². */
    public Vec3 getPreviousAcceleration() {
        return previousAcceleration;
    }

    public boolean wasEnabled() {
        return previousEnabled;
    }

    /**
     * Fired before the change is applied. Cancel to veto it entirely;
     * mutate the new value via {@link #setNewAcceleration} /
     * {@link #setNewEnabled} to rewrite it.
     */
    @Cancelable
    public static final class Pre extends WorldGravityChangeEvent {

        private Vec3 newAcceleration;
        private boolean newEnabled;

        public Pre(Vec3 previousAcceleration, boolean previousEnabled,
                   Vec3 newAcceleration, boolean newEnabled) {
            super(previousAcceleration, previousEnabled);
            this.newAcceleration = newAcceleration;
            this.newEnabled = newEnabled;
        }

        /** The requested acceleration, in m/s²; mutable. */
        public Vec3 getNewAcceleration() {
            return newAcceleration;
        }

        public void setNewAcceleration(Vec3 newAcceleration) {
            this.newAcceleration = newAcceleration;
        }

        public boolean isNewEnabled() {
            return newEnabled;
        }

        public void setNewEnabled(boolean newEnabled) {
            this.newEnabled = newEnabled;
        }
    }

    /** Fired after the change has been applied and persisted. */
    public static final class Post extends WorldGravityChangeEvent {

        private final Vec3 newAcceleration;
        private final boolean newEnabled;

        public Post(Vec3 previousAcceleration, boolean previousEnabled,
                    Vec3 newAcceleration, boolean newEnabled) {
            super(previousAcceleration, previousEnabled);
            this.newAcceleration = newAcceleration;
            this.newEnabled = newEnabled;
        }

        /** The acceleration now in effect, in m/s². */
        public Vec3 getNewAcceleration() {
            return newAcceleration;
        }

        public boolean isNewEnabled() {
            return newEnabled;
        }
    }
}
