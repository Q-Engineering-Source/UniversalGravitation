package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import wi.unigrav.core.floor.ActiveBootRules.Qualification;
import wi.unigrav.core.floor.ActiveBootRules.QualificationUnavailable;
import wi.unigrav.core.floor.ActiveBootRules.QualifiedBoot;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedCapture;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedSnapshot;
import wi.unigrav.core.floor.BootSurfaceQuery.VisibilityDiagnostic;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsServer.PendingCardinalPose;
import wi.unigrav.core.player.StandingConstraints.Evidence;

/** Same-tick active FEET, material, capture, range, support, and owner-freshness observation. */
public final class PlayerBootObservation {
    private PlayerBootObservation() { }

    public enum Reason {
        WORLD_UNAVAILABLE, ACTOR_SCOPE, EQUIPMENT_UNAVAILABLE, FOOTPRINT_UNAVAILABLE,
        CAPTURE_UNAVAILABLE, QUERY_UNAVAILABLE, SUPPORT_UNAVAILABLE, CONTEXT_CHANGED, NATIVE_QUERY_FAILED
    }

    public sealed interface Result permits Observed, Unavailable { }

    public record Observed(long worldTick, long rulesRevision, Qualification qualification,
                           Evidence evidence) implements Result {
        public Observed {
            if (worldTick < 0L || rulesRevision < 0L) throw new IllegalArgumentException("observation revisions must be nonnegative");
            Objects.requireNonNull(qualification, "qualification");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    public record Unavailable(Reason reason, String detail, Optional<RuntimeException> cause,
                              Optional<VisibilityDiagnostic> visibilityDiagnostic) implements Result {
        public Unavailable(Reason reason, String detail, Optional<RuntimeException> cause) {
            this(reason, detail, cause, Optional.empty());
        }

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(visibilityDiagnostic, "visibilityDiagnostic");
        }
    }

    /**
     * Reads the exact server FEET stack once for qualification, then rechecks it after the
     * bounded loaded-world capture/query/support pipeline. The caller retains no evidence if
     * any source, scope, tick, rule, or actor identity changes before the result is consumed.
     */
    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
                                 ItemStack sameSampleFeet, boolean sameSampleCreative,
                                 ActiveBootRules rules, Evidence nonBootEvidence) {
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative,
                rules, nonBootEvidence, null);
    }

    /** Retains accepted physical eyes only under the exact owner's pending native-pose token. */
    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
                                 ItemStack sameSampleFeet, boolean sameSampleCreative,
                                 ActiveBootRules rules, Evidence nonBootEvidence,
                                 PendingCardinalPose pending) {
        Objects.requireNonNull(rules, "rules");
        ActiveBootRules.Snapshot snapshot = rules.snapshot();
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative, snapshot,
                () -> rules.isCurrent(snapshot), nonBootEvidence, pending);
    }

    /** Uses the exact boot/passive/floor generation captured by the sampler, with no second global rules read. */
    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
                                 ItemStack sameSampleFeet, boolean sameSampleCreative,
                                 FloorDecision decision, Evidence nonBootEvidence) {
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative,
                decision, nonBootEvidence, null);
    }

    /** Retains accepted physical eyes only under the exact owner's pending native-pose token. */
    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
                                 ItemStack sameSampleFeet, boolean sameSampleCreative,
                                 FloorDecision decision, Evidence nonBootEvidence,
                                 PendingCardinalPose pending) {
        Objects.requireNonNull(decision, "decision");
        ActiveBootRules.Snapshot snapshot = decision.bootRules();
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative, snapshot,
                decision::isCurrent, nonBootEvidence, pending);
    }

    private static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
                                  ItemStack sameSampleFeet, boolean sameSampleCreative,
                                  ActiveBootRules.Snapshot activeRules, BooleanSupplier ownerCurrent,
                                  Evidence nonBootEvidence, PendingCardinalPose pending) {
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative,
                activeRules, ownerCurrent, nonBootEvidence, pending, null);
    }

    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
            ItemStack sameSampleFeet, boolean sameSampleCreative, ActiveBootRules rules,
            Evidence nonBootEvidence, PendingCardinalPose pending, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(actor.world, accepted.scope());
        ActiveBootRules.Snapshot snapshot = rules.snapshot();
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative, snapshot,
                () -> rules.isCurrent(snapshot), nonBootEvidence, pending, work);
    }

    public static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
            ItemStack sameSampleFeet, boolean sameSampleCreative, FloorDecision decision,
            Evidence nonBootEvidence, PendingCardinalPose pending, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(actor.world, accepted.scope());
        return observe(actor, accepted, personalZeroG, sameSampleFeet, sameSampleCreative, decision.bootRules(),
                decision::isCurrent, nonBootEvidence, pending, work);
    }

    private static Result observe(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, boolean personalZeroG,
            ItemStack sameSampleFeet, boolean sameSampleCreative, ActiveBootRules.Snapshot activeRules,
            BooleanSupplier current, Evidence nonBootEvidence, PendingCardinalPose pending,
            EnvironmentObservationWork work) {
        BooleanSupplier ownerCurrent = work == null ? current : () -> {
            work.charge(EnvironmentObservationWork.Unit.DISPATCH);
            return current.getAsBoolean();
        };
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(sameSampleFeet, "sameSampleFeet");
        Objects.requireNonNull(activeRules, "activeRules");
        Objects.requireNonNull(ownerCurrent, "ownerCurrent");
        Objects.requireNonNull(nonBootEvidence, "nonBootEvidence");
        if (!(actor.world instanceof WorldServer world)) {
            return unavailable(Reason.WORLD_UNAVAILABLE, "active boot observation requires a server world");
        }
        NetHandlerPlayServer connection = actor.connection;
        if (!world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("active boot observation requires the authoritative server thread");
        }
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal)
                || actor.world != world || actor.isDead || connection == null || connection.player != actor
                || actor.dimension != accepted.scope().dimension()
                || !actor.getUniqueID().equals(accepted.scope().playerId())
                || !placementMatches(actor, accepted, pending)) {
            return unavailable(Reason.ACTOR_SCOPE, "actor does not match accepted Cardinal owner geometry");
        }
        if (world.captureBlockSnapshots || world.restoringBlockSnapshots) {
            return unavailable(Reason.WORLD_UNAVAILABLE, "world block snapshot capture/restore is active");
        }

        long tick = world.getTotalWorldTime();
        ItemStack feet = sameSampleFeet.copy();
        if (actor.capabilities.isCreativeMode != sameSampleCreative
                || !ItemStack.areItemStacksEqual(feet, actor.getItemStackFromSlot(EntityEquipmentSlot.FEET))) {
            return unavailable(Reason.CONTEXT_CHANGED, "FEET equipment changed before same-sample qualification");
        }
        Qualification qualification = work == null ? ActiveBootRules.qualifySnapshot(feet, sameSampleCreative, personalZeroG, activeRules)
                : ActiveBootRules.qualifySnapshot(feet, sameSampleCreative, personalZeroG, activeRules, work);
        if (qualification instanceof QualificationUnavailable unavailable) {
            return unavailable(Reason.EQUIPMENT_UNAVAILABLE, unavailable.detail(), unavailable.cause().orElse(null));
        }
        QualifiedBoot qualifiedBoot = qualification instanceof QualifiedBoot qualified ? qualified : null;
        boolean bootQualified = qualifiedBoot != null;
        Evidence evidence;
        if (bootQualified) {
            // The magnetic sensor and complete active-tier domain are only queried for an
            // actor whose exact same-read FEET stack qualified. Ordinary actors still get an
            // actual support observation below, without paying or failing the magnetic scan.
            final BootQueryFootprint footprint;
            try {
                footprint = work == null ? BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(accepted),
                        activeRules.maximumCaptureReachMetres(qualifiedBoot), activeRules.captureLimits(),
                        accepted.placement().bounds()) : BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(accepted),
                        activeRules.maximumCaptureReachMetres(qualifiedBoot), activeRules.captureLimits(), accepted.placement().bounds(), work);
            } catch (RuntimeException failure) {
                return unavailable(Reason.FOOTPRINT_UNAVAILABLE, "bounded boot footprint planning failed", failure);
            }
            QualifiedCapture captured;
            try {
                captured = work == null ? BootWorldSurfaces.captureQualified(world, actor, footprint, activeRules.materialRules())
                        : BootWorldSurfaces.captureQualified(world, actor, footprint, activeRules.materialRules(), work);
            } catch (RuntimeException failure) {
                return unavailable(Reason.CAPTURE_UNAVAILABLE, "qualified native collision capture threw", failure);
            }
            if (captured instanceof BootWorldSurfaces.Unavailable failure) {
                return unavailable(Reason.CAPTURE_UNAVAILABLE,
                        failure.reason() + ": cell=" + failure.cell() + " actor=" + actor.getUniqueID()
                                + " tick=" + tick + " " + failure.detail(), failure.cause().orElse(null));
            }
            QualifiedSnapshot capture = (QualifiedSnapshot) captured;
            if (capture.snapshot().captureTick() != tick || capture.rulesRevision() != activeRules.revision()) {
                return unavailable(Reason.CONTEXT_CHANGED,
                        "capture did not retain the current world tick and active rules revision");
            }

            var queried = work == null ? BootSurfaceQuery.evaluateQualified(capture, activeRules.effectiveTiers(qualifiedBoot),
                    activeRules.compilerLimits(), activeRules.queryLimits())
                    : BootSurfaceQuery.evaluateQualified(capture, activeRules.effectiveTiers(qualifiedBoot),
                    activeRules.compilerLimits(), activeRules.queryLimits(), work);
            if (queried instanceof BootSurfaceQuery.Unavailable failure) {
                return queryUnavailable(failure);
            }
            var measurements = (BootSurfaceQuery.Measurements) queried;
            PlayerSupportObservation.Result support;
            try {
                support = work == null ? PlayerSupportObservation.observeCardinal(accepted, capture, measurements, true)
                        : PlayerSupportObservation.observeCardinal(accepted, capture, measurements, true, work);
            } catch (RuntimeException failure) {
                return unavailable(Reason.SUPPORT_UNAVAILABLE, "Cardinal support observation threw", failure);
            }
            if (support instanceof PlayerSupportObservation.Unavailable failure) {
                return unavailable(Reason.SUPPORT_UNAVAILABLE, failure.reason() + ": " + failure.detail(),
                        failure.cause().orElse(null));
            }
            evidence = BootStandingEvidence.withBootQueryAndSupport(nonBootEvidence, measurements,
                    ((PlayerSupportObservation.Observed) support).actualSupport(), accepted, tick, true);
        } else {
            PlayerSupportObservation.Result support;
            try {
                support = work == null ? PlayerSupportObservation.observeNativeCardinal(world, actor, accepted)
                        : PlayerSupportObservation.observeNativeCardinal(world, actor, accepted, work);
            } catch (RuntimeException failure) {
                return unavailable(Reason.SUPPORT_UNAVAILABLE, "native Cardinal support observation threw", failure);
            }
            if (support instanceof PlayerSupportObservation.Unavailable failure) {
                return unavailable(failure.reason() == PlayerSupportObservation.Reason.NATIVE_QUERY_FAILED
                                ? Reason.NATIVE_QUERY_FAILED : Reason.SUPPORT_UNAVAILABLE,
                        failure.reason() + ": " + failure.detail(), failure.cause().orElse(null));
            }
            evidence = BootStandingEvidence.withNativeSupport(nonBootEvidence,
                    ((PlayerSupportObservation.Observed) support).actualSupport());
        }
        if (evidence.availability() != wi.unigrav.core.player.StandingConstraints.Availability.AVAILABLE) {
            return unavailable(Reason.CONTEXT_CHANGED, "same-observation standing evidence failed validation");
        }
        if (actor.world != world || actor.isDead || actor.connection != connection || connection.player != actor
                || world.getTotalWorldTime() != tick || !ownerCurrent.getAsBoolean()
                || actor.capabilities.isCreativeMode != sameSampleCreative
                || !placementMatches(actor, accepted, pending)
                || !ItemStack.areItemStacksEqual(feet, actor.getItemStackFromSlot(EntityEquipmentSlot.FEET))) {
            return unavailable(Reason.CONTEXT_CHANGED,
                    "actor, FEET equipment, accepted placement, world tick, or active rules changed during boot observation");
        }
        if (work != null) work.requireCurrent();
        return new Observed(tick, activeRules.revision(), qualification, evidence);
    }

    private static boolean placementMatches(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, PendingCardinalPose pending) {
        var placement = accepted.placement();
        var actual = actor.getEntityBoundingBox();
        return actor.posX == placement.transport().x() && actor.posY == placement.transport().y()
                && actor.posZ == placement.transport().z()
                && actual.minX == placement.bounds().min().x() && actual.minY == placement.bounds().min().y()
                && actual.minZ == placement.bounds().min().z() && actual.maxX == placement.bounds().max().x()
                && actual.maxY == placement.bounds().max().y() && actual.maxZ == placement.bounds().max().z()
                && actor.width == placement.shape().dimensions().width()
                && actor.height == placement.shape().dimensions().height()
                && eyeMatches(actor, accepted, pending);
    }

    private static boolean eyeMatches(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, PendingCardinalPose pending) {
        float observed = actor.getEyeHeight();
        if (pending != null) return pending.matchesSnapshot(actor, accepted, observed) && pending.current();
        return observed == accepted.placement().shape().dimensions().eyeHeight();
    }

    private static Unavailable unavailable(Reason reason, String detail) {
        return unavailable(reason, detail, null);
    }

    private static Unavailable unavailable(Reason reason, String detail, RuntimeException cause) {
        return new Unavailable(reason, detail, Optional.ofNullable(cause));
    }

    /** Carries the bounded query witness through the existing owner failure message and cause chain. */
    static Unavailable queryUnavailable(BootSurfaceQuery.Unavailable failure) {
        Objects.requireNonNull(failure, "failure");
        Optional<VisibilityDiagnostic> diagnostic = failure.visibilityDiagnostic();
        String detail = failure.reason() + ": " + failure.detail();
        if (diagnostic.isPresent()) detail += ": " + diagnostic.orElseThrow().summary();
        return new Unavailable(Reason.QUERY_UNAVAILABLE, detail,
                failure.cause().map(cause -> (RuntimeException) cause), diagnostic);
    }
}
