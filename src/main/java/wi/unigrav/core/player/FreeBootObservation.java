package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootQueryFootprint;
import wi.unigrav.core.floor.BootSurfaceQuery;
import wi.unigrav.core.floor.BootWorldSurfaces;

/** Same-read magnetic sensor observations; physical support is observed independently. */
final class FreeBootObservation {
    private FreeBootObservation() { }
    sealed interface Result permits Observed, Failed { }
    record Failed(String stage, Object evidence, Optional<RuntimeException> cause) implements Result { }

    static Result observe(FreeOwnerTransaction tx, ItemStack feet, boolean creative, boolean zeroG,
                          ActiveBootRules.Snapshot rules, BooleanSupplier rulesCurrent,
                          BootQueryArithmetic arithmetic) {
        return observeInternal(tx, feet, creative, zeroG, rules, rulesCurrent, arithmetic, null, null);
    }

    static Result observe(FreeOwnerTransaction tx, ItemStack feet, boolean creative, boolean zeroG,
            ActiveBootRules.Snapshot rules, BooleanSupplier rulesCurrent, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        if (tx.resources().environmentObservationWork() != work) throw new IllegalArgumentException("foreign observation work");
        return observeInternal(tx, feet, creative, zeroG, rules, () -> {
            work.charge(EnvironmentObservationWork.Unit.DISPATCH);
            return rulesCurrent.getAsBoolean();
        }, work.arithmetic(), work, null);
    }

    static Result observe(CertifiedReferenceEndpoint endpoint, ItemStack feet, boolean creative, boolean zeroG,
            ActiveBootRules.Snapshot rules, BooleanSupplier rulesCurrent, EnvironmentObservationWork work) {
        endpoint.requireCurrent();
        var tx = endpoint.transaction();
        if (tx.resources().environmentObservationWork() != work) throw new IllegalArgumentException("foreign observation work");
        return observeInternal(tx, feet, creative, zeroG, rules, rulesCurrent, work.arithmetic(), work, endpoint);
    }

    private static Result observeInternal(FreeOwnerTransaction tx, ItemStack feet, boolean creative, boolean zeroG,
            ActiveBootRules.Snapshot rules, BooleanSupplier rulesCurrent, BootQueryArithmetic arithmetic,
            EnvironmentObservationWork work, CertifiedReferenceEndpoint endpoint) {
        tx.requireCurrent();
        var qualification = work == null ? ActiveBootRules.qualifySnapshot(feet, creative, zeroG, rules)
                : ActiveBootRules.qualifySnapshot(feet, creative, zeroG, rules, work);
        if (qualification instanceof ActiveBootRules.QualificationUnavailable unavailable)
            return new Failed("qualification", unavailable, unavailable.cause());
        BootWorldSurfaces.QualifiedSnapshot capture = null;
        BootSurfaceQuery.Measurements measurements = null;
        if (qualification instanceof ActiveBootRules.QualifiedBoot qualified) {
            var source = endpoint == null ? BootSurfaceGeometry.fromAccepted(tx.accepted(), arithmetic)
                    : BootSurfaceGeometry.fromEndpoint(endpoint, arithmetic);
            var queryBounds = endpoint == null ? tx.accepted().placement().bounds() : endpoint.pose().placement().bounds();
            var footprint = work == null ? BootQueryFootprint.plan(source, rules.maximumCaptureReachMetres(qualified),
                    rules.captureLimits(), queryBounds)
                    : BootQueryFootprint.plan(source, rules.maximumCaptureReachMetres(qualified),
                    rules.captureLimits(), queryBounds, work);
            var captured = work == null ? BootWorldSurfaces.captureQualified(tx.world(), tx.actor(), footprint, rules.materialRules())
                    : BootWorldSurfaces.captureQualified(tx.world(), tx.actor(), footprint, rules.materialRules(), work);
            if (captured instanceof BootWorldSurfaces.Unavailable unavailable)
                return new Failed("capture", unavailable, unavailable.cause());
            capture = (BootWorldSurfaces.QualifiedSnapshot) captured;
            if (capture.snapshot().captureTick() != tx.tick() || capture.rulesRevision() != rules.revision())
                return new Failed("capture context", capture, Optional.empty());
            var queried = work == null ? BootSurfaceQuery.evaluateQualified(capture, rules.effectiveTiers(qualified),
                    rules.compilerLimits(), rules.queryLimits(), arithmetic)
                    : BootSurfaceQuery.evaluateQualified(capture, rules.effectiveTiers(qualified),
                    rules.compilerLimits(), rules.queryLimits(), arithmetic, work);
            if (queried instanceof BootSurfaceQuery.Unavailable unavailable)
                return new Failed("query", unavailable, unavailable.cause().map(c -> (RuntimeException) c));
            measurements = (BootSurfaceQuery.Measurements) queried;
        }
        var result = new Observed(tx, feet, creative, rules, rulesCurrent, qualification, capture, measurements, endpoint, work);
        return result.matches(tx) ? result : new Failed("context", result, Optional.empty());
    }

    static final class Observed implements Result {
        private final FreeOwnerTransaction transaction;
        private final ItemStack feet;
        private final boolean creative;
        private final ActiveBootRules.Snapshot rules;
        private final BooleanSupplier current;
        private final ActiveBootRules.Qualification qualification;
        private final BootWorldSurfaces.QualifiedSnapshot capture;
        private final BootSurfaceQuery.Measurements measurements;
        private final CertifiedReferenceEndpoint endpoint;
        private final EnvironmentObservationWork work;
        private Observed(FreeOwnerTransaction tx, ItemStack feet, boolean creative, ActiveBootRules.Snapshot rules,
                         BooleanSupplier current, ActiveBootRules.Qualification qualification,
                         BootWorldSurfaces.QualifiedSnapshot capture, BootSurfaceQuery.Measurements measurements,
                         CertifiedReferenceEndpoint endpoint, EnvironmentObservationWork work) {
            transaction = tx; this.feet = feet.copy(); this.creative = creative; this.rules = rules;
            this.current = Objects.requireNonNull(current); this.qualification = qualification;
            this.capture = capture; this.measurements = measurements;
            this.endpoint = endpoint; this.work = work;
        }
        ActiveBootRules.Qualification qualification() { return qualification; }
        Optional<BootSurfaceQuery.Measurements> measurements() { return Optional.ofNullable(measurements); }
        boolean matches(FreeOwnerTransaction tx) {
            if (tx != transaction) return false;
            tx.requireCurrent();
            boolean matches = (endpoint == null || endpoint.current()) && current.getAsBoolean()
                    && creative == tx.actor().capabilities.isCreativeMode
                    && ItemStack.areItemStacksEqual(feet, tx.actor().getItemStackFromSlot(EntityEquipmentSlot.FEET))
                    && (endpoint == null || endpoint.current());
            tx.requireCurrent();
            return matches;
        }
        void requireSourcesCurrentAfterInstall(FreeOwnerTransaction tx) {
            if (tx != transaction) throw new IllegalStateException("foreign post-install boot observation owner");
            tx.requireCurrent();
            if (!current.getAsBoolean() || creative != tx.actor().capabilities.isCreativeMode
                    || !ItemStack.areItemStacksEqual(feet, tx.actor().getItemStackFromSlot(EntityEquipmentSlot.FEET)))
                throw new IllegalStateException("post-install boot rules or equipment changed");
            tx.requireCurrent();
            if (capture == null) return;
            var source = capture.snapshot().footprint().source();
            var lease = BootSurfaceGeometry.installedObservation(tx, source);
            var next = BootWorldSurfaces.recaptureQualifiedAfterInstall(tx.world(), tx.actor(), capture,
                    rules.materialRules(), tx.resources().environmentObservationWork(), lease);
            if (next instanceof BootWorldSurfaces.Unavailable unavailable) {
                if (unavailable.cause().isPresent()) throw unavailable.cause().orElseThrow();
                throw new IllegalStateException("post-install qualified boot recapture unavailable: " + unavailable.reason());
            }
            var observed = (BootWorldSurfaces.QualifiedSnapshot) next;
            lease.requireCurrent(tx.world(), tx.actor(), source);
            if (observed.snapshot().captureTick() != capture.snapshot().captureTick()
                    || observed.rulesRevision() != capture.rulesRevision()
                    || !observed.snapshot().primitives().equals(capture.snapshot().primitives())
                    || !observed.materialMatches().equals(capture.materialMatches()))
                throw new IllegalStateException("post-install qualified boot producer dependencies changed");
            if (!current.getAsBoolean() || creative != tx.actor().capabilities.isCreativeMode
                    || !ItemStack.areItemStacksEqual(feet, tx.actor().getItemStackFromSlot(EntityEquipmentSlot.FEET)))
                throw new IllegalStateException("post-install boot context changed during producer recapture");
            lease.requireCurrent(tx.world(), tx.actor(), source);
        }

        /** Reobserve complete producer dependencies; equal tick alone does not establish freshness. */
        boolean recaptureMatches(FreeOwnerTransaction tx) {
            if (!matches(tx)) return false;
            if (capture == null) return true;
            var next = work == null
                    ? BootWorldSurfaces.captureQualified(tx.world(), tx.actor(), capture.snapshot().footprint(), rules.materialRules())
                    : BootWorldSurfaces.captureQualified(tx.world(), tx.actor(), capture.snapshot().footprint(), rules.materialRules(), work);
            if (next instanceof BootWorldSurfaces.Unavailable unavailable) {
                if (unavailable.cause().isPresent()) throw unavailable.cause().orElseThrow();
                throw new IllegalStateException("Free boot recapture unavailable: " + unavailable.reason());
            }
            var observed = (BootWorldSurfaces.QualifiedSnapshot) next;
            return matches(tx) && observed.snapshot().captureTick() == capture.snapshot().captureTick()
                    && observed.rulesRevision() == capture.rulesRevision()
                    && observed.snapshot().primitives().equals(capture.snapshot().primitives())
                    && observed.materialMatches().equals(capture.materialMatches());
        }
    }
}
