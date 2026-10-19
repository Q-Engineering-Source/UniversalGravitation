package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;

/** One installed journal's genuine native statistics call; a lexical rejection is never authority. */
public final class InstalledMovementStat {
    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();
    private InstalledMovementStat() { }
    private static final class Attempt {
        final InstalledFreeEndpoint endpoint;
        boolean entered;
        boolean inProgress;
        RuntimeException failure;
        Attempt(InstalledFreeEndpoint endpoint) { this.endpoint = endpoint; }
        void check() {
            if (failure != null) throw failure;
            endpoint.requireCurrent();
        }
    }

    static void consume(InstalledFreeEndpoint endpoint, Vec3d delta) {
        endpoint.requireCurrent();
        var actor = endpoint.transaction().actor();
        var previous = ACTIVE.get();
        if (previous != null && previous.endpoint.transaction().actor() == actor)
            throw reject(previous);
        try (var call = CardinalNativeBoundary.enter(actor, "installed movement statistics")) {
            if (!call.active()) throw new IllegalStateException("installed statistics require the active owner native scope");
            endpoint.requireCurrent();
            endpoint.consumeMovementStatAttempt();
            var attempt = new Attempt(endpoint);
            ACTIVE.set(attempt);
            try {
                // Keep authority active until native callsites explicitly enter external callbacks.
                call.internal("addMovementStat", () -> actor.addMovementStat(delta.x(), delta.y(), delta.z()));
                attempt.check();
                if (!attempt.entered) throw new IllegalStateException("native movement statistics guard was not transformed");
            } finally {
                if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            }
        }
    }

    private static RuntimeException reject(Attempt attempt) {
        if (attempt.failure == null)
            attempt.failure = new IllegalStateException("reentrant installed movement statistics have no independent operation authority");
        return attempt.failure;
    }

    /** The transformed native entry checks lexical reentry before consulting suspended UG authority. */
    public static NativeCall enter(Entity actor) {
        var attempt = ACTIVE.get();
        if (attempt != null && attempt.endpoint.transaction().actor() == actor) {
            if (attempt.entered || attempt.inProgress) throw reject(attempt);
            attempt.check(); attempt.entered = true; attempt.inProgress = true;
        } else attempt = null;
        return new NativeCall(actor, attempt);
    }

    public static final class NativeCall implements AutoCloseable {
        private final Attempt attempt;
        private final CardinalNativeBoundary.Invocation invocation;
        private NativeCall(Entity actor, Attempt attempt) {
            this.attempt = attempt;
            invocation = CardinalNativeBoundary.enter(actor, "EntityPlayer.addMovementStat");
        }
        public void check() {
            if (attempt != null) attempt.check();
            invocation.check();
        }
        public <T> T external(String stage, Supplier<T> original) {
            check();
            T result = invocation.external(stage, original);
            check();
            return result;
        }
        public void external(String stage, Runnable original) {
            external(stage, () -> { original.run(); return null; });
        }
        @Override public void close() {
            try { invocation.close(); }
            finally { if (attempt != null) attempt.inProgress = false; }
        }
    }
}
