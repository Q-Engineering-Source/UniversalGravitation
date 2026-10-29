package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Loader;
import wi.unigrav.core.UnigravConfig;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;

/** Private first-Free admission: actual native profile observation and full target fit, never containment. */
final class NativeCardinalBodyProfile {
    private NativeCardinalBodyProfile() { }

    /** A value-current sample, not an event generation or installation capability. */
    record Value(EntityPlayerMP actor, NativeBodyProfileProducer producer,
            NativeBodyProfileProducer.Profile profile,
            UnigravConfig.NativeBodyProfileEnvironment environment) {
        PlayerBodyShape.CollisionProfile physical() { return PlayerBodyShape.CollisionProfile.fromNative(profile); }
        boolean current() {
            return actor instanceof NativeBodyProfileProducer actual && actual == producer
                    && qualified(profile, environment)
                    && producer.unigrav$nativeBodyProfile().filter(profile::equals).isPresent();
        }
    }

    static Optional<Value> sample(EntityPlayerMP actor) {
        if (!(actor instanceof NativeBodyProfileProducer producer)) return Optional.empty();
        var observed = producer.unigrav$nativeBodyProfile();
        if (observed.isEmpty()) return Optional.empty();
        var profile = observed.orElseThrow();
        var environment = UnigravConfig.nativeBodyProfileEnvironment;
        if (!qualified(profile, environment)) return Optional.empty();
        return Optional.of(new Value(actor, producer, profile, environment));
    }

    private static boolean qualified(NativeBodyProfileProducer.Profile profile,
            UnigravConfig.NativeBodyProfileEnvironment environment) {
        var indexedMods = Loader.instance().getIndexedModList();
        return switch (profile.source()) {
            case AQUA_STANDING -> indexedMods.containsKey("aquaacrobatics");
            case VANILLA_CONSTRUCTOR -> environment == UnigravConfig.NativeBodyProfileEnvironment.VANILLA_UNSCALED
                    && environment == UnigravConfig.nativeBodyProfileEnvironment
                    && !indexedMods.containsKey("aquaacrobatics") && !indexedMods.containsKey("artemislib");
        };
    }

    static final class Observation {
        private final EntityPlayerMP actor;
        private final PlayerPhysicalSnapshot accepted;
        private final NativeBodyProfileProducer producer;
        private final NativeBodyProfileProducer.Profile profile;
        private final UnigravConfig.NativeBodyProfileEnvironment environment;
        private final WorldCollisionCandidates.ActorGeometry geometry;
        private final double eye;
        private final PlayerPhysicsServer.PendingCardinalPose pending;
        private Observation(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
                NativeBodyProfileProducer producer, NativeBodyProfileProducer.Profile profile,
                WorldCollisionCandidates.ActorGeometry geometry, double eye,
                UnigravConfig.NativeBodyProfileEnvironment environment,
                PlayerPhysicsServer.PendingCardinalPose pending) {
            this.actor = actor; this.accepted = accepted; this.producer = producer; this.profile = profile;
            this.geometry = geometry; this.eye = eye;
            this.environment = environment;
            this.pending = pending;
        }
        PlayerBodyShape.CollisionProfile physical() { return PlayerBodyShape.CollisionProfile.fromNative(profile); }
        boolean profileCurrent() {
            return actor instanceof NativeBodyProfileProducer actual && actual == producer
                    && qualified(profile, environment)
                    && producer.unigrav$nativeBodyProfile().filter(profile::equals).isPresent();
        }
        boolean matches(PlayerPhysicalSnapshot exactAccepted) {
            if (exactAccepted != accepted || !(actor instanceof NativeBodyProfileProducer actual)
                    || actual != producer || !qualified(profile, environment)) return false;
            if (profile.source() == NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR
                    && environment != UnigravConfig.nativeBodyProfileEnvironment) return false;
            var observed = producer.unigrav$nativeBodyProfile();
            return observed.isPresent() && profile.equals(observed.orElseThrow())
                    && geometry.equals(WorldCollisionCandidates.ActorGeometry.read(actor))
                    && StoredRealizationLimits.sameRaw(eye, actor.getEyeHeight())
                    && (pending == null || pending.matchesSnapshot(actor, accepted, (float) eye));
        }
    }

    static Observation observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted) {
        return observe(actor, accepted, null);
    }

    static Observation observe(FreeOwnerTransaction transaction) {
        transaction.requireCurrent();
        var observation = observe(transaction.actor(), transaction.accepted(), transaction.pendingCardinalPose());
        transaction.requireCurrent();
        return observation;
    }

    private static Observation observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsServer.PendingCardinalPose pending) {
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal))
            throw new IllegalArgumentException("native profile source must be actual Cardinal");
        if (!(actor instanceof NativeBodyProfileProducer producer))
            throw new IllegalStateException("native body profile producer unavailable");
        var geometry = WorldCollisionCandidates.ActorGeometry.read(actor);
        double eye = actor.getEyeHeight();
        var profile = producer.unigrav$nativeBodyProfile().orElseThrow(
                () -> new IllegalStateException("native body profile producer unavailable"));
        var environment = UnigravConfig.nativeBodyProfileEnvironment;
        if (!qualified(profile, environment))
            throw new IllegalStateException("native body profile environment is not qualified for its producer");
        var raw = accepted.placement().shape().dimensions();
        if (!geometry.position().equals(accepted.placement().transport())
                || !geometry.bounds().equals(accepted.placement().bounds())
                || !StoredRealizationLimits.sameRaw((double) geometry.width(), raw.width())
                || !StoredRealizationLimits.sameRaw((double) geometry.height(), raw.height())
                || !(StoredRealizationLimits.sameRaw(eye, raw.eyeHeight())
                    || pending != null && pending.matchesSnapshot(actor, accepted, (float) eye)))
            throw new IllegalStateException("native profile observation does not match exact accepted Cardinal anchor");
        var result = new Observation(actor, accepted, producer, profile, geometry, eye, environment, pending);
        if (!result.matches(accepted)) throw new IllegalStateException("native profile changed during observation");
        return result;
    }

    sealed interface FitResult permits Fit, TargetOccupied { }

    /** Definite overlap is an observation, never permission to install the Free target. */
    static final class TargetOccupied implements FitResult {
        private final Fit source;
        private final long ledgerSequence;
        private TargetOccupied(Fit source) {
            this.source = source;
            ledgerSequence = source.transaction.nextLedgerSequence();
        }
        void requireRetainedCardinalCurrent(FreeOwnerTransaction exactTransaction) {
            var tx = source.transaction;
            if (tx != exactTransaction || tx.installationStage() != FreeOwnerTransaction.InstallationStage.NONE
                    || tx.nextLedgerSequence() != ledgerSequence || !source.current()
                    || source.capture.capsuleContextFailure(tx.accepted(), source.window).isPresent()
                    || tx.queries().terminal())
                throw new IllegalStateException("occupied target lost its pre-installation Cardinal source or query owner");
            tx.requireCurrent();
        }
        /** After real Cardinal movement the old raw anchor is historical; only profile/owner remain this proof's duty. */
        void requireCardinalTailProfileCurrent(FreeOwnerTransaction exactTransaction) {
            var tx = source.transaction;
            if (tx != exactTransaction || tx.installationStage() != FreeOwnerTransaction.InstallationStage.NONE)
                throw new IllegalStateException("occupied target cannot cross a Free installation");
            tx.requireOwnerFence();
            boolean current = source.observation.profileCurrent();
            tx.requireOwnerFence();
            if (!current) throw new IllegalStateException("retained Cardinal native body profile changed during callbacks");
        }
    }

    static final class Fit implements FitResult {
        private final FreeOwnerTransaction transaction;
        private final Observation observation;
        private final CapsulePlacementMath.Pose pose;
        private final WorldCollisionCandidates.Snapshot capture;
        private final Aabb window;
        private Fit(FreeOwnerTransaction transaction, Observation observation, CapsulePlacementMath.Pose pose,
                WorldCollisionCandidates.Snapshot capture, Aabb window) {
            this.transaction = transaction; this.observation = observation; this.pose = pose;
            this.capture = capture; this.window = window;
        }
        boolean matches(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose exactPose,
                WorldCollisionCandidates.Snapshot exactCapture, Aabb exactWindow, StoredDyadic.Work work) {
            return transaction.accepted() == accepted && observation.accepted == accepted && pose == exactPose
                    && capture == exactCapture && window == exactWindow && transaction.numeric() == work
                    && capture.capsuleContextFailure(accepted, window).isEmpty() && current();
        }
        void requireInstalledProfileCurrent() {
            transaction.requireCurrent();
            boolean matches = observation.profileCurrent();
            transaction.requireCurrent();
            if (!matches) throw new IllegalStateException("installed physical profile changed during callbacks");
        }
        boolean current() {
            transaction.requireCurrent();
            boolean result = observation.matches(transaction.accepted());
            transaction.requireCurrent();
            return result;
        }
    }

    static FitResult certify(FreeOwnerTransaction transaction, Observation observation, CapsulePlacementMath.Pose pose,
            WorldCollisionCandidates.Snapshot capture, Aabb window) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(observation);
        transaction.requireCurrent();
        if (!observation.matches(transaction.accepted())
                || capture.capsuleContextFailure(transaction.accepted(), window).isPresent()
                || !(pose.placement().shape() instanceof PlayerBodyShape.FreeCapsule free)
                || !free.collisionProfile().equals(observation.physical())
                || free.eyeAnchor().isEmpty()
                || !StoredRealizationLimits.sameRaw(pose.placement().center(), transaction.accepted().placement().center()))
            throw new IllegalStateException("first-Free profile/anchor/capture identity changed");
        for (var box : capture.boxes()) {
            transaction.requireCurrent();
            var contact = transaction.queries().query(pose.capsule(), box, FreeOwnerTransaction.ACCURACY);
            transaction.requireCurrent();
            if (contact.relation() == Relation.OVERLAPPING) {
                var occupied = new TargetOccupied(new Fit(transaction, observation, pose, capture, window));
                occupied.requireRetainedCardinalCurrent(transaction);
                return occupied;
            }
            if (contact.relation() != Relation.SEPARATED && contact.relation() != Relation.TOUCHING)
                throw new IllegalStateException("first-Free full physical target does not fit: " + contact.relation());
        }
        var result = new Fit(transaction, observation, pose, capture, window);
        if (!result.matches(transaction.accepted(), pose, capture, window, transaction.numeric()))
            throw new IllegalStateException("first-Free target fit lost its exact source");
        return result;
    }
}
