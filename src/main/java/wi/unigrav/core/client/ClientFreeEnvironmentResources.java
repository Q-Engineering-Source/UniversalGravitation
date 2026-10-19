package wi.unigrav.core.client;

import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.world.World;
import wi.unigrav.core.player.FreeTickResources;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** One installed scope keeps its ledger across states; correction requires retirement and negotiation. */
final class ClientFreeEnvironmentResources {
    private final Object owner;
    private final World world;
    private final PlayerPhysicalSnapshot.Scope scope;
    private long correction;
    private FreeTickResources acquired;
    private final FreeTickResources.Store store;

    ClientFreeEnvironmentResources(Object owner, World world, PlayerPhysicalSnapshot.Scope scope, long correction, LongSupplier simulationTicks) {
        this.store = new FreeTickResources.Store(simulationTicks);
        this.owner = Objects.requireNonNull(owner);
        this.world = Objects.requireNonNull(world);
        this.scope = Objects.requireNonNull(scope);
        this.correction = correction;
    }

    FreeTickResources acquire(World expectedWorld, PlayerPhysicalSnapshot.Scope expectedScope, long expectedCorrection) {
        if (world != expectedWorld || !scope.equals(expectedScope) || correction != expectedCorrection) {
            invalidate();
            throw new IllegalStateException("Installed Free mirror resource lifetime changed; recovery required");
        }
        acquired = store.acquire(owner, world, scope, correction);
        return acquired;
    }

    FreeTickResources.Store.NativeCorrectionTransfer prepareCorrection(long nextCorrection) {
        return store.prepareClientNativeCorrection(acquired, nextCorrection);
    }

    void completeCorrection(FreeTickResources.Store.NativeCorrectionTransfer transfer, long nextCorrection, Runnable requireCurrent) {
        var completion = store.captureClientCompletion(transfer);
        requireCurrent.run();
        acquired = store.completeClientNativeCorrection(transfer, completion);
        correction = nextCorrection;
    }

    FreeTickResources.Store.NativeResumeTransfer prepareResume() {
        return store.prepareClientNativeResume(acquired);
    }

    void completeResume(FreeTickResources.Store.NativeResumeTransfer transfer, Runnable requireCurrent) {
        var completion = store.captureClientCompletion(transfer);
        requireCurrent.run();
        acquired = store.completeClientNativeResume(transfer, completion);
        correction = -1;
    }

    void invalidate() { store.invalidate(); }
}
