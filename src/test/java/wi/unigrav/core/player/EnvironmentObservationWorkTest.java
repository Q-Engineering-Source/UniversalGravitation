package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.EnvironmentObservationSnapshotTest.Probe;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex;
import wi.unigrav.core.floor.FloorFieldProfile;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentObservationWorkTest {
    private static final UUID PLAYER = new UUID(0, 9281);
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(PLAYER, new UUID(0, 9282), 0, 1);
    @TempDir Path temp;

    @Test void actualProducerIsResampledAndOriginalFailureKeepsItsSpentDispatch() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("producer"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var resources = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0);
            var work = resources.environmentObservationWork();
            var calls = new AtomicInteger();
            var force = new AtomicReference<>(new Vec3(1, 2, 3));
            var failure = new IllegalStateException("real producer failure");
            try (var registration = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "owner-observation", (p, t) -> {
                calls.incrementAndGet();
                if (force.get() == null) throw failure;
                return force.get();
            })) {
                assertEquals(new Vec3(1, 2, 3), EntityGravitySources.fieldAcceleration(actor, Vec3.ZERO, 0, work));
                long first = work.usage().units();
                force.set(new Vec3(4, 5, 6));
                assertEquals(new Vec3(4, 5, 6), EntityGravitySources.fieldAcceleration(actor, Vec3.ZERO, 0, work));
                assertEquals(2, calls.get());
                assertTrue(work.usage().units() > first, "same-tick changed sources must be resampled and charged");
                long beforeFailure = work.usage().units();
                force.set(null);
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> EntityGravitySources.fieldAcceleration(actor, Vec3.ZERO, 0, work)));
                assertEquals(3, calls.get());
                assertTrue(work.usage().units() > beforeFailure, "throwing dispatch is not refunded");
            }
        }
    }

    @Test void bindingFilterChargesOnlyTheActuallyInvokedCallback() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("filter"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            assertTrue(fixture.world().spawnEntity(actor));
            var binder = new GravityEntityBinder();
            var calls = new AtomicInteger();
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            assertFalse(binder.sampleFieldBinding(actor, work).active());
            assertEquals(0, work.usage().units());
            try (var registration = binder.bindPredicate(entity -> entity == actor, GravityMode.REPLACE,
                    entity -> { calls.incrementAndGet(); return true; }, new WorldServer[]{fixture.world()})) {
                assertTrue(binder.sampleFieldBinding(actor, work).active());
                assertEquals(1, calls.get());
                assertEquals(1, work.usage().units(), "one actual tick-filter dispatch");
            }
        }
    }

    @Test void correctionPreservesExhaustionAndRetiresSavedArithmetic() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("correction"), PLAYER, false)) {
            var store = new FreeTickResources.Store();
            var resources = store.acquire(this, fixture.world(), SCOPE, 0);
            var work = resources.environmentObservationWork();
            var arithmetic = work.arithmetic();
            arithmetic.exact(1.25);
            long numeric = resources.numeric().units();
            resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS - 1);
            work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            var successor = store.completeNativeCorrection(this, fixture.world(), SCOPE, 1);
            assertThrows(IllegalStateException.class, () -> work.charge(EnvironmentObservationWork.Unit.DISPATCH));
            assertThrows(IllegalStateException.class, () -> arithmetic.exact(2));
            var next = successor.environmentObservationWork();
            assertEquals(FreePostprocessingWork.MAX_UNITS, next.usage().units());
            assertEquals(numeric, next.usage().numeric().units());
            assertThrows(FreePostprocessingWork.Exhausted.class,
                    () -> next.charge(EnvironmentObservationWork.Unit.DISPATCH));
        }
    }

    @Test void sameTickCorrectionBlocksAllCallbacksOnTheSavedSnapshotDecision() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("saved-snapshot-correction"), PLAYER, false)) {
            var store = new FreeTickResources.Store();
            var resources = store.acquire(this, fixture.world(), SCOPE, 0);
            var probe = new Probe();
            try (var registration = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "saved-snapshot", probe)) {
                var decision = AmbientWorldFields.capture(fixture.world(), resources.environmentObservationWork());
                assertEquals(1, probe.snapshots.get());
                long spent = resources.environmentObservationWork().usage().units();
                var successor = store.completeNativeCorrection(this, fixture.world(), SCOPE, 1);
                assertThrows(IllegalStateException.class, decision::isCurrent);
                assertThrows(IllegalStateException.class, () -> decision.accelerationAt(Vec3.ZERO, 0));
                assertEquals(0, probe.currents.get());
                assertEquals(0, probe.samples.get());
                assertEquals(spent, successor.environmentObservationWork().usage().units());
                var refreshed = AmbientWorldFields.capture(fixture.world(), successor.environmentObservationWork());
                assertTrue(refreshed.isCurrent());
                assertEquals(2, probe.snapshots.get());
                assertEquals(1, probe.currents.get());
                assertTrue(successor.environmentObservationWork().usage().units() > spent);
            }
        }
    }

    @Test void exhaustedObservationCannotInvokeProducerAndCorrectionDoesNotRefundIt() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("dispatch-exhaustion"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var store = new FreeTickResources.Store();
            var resources = store.acquire(this, fixture.world(), SCOPE, 0);
            resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
            var calls = new AtomicInteger();
            try (var registration = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "exhausted-observation",
                    (p, t) -> { calls.incrementAndGet(); return Vec3.ZERO; })) {
                assertThrows(FreePostprocessingWork.Exhausted.class, () ->
                        EntityGravitySources.fieldAcceleration(actor, Vec3.ZERO, 0, resources.environmentObservationWork()));
                var successor = store.completeNativeCorrection(this, fixture.world(), SCOPE, 1);
                assertThrows(FreePostprocessingWork.Exhausted.class, () ->
                        EntityGravitySources.fieldAcceleration(actor, Vec3.ZERO, 0, successor.environmentObservationWork()));
                assertEquals(0, calls.get());
            }
        }
    }

    @Test void realFloorRouteUsesSameLedgerAndMatchesLegacyNumericsAfterReplacement() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("floor"), PLAYER, false)) {
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            var index = new FloorFieldIndex();
            var key = new FloorFieldIndex.ChunkKey(0, 0);
            Object owner = new Object();
            var point = new Vec3(.5, 65, .5);
            index.replaceChunk(key, owner, List.of(new FloorFieldIndex.Source(0, 64, 0, Face.UP,
                    FloorFieldIndex.Kind.GRAVITY, new FloorFieldProfile(8, 2, 6))));
            var baseline = index.sampleForStanding(point, 0);
            var observed = index.sampleForStanding(point, 0, work);
            assertEquals(baseline.forces(), observed.forces());
            assertEquals(baseline.gravityStrengths(), observed.gravityStrengths());
            assertEquals(baseline.magneticStrengths(), observed.magneticStrengths());
            long first = work.usage().units();
            assertTrue(first > 0);
            index.replaceChunk(key, owner, List.of(new FloorFieldIndex.Source(0, 64, 0, Face.UP,
                    FloorFieldIndex.Kind.GRAVITY, new FloorFieldProfile(12, 2, 6))));
            var changed = index.sampleForStanding(point, 0, work);
            assertEquals(new Vec3(0, -12, 0), changed.forces().gravityAccel());
            assertEquals(index.sampleForStanding(point, 0).forces(), changed.forces());
            assertTrue(work.usage().units() > first);
        }
    }

    @Test void nativeAttemptsAreClaimedAtRealAggregateCallAfterSuccessfulPreflight() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("native"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            var limits = new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
            var unavailable = WorldCollisionCandidates.capture(fixture.world(), actor,
                    new Aabb(new Vec3d(1000, 64, 1000), new Vec3d(1001, 65, 1001)),
                    CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, limits, work);
            assertInstanceOf(WorldCollisionCandidates.Unavailable.class, unavailable);
            assertEquals(0, work.usage().nativeAttempts());
            var bounds = new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 65, 9));
            assertInstanceOf(WorldCollisionCandidates.Snapshot.class, WorldCollisionCandidates.capture(
                    fixture.world(), actor, bounds, CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, limits, work));
            assertEquals(1, work.usage().nativeAttempts());
        }
    }

    @Test void typedForceRetainsDecisionAcrossLaterRealCallbackWithoutResamplingProducer() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("typed-registration"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            assertTrue(fixture.world().spawnEntity(actor));
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            var calls = new AtomicInteger();
            var position = new Vec3(8, 70, 8);
            try (var source = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "typed-observation", (p, t) -> {
                calls.incrementAndGet(); return new Vec3(1, 2, 3);
            })) {
                var observation = EntityGravitySources.observeFieldAcceleration(actor, position, 3.5, work);
                assertEquals(position, observation.position());
                assertEquals(3.5, observation.time());
                assertEquals(new Vec3(1, 2, 3), observation.acceleration());
                assertEquals(1, calls.get());
                var binder = new GravityEntityBinder();
                try (var binding = binder.bindPredicate(entity -> entity == actor, GravityMode.REPLACE,
                        entity -> { source.close(); return true; }, new WorldServer[]{fixture.world()})) {
                    assertTrue(binder.sampleFieldBinding(actor, work).active());
                    assertFalse(observation.isCurrent(), "later real filter callback changed the captured ambient roster");
                    assertEquals(1, calls.get(), "current checks must not evaluate producers again");
                    assertEquals(new Vec3(1, 2, 3), observation.acceleration(), "historical values do not claim freshness");
                }
            }
        }
    }

    @Test void typedForceRejectsChangedSnapshotAndKeepsCurrentCallbackFailure() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("typed-snapshot"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            assertTrue(fixture.world().spawnEntity(actor));
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            var probe = new Probe();
            try (var source = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "typed-snapshot", probe)) {
                var observation = EntityGravitySources.observeFieldAcceleration(actor, Vec3.ZERO, 0, work);
                assertEquals(1, probe.snapshots.get());
                assertEquals(1, probe.samples.get());
                var binder = new GravityEntityBinder();
                try (var binding = binder.bindPredicate(entity -> entity == actor, GravityMode.REPLACE,
                        entity -> { probe.current = false; return true; }, new WorldServer[]{fixture.world()})) {
                    assertTrue(binder.sampleFieldBinding(actor, work).active());
                    assertFalse(observation.isCurrent());
                    assertEquals(1, probe.samples.get());
                    assertEquals(1, probe.snapshots.get());
                    long beforeFailure = work.usage().units();
                    probe.currentFailure = new IllegalStateException("typed current callback failure");
                    assertSame(probe.currentFailure, assertThrows(IllegalStateException.class, observation::isCurrent));
                    assertTrue(work.usage().units() > beforeFailure);
                    assertEquals(1, probe.samples.get());
                }
            }
        }
    }

    @Test void typedForceTracksActualSelfRegistrationIdentity() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("typed-self"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            assertTrue(fixture.world().spawnEntity(actor));
            var work = new FreeTickResources.Store().acquire(this, fixture.world(), SCOPE, 0).environmentObservationWork();
            var calls = new AtomicInteger();
            try (var source = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "typed-self-background",
                    (p, t) -> { calls.incrementAndGet(); return new Vec3(0, -8, 0); })) {
                var binder = new GravityEntityBinder();
                try (var binding = binder.bindPredicate(entity -> entity == actor, GravityMode.REPLACE,
                        entity -> { EntityGravitySources.update(actor, new BodyPhysics(2e14, .09)); return true; },
                        new WorldServer[]{fixture.world()})) {
                    EntityGravitySources.update(actor, new BodyPhysics(1e14, .09));
                    var observation = EntityGravitySources.observeFieldAcceleration(actor, new Vec3(8, 70, 8), 0, work);
                    assertEquals(new Vec3(0, -8, 0), observation.acceleration(), "actual registered self source is excluded once");
                    assertTrue(binder.sampleFieldBinding(actor, work).active());
                    assertFalse(observation.isCurrent());
                    assertEquals(1, calls.get());
                }
            } finally {
                EntityGravitySources.remove(actor);
            }
        }
    }

    @Test void typedForceCannotSpendRefundedWorkOrInvokeCallbacksAfterCorrection() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("typed-lifetime"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var store = new FreeTickResources.Store();
            var resources = store.acquire(this, fixture.world(), SCOPE, 0);
            var work = resources.environmentObservationWork();
            var probe = new Probe();
            try (var source = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "typed-lifetime", probe)) {
                var observation = EntityGravitySources.observeFieldAcceleration(actor, Vec3.ZERO, 0, work);
                int currents = probe.currents.get();
                resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS - work.usage().units());
                assertThrows(FreePostprocessingWork.Exhausted.class, observation::isCurrent);
                assertEquals(currents, probe.currents.get());
                var successor = store.completeNativeCorrection(this, fixture.world(), SCOPE, 1);
                assertThrows(IllegalStateException.class, observation::isCurrent);
                assertThrows(FreePostprocessingWork.Exhausted.class, () ->
                        EntityGravitySources.observeFieldAcceleration(actor, Vec3.ZERO, 0, successor.environmentObservationWork()));
                assertEquals(FreePostprocessingWork.MAX_UNITS, successor.environmentObservationWork().usage().units());
                assertEquals(1, probe.snapshots.get());
                assertEquals(1, probe.samples.get());
                assertEquals(currents, probe.currents.get());
                assertEquals(new Vec3(2, 3, 4), observation.acceleration());
            }
        }
    }
}
