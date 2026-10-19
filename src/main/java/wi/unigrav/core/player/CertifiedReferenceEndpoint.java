package wi.unigrav.core.player;

import java.util.Objects;
import dev.crlhitbox.api.geometry.Aabb;

/** Query geometry certified by this invocation's path, without installation authority. */
final class CertifiedReferenceEndpoint implements CapsuleObservationEndpoint {
    private final FreeOwnerTransaction transaction;
    private final CapsulePathSource lineage;
    private final CapsuleMovementResolver.Resolved movement;
    private final CapsulePlacementMath.Pose pose;

    private CertifiedReferenceEndpoint(FreeOwnerTransaction transaction, CapsulePathSource lineage) {
        this.transaction = transaction;
        this.lineage = Objects.requireNonNull(lineage);
        this.movement = null;
        this.pose = lineage.start();
    }

    private CertifiedReferenceEndpoint(FreeOwnerTransaction transaction,
            CapsuleMovementResolver.Resolved movement) {
        this.transaction = transaction;
        this.lineage = null;
        this.movement = Objects.requireNonNull(movement);
        this.pose = movement.end();
    }

    /** Ordinary multi-capture movement is its own immutable proof, not a synthetic path root. */
    static CertifiedReferenceEndpoint fromMovement(FreeOwnerTransaction transaction,
            CapsuleMovementResolver.Resolved movement) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(movement);
        var endpoint = new CertifiedReferenceEndpoint(transaction, movement);
        endpoint.requireCurrent();
        return endpoint;
    }

    static CertifiedReferenceEndpoint from(FreeOwnerTransaction transaction, CapsulePathSource lineage) {
        Objects.requireNonNull(transaction);
        Objects.requireNonNull(lineage);
        var endpoint = new CertifiedReferenceEndpoint(transaction, lineage);
        endpoint.requireCurrent();
        return endpoint;
    }

    /** Query-only lease for the exact private contained root, never a continuation/movement endpoint. */
    static CardinalRootObservation containedRootObservation(FreeOwnerTransaction transaction,
            CardinalContainedCapsuleSource.Prepared exactContained, CapsulePathSource exactRoot) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(exactContained); Objects.requireNonNull(exactRoot);
        var result = new CardinalRootObservation(transaction, exactContained, null, exactRoot);
        result.requireCurrent();
        return result;
    }

    /** The same query lease serves a contained construction or an independently fitted physical body. */
    static CardinalRootObservation nativeProfileRootObservation(FreeOwnerTransaction transaction,
            NativeCardinalBodyProfile.Fit exactFit, CapsulePathSource exactRoot) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(exactFit); Objects.requireNonNull(exactRoot);
        var result = new CardinalRootObservation(transaction, null, exactFit, exactRoot);
        result.requireCurrent();
        return result;
    }

    static final class CardinalRootObservation {
        private final FreeOwnerTransaction transaction;
        private final CardinalContainedCapsuleSource.Prepared contained;
        private final NativeCardinalBodyProfile.Fit nativeFit;
        private final CapsulePathSource source;
        private final CertifiedReferenceEndpoint endpoint;
        private CardinalRootObservation(FreeOwnerTransaction transaction,
                CardinalContainedCapsuleSource.Prepared contained, NativeCardinalBodyProfile.Fit nativeFit,
                CapsulePathSource source) {
            this.transaction=transaction; this.contained=contained; this.source=source;
            this.nativeFit=nativeFit;
            endpoint=new CertifiedReferenceEndpoint(transaction,source);
        }
        boolean current() {
            transaction.requireCurrent();
            boolean valid=transaction.isPreInstallationPhase()
                    && transaction.accepted().placement().shape() instanceof PlayerBodyShape.Cardinal
                    && (contained != null
                        ? nativeFit == null && contained.pose().placement()==contained.placement()
                            && contained.pose().capsule()==contained.placement().capsule()
                            && contained.containment().matches(transaction.accepted(),contained.placement(),transaction.numeric())
                            && source.isContainedRoot(contained.containment()) && source.start()==contained.pose()
                        : nativeFit != null && source.isNativeProfileRoot(nativeFit)
                            && nativeFit.matches(transaction.accepted(),source.start(),source.capture(),source.window(),transaction.numeric()))
                    && source.actualSource()==transaction.accepted() && source.work()==transaction.numeric()
                    && source.queries()==transaction.queries() && source.accuracy()==FreeOwnerTransaction.ACCURACY
                    && source.valid() && source.current() && endpoint.matches(transaction,source.start())
                    && source.window().equals(source.capture().footprint().requestedBounds());
            transaction.requireCurrent();
            return valid && transaction.isPreInstallationPhase();
        }
        void requireCurrent() {
            if(!current())throw new IllegalStateException("Cardinal root observation context changed");
        }
        boolean matches(CertifiedReferenceEndpoint exactEndpoint,WorldCollisionCandidates.Snapshot exactCapture,Aabb exactQueryWindow) {
            return endpoint==exactEndpoint && source.capture()==exactCapture && source.window().equals(exactQueryWindow) && current();
        }
        CertifiedReferenceEndpoint endpoint(){return endpoint;}
        CapsulePlacementMath.Pose pose(){return source.start();}
        WorldCollisionCandidates.Snapshot capture(){return source.capture();}
        Aabb queryWindow(){return source.window();}
    }

    public boolean current() {
        transaction.requireCurrent();
        boolean current = transaction.isPreInstallationPhase() && (lineage != null
                ? movement == null && lineage.actualSource() == transaction.accepted()
                    && lineage.work() == transaction.numeric() && lineage.queries() == transaction.queries()
                    && lineage.valid() && lineage.current() && lineage.start() == pose
                : movement != null && movement.belongsTo(transaction) && movement.source() == null
                    && transaction.accepted().placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    && pose.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    && movement.end() == pose && movement.matches(transaction));
        transaction.requireCurrent();
        return current && transaction.isPreInstallationPhase();
    }

    public void requireCurrent() {
        if (!current()) throw new IllegalStateException("certified reference endpoint context changed");
    }

    boolean matches(FreeOwnerTransaction exactTransaction, CapsulePlacementMath.Pose exactPose) {
        return transaction == exactTransaction && pose == exactPose && current();
    }

    public FreeOwnerTransaction transaction() { return transaction; }
    public PlayerPhysicalSnapshot owner() { return transaction.accepted(); }
    public CapsulePlacementMath.Pose pose() { return pose; }
}
