package wi.unigrav.core;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.EnvironmentObservationWork;
import wi.unigrav.core.player.FreeTickResources;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

public class EnvironmentObservationSnapshotTest {
    @TempDir Path temp;

    /** The real production snapshot interface, with observable callback attempts. */
    public static final class Probe implements AmbientWorldFields.SnapshotContribution {
        public final AtomicInteger snapshots = new AtomicInteger();
        public final AtomicInteger currents = new AtomicInteger();
        public final AtomicInteger samples = new AtomicInteger();
        public RuntimeException snapshotFailure;
        public RuntimeException currentFailure;
        public boolean current = true;
        public Runnable currentCallback = () -> { };
        private final GravityContribution sampled = (position, time) -> {
            samples.incrementAndGet(); return new Vec3(2, 3, 4);
        };
        @Override public GravityContribution snapshot() {
            snapshots.incrementAndGet();
            if (snapshotFailure != null) throw snapshotFailure;
            return sampled;
        }
        @Override public boolean isCurrent(GravityContribution observation) {
            currents.incrementAndGet();
            if (currentFailure != null) throw currentFailure;
            currentCallback.run();
            return current && observation == sampled;
        }
        @Override public Vec3 accelerationAt(Vec3 position, double time) {
            throw new AssertionError("snapshot owner must evaluate the captured contribution");
        }
    }

    @Test void actualSnapshotAndCurrentCallbacksChargeBeforeEveryAttemptAndPreserveFailures() throws Exception {
        UUID player = new UUID(0, 9293);
        try (var fixture = BootWorldFixture.open(temp.resolve("snapshot-current"), player, false)) {
            var scope = new PlayerPhysicalSnapshot.Scope(player, new UUID(0, 9294), 0, 1);
            EnvironmentObservationWork work = new FreeTickResources.Store()
                    .acquire(this, fixture.world(), scope, 0).environmentObservationWork();
            var probe = new Probe();
            try (var registration = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "snapshot-current", probe)) {
                var decision = AmbientWorldFields.capture(fixture.world(), work);
                assertNotNull(decision);
                assertEquals(1, probe.snapshots.get());
                long afterCapture = work.usage().units();
                assertEquals(2, afterCapture, "one snapshot classification and one actual snapshot dispatch");
                assertEquals(new Vec3(2, 3, 4), decision.accelerationAt(Vec3.ZERO, 0));
                assertEquals(1, probe.samples.get());
                assertTrue(decision.isCurrent());
                assertEquals(1, probe.currents.get());
                assertEquals(afterCapture + 4, work.usage().units(), "sample comparison/dispatch and current classification/dispatch");
                long beforeCurrentFailure = work.usage().units();
                probe.currentFailure = new IllegalStateException("original current failure");
                assertSame(probe.currentFailure, assertThrows(IllegalStateException.class, decision::isCurrent));
                assertEquals(2, probe.currents.get());
                assertEquals(beforeCurrentFailure + 2, work.usage().units());
                long beforeSnapshotFailure = work.usage().units();
                probe.snapshotFailure = new IllegalStateException("original snapshot failure");
                assertSame(probe.snapshotFailure, assertThrows(IllegalStateException.class,
                        () -> AmbientWorldFields.capture(fixture.world(), work)));
                assertEquals(2, probe.snapshots.get());
                assertEquals(beforeSnapshotFailure + 2, work.usage().units());
            }
        }
    }

    @Test void metadataMutationInsideActualCurrentCallbackInvalidatesTheRetainedForce() throws Exception {
        for (boolean retire : new boolean[]{false, true}) {
            UUID player = new UUID(0, retire ? 9297 : 9295);
            try (var fixture = BootWorldFixture.open(temp.resolve("current-metadata-" + retire), player, false)) {
                var scope = new PlayerPhysicalSnapshot.Scope(player, new UUID(0, 9296), 0, 1);
                var work = new FreeTickResources.Store().acquire(this, fixture.world(), scope, 0).environmentObservationWork();
                var actor = fixture.serverPlayer(player);
                var armed = new AtomicBoolean();
                var registration = new AtomicReference<GravityField.Registration>();
                var probe = new Probe();
                probe.currentCallback = () -> {
                    if (armed.get()) {
                        if (retire) AmbientWorldFields.retire(fixture.world());
                        else registration.get().close();
                    }
                };
                try (var source = AmbientWorldFields.field(fixture.world()).registerContribution("fixture", "current-metadata", probe)) {
                    registration.set(source);
                    var observation = EntityGravitySources.observeFieldAcceleration(actor, Vec3.ZERO, 0, work);
                    assertEquals(1, probe.samples.get());
                    assertEquals(1, probe.currents.get());
                    armed.set(true);
                    assertFalse(observation.isCurrent(), "current callback changed " + (retire ? "world owner" : "source roster"));
                    assertEquals(2, probe.currents.get(), "the real current callback must actually run");
                    assertEquals(1, probe.samples.get(), "freshness recheck must not evaluate force again");
                    assertEquals(new Vec3(2, 3, 4), observation.acceleration(), "retained value is historical only");
                }
            }
        }
    }
}
