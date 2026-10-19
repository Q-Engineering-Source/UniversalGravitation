package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.ImpactDamageHandler;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.network.ImpactReportMessage;

/** One operation's historical collision sample, consumed only after its installed native fall succeeds. */
final class InstalledFreeImpact {
    private final InstalledFreeEndpoint endpoint;
    private final boolean active;
    private final double fallSpeed;
    private final double ceilingSpeed;
    private final double wallSpeed;
    private boolean attempted;

    private InstalledFreeImpact(InstalledFreeEndpoint endpoint, boolean active, double fallSpeed, double ceilingSpeed, double wallSpeed) {
        this.endpoint=endpoint; this.active=active; this.fallSpeed=fallSpeed; this.ceilingSpeed=ceilingSpeed; this.wallSpeed=wallSpeed;
    }

    /** Historical incoming-axis sample; independent from the later joint cone response. */
    static final class HistoricalAxisImpactSample {
        private final InstalledFreeEndpoint endpoint;
        private final CapsuleMovementResolver.Resolved movement;
        private final long xBits, yBits, zBits;
        private final boolean x, y, z;
        private final FreeNativeTail.ActualResponseReceipt g0;
        private HistoricalAxisImpactSample(InstalledFreeEndpoint endpoint,
                CapsuleMovementResolver.Resolved movement, Vec3d incoming, boolean x, boolean y, boolean z,
                FreeNativeTail.ActualResponseReceipt g0) {
            this.endpoint = endpoint; this.movement = movement;
            xBits = Double.doubleToRawLongBits(incoming.x());
            yBits = Double.doubleToRawLongBits(incoming.y());
            zBits = Double.doubleToRawLongBits(incoming.z());
            this.x = x; this.y = y; this.z = z; this.g0 = Objects.requireNonNull(g0);
        }
        boolean matches(InstalledFreeEndpoint exactEndpoint, CapsuleMovementResolver.Resolved exactMovement,
                Vec3d incoming, FreeNativeTail.ActualResponseReceipt exactG0) {
            return endpoint == exactEndpoint && movement == exactMovement
                    && g0 == exactG0 && g0.stage() == FreeNativeTail.ActualResponseReceipt.Stage.G0
                    && xBits == Double.doubleToRawLongBits(incoming.x())
                    && yBits == Double.doubleToRawLongBits(incoming.y())
                    && zBits == Double.doubleToRawLongBits(incoming.z());
        }
    }

    static HistoricalAxisImpactSample sampleHistoricalAxisContacts(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement,
            CapsuleMovementResolver.Resolved.InstalledEvidence evidence, Vec3d incoming,
            FreeNativeTail.ActualResponseReceipt g0) {
        Objects.requireNonNull(endpoint).requireCurrent(); Objects.requireNonNull(incoming);
        Objects.requireNonNull(g0);
        if (!g0.matches(endpoint, movement, FreeNativeTail.ActualResponseReceipt.Stage.G0, incoming, g0.readback()))
            throw new IllegalStateException("historical Free axis sample is not bound to the installed G0 response receipt");
        boolean x = false, y = false, z = false;
        var tx = endpoint.transaction();
        if (movement != null) {
            if (!movement.belongsTo(tx) || evidence == null
                    || !evidence.matches(endpoint))
                throw new IllegalStateException("historical Free impact sample lost its installed movement");
            var work = tx.numeric();
            for (var event : movement.clipEvents()) {
                tx.requireCurrent();
                if (!event.actualTouchAtSegmentEnd(movement)) continue;
                int axis = event.axis();
                double component = axis == 0 ? incoming.x() : axis == 1 ? incoming.y() : incoming.z();
                if (StoredDyadic.of(component, work).signum() * event.outwardSign() >= 0) continue;
                if (axis == 0) x = true; else if (axis == 1) y = true; else z = true;
            }
        }
        endpoint.requireCurrent();
        return new HistoricalAxisImpactSample(endpoint, movement, incoming, x, y, z, g0);
    }

    static InstalledFreeImpact prepare(InstalledFreeEndpoint endpoint, CapsuleMovementResolver.Resolved movement,
            CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
            Vec3d incomingBlocks, HistoricalAxisImpactSample historicalAxis,
            FreeNativeTail.ActualResponseReceipt g0, boolean genuineLanding, boolean grounded) {
        Objects.requireNonNull(endpoint).requireCurrent();
        // Read the same live switch as the authoritative handler; disabled impact adds no eligibility callbacks.
        if(!UnigravConfig.impactDamage)return new InstalledFreeImpact(endpoint,false,0,0,0);
        var work=endpoint.transaction().resources().environmentObservationWork();
        Objects.requireNonNull(incomingBlocks); Objects.requireNonNull(historicalAxis); Objects.requireNonNull(g0);
        var tx=endpoint.transaction();
        if (!historicalAxis.matches(endpoint, movement, incomingBlocks, g0)
                || movement != null && (evidence == null || !evidence.matches(endpoint))
                || !g0.matches(endpoint, movement, FreeNativeTail.ActualResponseReceipt.Stage.G0,
                        incomingBlocks, g0.readback()))
            throw new IllegalStateException("Free impact lost its exact pre-response historical axis sample");
        if(!test(work,()->movement!=null) || !test(work,()->movement.belongsTo(tx)) || !same(work,movement.end(),endpoint.pose()))
            return new InstalledFreeImpact(endpoint,false,0,0,0);
        boolean x=historicalAxis.x, y=historicalAxis.y, z=historicalAxis.z;
        work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
        if(!x&&!y&&!z)return new InstalledFreeImpact(endpoint,false,0,0,0);
        var attitude=endpoint.pose().placement().attitude();
        work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        var incoming=attitude.inverseRotate(incomingBlocks.multiply(1/GravityEntityBinder.TICK_SECONDS));
        work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        var removed=attitude.inverseRotate(new Vec3d(x?incomingBlocks.x():0,y?incomingBlocks.y():0,z?incomingBlocks.z():0)
                .multiply(1/GravityEntityBinder.TICK_SECONDS));
        var previous=tx.accepted().contacts();
        var actor=tx.actor();
        endpoint.requireCurrent();
        boolean water=CardinalNativeBoundary.dispatch(actor,"Free impact water eligibility",actor::isInWater);
        endpoint.requireCurrent();
        boolean gliding=CardinalNativeBoundary.dispatch(actor,"Free impact glide eligibility",actor::isElytraFlying);
        endpoint.requireCurrent();
        boolean fall=test(work,()->genuineLanding) && test(work,()->grounded)
                && test(work,()->!previous.map(PlayerPhysicalSnapshot.Contacts::onGround).orElse(false))
                && test(work,()->incoming.y()<0) && test(work,()->!water);
        boolean horizontal=test(work,()->actor.collidedHorizontally)
                && test(work,()->!previous.map(PlayerPhysicalSnapshot.Contacts::horizontal).orElse(false)) && test(work,()->!gliding);
        endpoint.requireCurrent();
        work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        return new InstalledFreeImpact(endpoint,true,fall?-incoming.y():0,fall?0:Math.max(0,removed.y()),
                horizontal?Math.hypot(removed.x(),removed.z()):0);
    }

    private static boolean same(EnvironmentObservationWork work,Object first,Object second) {
        work.charge(EnvironmentObservationWork.Unit.COMPARISON);
        return first==second;
    }
    private static boolean test(EnvironmentObservationWork work,BooleanSupplier predicate) {
        work.charge(EnvironmentObservationWork.Unit.COMPARISON);
        return predicate.getAsBoolean();
    }

    void consume(Runnable refresh) {
        Objects.requireNonNull(refresh); endpoint.requireCurrent();
        if(!active)return;
        var work=endpoint.transaction().resources().environmentObservationWork();
        if(test(work,()->attempted))throw new IllegalStateException("installed Free impact already attempted");
        // Mark before the first external call: callback failure/reentry cannot replay an attack.
        attempted=true;
        if(test(work,()->fallSpeed>0))apply(ImpactReportMessage.TYPE_FALL,fallSpeed,refresh);
        else if(test(work,()->ceilingSpeed>0))apply(ImpactReportMessage.TYPE_CEILING,ceilingSpeed,refresh);
        if(test(work,()->wallSpeed>0))apply(ImpactReportMessage.TYPE_WALL,wallSpeed,refresh);
    }

    private void apply(int type,double speed,Runnable refresh) {
        refresh.run(); endpoint.requireCurrent();
        var actor=endpoint.transaction().actor();
        CardinalNativeBoundary.dispatch(actor,"Free authoritative impact",()->
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor,type,speed));
        endpoint.requireCurrent(); refresh.run(); endpoint.requireCurrent();
    }
}
