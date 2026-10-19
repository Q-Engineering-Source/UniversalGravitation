package wi.unigrav.core.client;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.World;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** Persistent partial-install fence containing weak actor/world identities and diagnostic values only. */
final class BrokenActorInstallation {
    private final WeakReference<EntityPlayer> actor;
    private WeakReference<World> world;
    private long attemptStamp;
    private final UUID diagnosticId;
    private boolean active = true;
    private final List<ScopeGeneration> scopeGenerations = new ArrayList<>();

    BrokenActorInstallation(EntityPlayer actor, World world, long attemptStamp, UUID diagnosticId) {
        if (actor == null || world == null || attemptStamp <= 0 || diagnosticId == null)
            throw new IllegalArgumentException("Broken installation needs an exact actor/world attempt identity");
        this.actor = new WeakReference<>(actor);
        this.world = new WeakReference<>(world);
        this.attemptStamp = attemptStamp;
        this.diagnosticId = diagnosticId;
    }

    boolean matchesActor(EntityPlayer exactActor) {
        return active && matchesActorIdentity(exactActor);
    }

    boolean matchesActorIdentity(EntityPlayer exactActor) {
        return exactActor != null && actor.get() == exactActor;
    }

    boolean matchesAttempt(EntityPlayer exactActor, World exactWorld, long expectedAttemptStamp) {
        return matchesActor(exactActor) && world.get() == exactWorld && attemptStamp == expectedAttemptStamp;
    }

    void beginAttempt(World exactWorld, long nextAttemptStamp) {
        if (exactWorld == null || nextAttemptStamp <= attemptStamp)
            throw new IllegalArgumentException("Broken installation attempt must advance with its exact world");
        world = new WeakReference<>(exactWorld);
        attemptStamp = nextAttemptStamp;
        active = true;
    }

    /** Retained across Session reset and successful recovery; the same transport cannot reuse an installed epoch. */
    void recordScope(NetworkManager exactManager, PlayerPhysicalSnapshot.Scope scope) {
        EntityPlayer exactActor = actor.get();
        if (exactManager == null || scope == null || exactActor == null
                || !exactActor.getUniqueID().equals(scope.playerId()))
            throw new IllegalArgumentException("Installation generation needs its actual actor and manager");
        scopeGenerations.removeIf(generation -> generation.manager.get() == null);
        for (ScopeGeneration generation : scopeGenerations) {
            if (generation.manager.get() == exactManager) {
                generation.epoch = Math.max(generation.epoch, scope.worldEpoch());
                return;
            }
        }
        scopeGenerations.add(new ScopeGeneration(exactManager, scope.worldEpoch()));
    }

    boolean allowsOffer(NetworkManager exactManager, PlayerPhysicalSnapshot.Scope scope) {
        if (exactManager == null || scope == null) throw new IllegalArgumentException("Offer needs its actual manager and scope");
        scopeGenerations.removeIf(generation -> generation.manager.get() == null);
        for (ScopeGeneration generation : scopeGenerations) {
            if (generation.manager.get() == exactManager && scope.worldEpoch() <= generation.epoch) return false;
        }
        return true;
    }

    void complete() {
        active = false;
    }

    boolean collected() {
        // The old world is needed only to decide whether deferred cleanup still targets this
        // physical lifetime. The actor identity alone keeps the writer fence alive across world
        // reuse until a complete new installation explicitly clears it.
        return actor.get() == null;
    }

    long attemptStamp() { return attemptStamp; }
    UUID diagnosticId() { return diagnosticId; }

    private static final class ScopeGeneration {
        private final WeakReference<NetworkManager> manager;
        private long epoch;

        private ScopeGeneration(NetworkManager manager, long epoch) {
            this.manager = new WeakReference<>(manager);
            this.epoch = epoch;
        }
    }
}
