package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Installed-only request for the native inherited fall chain; neither state nor scalar mints authority. */
public final class InstalledFreeFall {
    private InstalledFreeFall() { }
    private static final ThreadLocal<Request> ACTIVE = new ThreadLocal<>();

    @FunctionalInterface
    public interface InheritedCall {
        void run(double localDelta, boolean grounded, IBlockState state, BlockPos cell);
    }

    public static final class Request {
        private final InstalledFreeEndpoint endpoint;
        private final double localDelta;
        private final boolean grounded;
        private final NativeCollisionProvenance.Entry producer;
        private final Runnable refresh;
        private boolean consumed;
        private boolean completed;
        private boolean actualFallAttempt;
        private boolean actualFallCallbackFailed;
        private float attemptedDistance;
        private Request(InstalledFreeEndpoint endpoint, double localDelta, boolean grounded,
                        NativeCollisionProvenance.Entry producer, Runnable refresh) {
            this.endpoint=endpoint; this.localDelta=localDelta; this.grounded=grounded;
            this.producer=producer; this.refresh=refresh;
        }
        /** Only a genuine request can enter; the target MP and exact installed owner are rechecked. */
        public void invoke(EntityPlayerMP actor, InheritedCall inherited) {
            Objects.requireNonNull(inherited);
            endpoint.requireCurrent();
            if (actor != endpoint.transaction().actor() || consumed)
                throw new IllegalStateException("foreign or already consumed installed fall request");
            refresh.run(); endpoint.requireCurrent(); consumed=true;
            Request previous=ACTIVE.get(); ACTIVE.set(this);
            try {
                CardinalNativeBoundary.check(actor,"inherited Free fall entry");
                CardinalNativeBoundary.charge(actor,"inherited Free fall",EnvironmentObservationWork.Unit.DISPATCH);
                inherited.run(localDelta,grounded,producer.state(),producer.cell());
                checkpoint(actor); completed=true;
            } catch (RuntimeException | Error failure) {
                // The callback may already have applied damage or statistics. Consume only this
                // still-current attempted landing, never replay it or clear a successor's state.
                if (actualFallAttempt && actualFallCallbackFailed) {
                    try {
                        endpoint.requireCurrent();
                        if (actor.fallDistance == attemptedDistance) actor.fallDistance = 0;
                    } catch (RuntimeException | Error cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            } finally {
                if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);
            }
        }
    }

    /** Actual onFallenUpon call boundary; no independent attempt/proof setter is exposed. */
    public static void runFallenUpon(Entity actor, float distance, Runnable original) {
        Objects.requireNonNull(original);
        Request request = ACTIVE.get();
        if (request != null && request.endpoint.transaction().actor() == actor) {
            request.endpoint.requireCurrent();
            if (request.grounded && request.consumed && !request.completed && distance > 0
                    && distance == actor.fallDistance) {
                request.actualFallAttempt = true;
                request.attemptedDistance = actor.fallDistance;
            }
        }
        try {
            original.run();
        } catch (RuntimeException | Error failure) {
            if (request != null && request.endpoint.transaction().actor() == actor && request.actualFallAttempt)
                request.actualFallCallbackFailed = true;
            throw failure;
        }
    }

    /** TEST bridge preserves the original virtual callback body and executes it exactly once. */
    public static void callFallenUpon(Block block, World world, BlockPos cell, Entity actor, float distance) {
        runFallenUpon(actor, distance, () -> block.onFallenUpon(world, cell, actor, distance));
    }

    /** Called after actual native callbacks, before the inherited chain may consume/reset fall state. */
    public static void checkpoint(Entity actor) {
        Request request=ACTIVE.get();
        if(request==null || request.endpoint.transaction().actor()!=actor)return;
        request.endpoint.requireCurrent(); request.refresh.run(); request.endpoint.requireCurrent();
    }

    static void consume(InstalledFreeEndpoint endpoint, CapsuleMovementResolver.Resolved movement,
                        boolean grounded, Optional<NativeCollisionProvenance.Entry> producer, Runnable refresh) {
        endpoint.requireCurrent();
        var actor=endpoint.transaction().actor();
        // Pure attitude/profile work has no translation to accumulate. Existing grounded distance
        // still belongs to the actual final support and must not leak into a later unrelated block.
        if(movement==null && !grounded)return;
        double localDelta=0;
        if(movement!=null)for(var segment:movement.segments()) {
            var delta=segment.end().placement().transport().subtract(segment.tube().start().placement().transport());
            localDelta+=segment.tube().start().placement().attitude().inverseRotate(delta).y();
        }
        if(!Double.isFinite(localDelta))throw new IllegalStateException("non-finite actual Free fall translation");
        refresh.run(); endpoint.requireCurrent();
        if(producer.isPresent()) {
            if(!(actor instanceof InstalledFreeFallBridge bridge))
                throw new IllegalStateException("installed Free fall requires the transformed MP inherited bridge");
            var request=new Request(endpoint,localDelta,grounded,producer.orElseThrow(),refresh);
            bridge.unigrav$installedFall(request);
            if(!request.consumed || !request.completed)throw new IllegalStateException("MP inherited fall bridge did not complete its genuine request");
            endpoint.requireCurrent(); refresh.run();
            return;
        }
        // An aggregate/entity/event support with no unique BLOCK has no material callback authority.
        // This is the adopted ordinary default fall, not a substitute block or a second native chain.
        if(grounded) {
            if(actor.fallDistance>0) {
                float distance=actor.fallDistance;
                CardinalNativeBoundary.dispatch(actor,"Free unattributed ordinary fall",()->actor.fall(distance,1F));
                endpoint.requireCurrent(); refresh.run();
            }
            endpoint.requireCurrent(); actor.fallDistance=0;
        } else if(localDelta<0) {
            endpoint.requireCurrent(); actor.fallDistance=(float)(actor.fallDistance-localDelta);
        }
        endpoint.requireCurrent();
    }
}
