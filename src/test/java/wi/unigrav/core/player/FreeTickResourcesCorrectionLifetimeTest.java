package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;

import static org.junit.jupiter.api.Assertions.*;

class FreeTickResourcesCorrectionLifetimeTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000009271");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000009272");
    private static final Aabb OBSTACLE = new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1));
    private static final Capsule CAPSULE = new Capsule(
            new Segment3d(new Vec3d(-2, 0.5, 0.5), new Vec3d(-2, 0.5, 0.5)), 0.25);
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);

    @TempDir Path temp;

    @Test void explicitClientSimulationClockRetainsEveryLedgerAcrossWorldTimeSynchronization() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("client-clock"), PLAYER, false)) {
            var world = fixture.world(); var owner = new Object(); var scope = scope(0);
            var simulationTick = new java.util.concurrent.atomic.AtomicLong(1);
            var store = new FreeTickResources.Store(simulationTick::get);
            world.setTotalWorldTime(100);
            var first = store.acquire(owner, world, scope, 20);
            first.numeric().charge(17); first.postprocessing().charge(3); first.claimNativeAttempt();
            first.queries().query(CAPSULE, OBSTACLE, ACCURACY);
            assertTrue(first.claimWaterForce());
            long queryCalls = first.queries().calls(); var queryWork = first.queries().work();
            for (long synchronizedTime : new long[] {95, 1000, 96, 1001}) {
                world.setTotalWorldTime(synchronizedTime);
                var same = store.acquire(owner, world, scope, 20);
                assertSame(first, same); assertEquals(17, same.numeric().units());
                assertEquals(3, same.postprocessing().units()); assertEquals(1, same.nativeAttempts());
                assertEquals(queryCalls, same.queries().calls()); assertEquals(queryWork, same.queries().work());
                assertFalse(same.claimWaterForce()); same.chargeNativeShell(0);
            }
            simulationTick.incrementAndGet();
            var next = store.acquire(owner, world, scope, 20);
            assertNotSame(first, next); assertSame(next, store.acquire(owner, world, scope, 20));
            assertEquals(0, next.numeric().units()); assertEquals(0, next.queries().calls());
            assertTrue(next.claimWaterForce()); assertFalse(next.claimWaterForce());
            assertThrows(IllegalStateException.class, first::claimNativeAttempt);
            assertThrows(IllegalStateException.class, () -> first.chargeNativeShell(1));
            assertThrows(IllegalStateException.class, first::claimWaterForce);
            assertThrows(IllegalStateException.class, () -> store.acquire(new Object(), world, scope, 20));
        }
    }

    @Test void clientCompletionTokenRetainsAllSpentWorkAndRejectsOldAndDuplicateLeases() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("client-completion"), PLAYER, false)) {
            var tick = new java.util.concurrent.atomic.AtomicLong(1);
            var store = new FreeTickResources.Store(tick::get);
            var owner = new Object(); var world = fixture.world(); var scope = scope(0);
            var old = store.acquire(owner, world, scope, -1);
            old.numeric().charge(17); old.postprocessing().charge(3); old.claimNativeAttempt();
            old.queries().query(CAPSULE, OBSTACLE, ACCURACY); assertTrue(old.claimWaterForce());
            var queries = old.queries(); var queryWork = queries.work(); long queryCalls = queries.calls();
            var transfer = store.prepareClientNativeCorrection(old, 7);
            assertThrows(IllegalStateException.class, old::claimNativeAttempt);
            assertThrows(IllegalStateException.class, () -> store.acquire(owner, world, scope, 7));
            // The rejected acquire must not manufacture a successor; complete the captured installation handoff.
            var corrected = store.completeClientNativeCorrection(transfer, store.captureClientCompletion(transfer));
            assertSame(queries, corrected.queries()); assertEquals(queryCalls, corrected.queries().calls());
            assertEquals(queryWork, corrected.queries().work()); assertEquals(17, corrected.numeric().units());
            assertEquals(3, corrected.postprocessing().units()); assertEquals(1, corrected.nativeAttempts());
            assertFalse(corrected.claimWaterForce());
            assertSame(corrected, store.acquire(owner, world, scope, 7), "ordinary state acquisition creates no grant");
            assertThrows(IllegalStateException.class, () -> store.completeClientNativeCorrection(transfer, store.captureClientCompletion(transfer)));
            assertThrows(IllegalStateException.class, old::claimNativeAttempt);
            tick.incrementAndGet();
            var next = store.acquire(owner, world, scope, 7);
            assertNotSame(corrected, next); assertEquals(0, next.numeric().units());
        }
    }

    @Test void supersededOrInvalidatedClientCompletionCannotTransferItsFrame() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("client-stale-completion"), PLAYER, false)) {
            var tick = new java.util.concurrent.atomic.AtomicLong(1);
            var store = new FreeTickResources.Store(tick::get);
            var owner = new Object(); var world = fixture.world(); var scope = scope(0);
            var old = store.acquire(owner, world, scope, -1); old.numeric().charge(9);
            var first = store.prepareClientNativeCorrection(old, 7);
            assertSame(first, store.prepareClientNativeCorrection(old, 7));
            var second = store.prepareClientNativeCorrection(old, 8);
            assertThrows(IllegalStateException.class, () -> store.completeClientNativeCorrection(first, store.captureClientCompletion(first)));
            var current = store.completeClientNativeCorrection(second, store.captureClientCompletion(second));
            assertEquals(9, current.numeric().units());
            var third = store.prepareClientNativeCorrection(current, 9);
            store.invalidate();
            assertThrows(IllegalStateException.class, () -> store.completeClientNativeCorrection(third, store.captureClientCompletion(third)));
        }
    }

    @Test void delegatedResumeCanReplacePendingCorrectionWithoutRefreshingItsFrame() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("client-proposal-resume"), PLAYER, false)) {
            var tick = new java.util.concurrent.atomic.AtomicLong(1);
            var store = new FreeTickResources.Store(tick::get);
            var owner = new Object(); var world = fixture.world(); var scope = scope(0);
            var old = store.acquire(owner, world, scope, 91);
            old.numeric().charge(17); old.postprocessing().charge(3); old.claimNativeAttempt();
            old.queries().query(CAPSULE, OBSTACLE, ACCURACY); assertTrue(old.claimWaterForce());
            var queries = old.queries(); long calls = queries.calls(); var work = queries.work();
            var correction = store.prepareClientNativeCorrection(old, 92);
            var superseded = store.prepareClientNativeResume(old);
            var resume = store.prepareClientNativeResume(old);
            assertThrows(IllegalStateException.class, () -> store.captureClientCompletion(superseded));
            assertThrows(IllegalStateException.class, () -> store.captureClientCompletion(correction));
            assertThrows(IllegalStateException.class, old::claimNativeAttempt);
            var captured = store.captureClientCompletion(resume);
            var resumed = store.completeClientNativeResume(resume, captured);
            assertSame(resumed, store.acquire(owner, world, scope, -1));
            assertSame(queries, resumed.queries()); assertEquals(calls, queries.calls()); assertEquals(work, queries.work());
            assertEquals(17, resumed.numeric().units()); assertEquals(3, resumed.postprocessing().units());
            assertEquals(1, resumed.nativeAttempts()); assertFalse(resumed.claimWaterForce());
            assertThrows(IllegalStateException.class, () -> store.completeClientNativeResume(resume, captured));
            var nextCorrection = store.prepareClientNativeCorrection(resumed, 1);
            var again = store.completeClientNativeCorrection(nextCorrection, store.captureClientCompletion(nextCorrection));
            assertSame(again, store.acquire(owner, world, scope, 1));
            assertEquals(17, again.numeric().units());
        }
    }

    @Test void capturedClientCompletionCannotCommitAfterClockReentryOrRetirement() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("client-completion-reentry"), PLAYER, false)) {
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var holder = new FreeTickResources.Store[1];
            var store = new FreeTickResources.Store(() -> {
                if (armed.getAndSet(false)) holder[0].invalidate();
                return 1;
            });
            holder[0] = store;
            var owner = new Object(); var world = fixture.world(); var scope = scope(0);
            var old = store.acquire(owner, world, scope, 91);
            var resume = store.prepareClientNativeResume(old);
            armed.set(true);
            assertThrows(IllegalStateException.class, () -> store.captureClientCompletion(resume));
            assertThrows(IllegalStateException.class, () -> store.acquire(owner, world, scope, -1));
        }
    }

    @Test void confirmedSameTickCorrectionRetiresOldHandleAndKeepsEverySpentLedger() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("same-tick-correction"), PLAYER, false)) {
            var world = fixture.world();
            var scope = scope(0);
            var owner = new Object();
            var store = new FreeTickResources.Store();
            var old = store.acquire(owner, world, scope, 20);

            old.numeric().charge(StoredDyadic.MAX_WORK_UNITS - 1);
            old.postprocessing().charge(FreePostprocessingWork.MAX_UNITS - 1);
            for (int attempt = 0; attempt < FreeTickResources.MAX_NATIVE_ATTEMPTS; attempt++)
                old.claimNativeAttempt();
            assertTrue(old.claimWaterForce());

            CapsuleContactQueryBudget queries = old.queries();
            CapsuleContactQueryBudget.BudgetExhausted exhausted = null;
            for (int attempt = 0; attempt <= 512; attempt++) {
                try {
                    queries.query(CAPSULE, OBSTACLE, ACCURACY);
                } catch (CapsuleContactQueryBudget.BudgetExhausted failure) {
                    exhausted = failure;
                    break;
                }
            }
            assertNotNull(exhausted, "the real bounded CHB query ledger must exhaust within its configured caps");
            assertTrue(queries.calls() > 0);
            var queryWork = queries.work();
            long queryCalls = queries.calls();

            FreeTickResources corrected = store.completeNativeCorrection(owner, world, scope, 21);

            assertNotSame(old, corrected, "a confirmed correction receives a new lifetime handle");
            assertSame(queries, corrected.queries(), "native entry in this world tick retains the consumed query ledger");
            assertEquals(queryCalls, corrected.queries().calls());
            assertEquals(queryWork, corrected.queries().work());
            assertEquals(StoredDyadic.MAX_WORK_UNITS - 1, corrected.numeric().units());
            assertEquals(FreePostprocessingWork.MAX_UNITS - 1, corrected.postprocessing().units());
            assertEquals(FreeTickResources.MAX_NATIVE_ATTEMPTS, corrected.nativeAttempts());

            FreeTickResources secondCorrection = store.completeNativeCorrection(owner, world, scope, 22);
            assertNotSame(corrected, secondCorrection, "each accepted correction retires its predecessor handle");
            assertSame(queries, secondCorrection.queries(), "same-tick corrections retain one accounting frame");
            assertSame(secondCorrection, store.acquire(owner, world, scope, 22),
                    "the same-tick native environment entry resolves to the transferred handle");
            assertEquals(queryCalls, secondCorrection.queries().calls());
            assertEquals(queryWork, secondCorrection.queries().work());
            assertEquals(StoredDyadic.MAX_WORK_UNITS - 1, secondCorrection.numeric().units());
            assertEquals(FreePostprocessingWork.MAX_UNITS - 1, secondCorrection.postprocessing().units());
            assertEquals(FreeTickResources.MAX_NATIVE_ATTEMPTS, secondCorrection.nativeAttempts());
            assertFalse(secondCorrection.claimWaterForce(), "corrections cannot replay the already consumed force");

            assertThrows(IllegalStateException.class,
                    () -> old.requireCurrent(owner, world, scope, 20), "the old correction lease is retired");
            assertThrows(IllegalStateException.class, old::claimWaterForce);
            assertThrows(IllegalStateException.class,
                    () -> corrected.requireCurrent(owner, world, scope, 21), "the intermediate correction lease is retired");
            assertThrows(FreeTickResources.NativeAttemptLimitException.class, secondCorrection::claimNativeAttempt);
            assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                    () -> secondCorrection.queries().query(CAPSULE, OBSTACLE, ACCURACY));
            assertEquals(queryCalls + 1, secondCorrection.queries().calls(), "the denied query entry remains charged");
            assertEquals(queryWork, secondCorrection.queries().work(), "a denied query does no CHB work and refunds nothing");

            secondCorrection.chargeNativeShell(1);
            assertEquals(StoredDyadic.MAX_WORK_UNITS, secondCorrection.numeric().units());
            assertThrows(NumericWorkExhaustedException.class, () -> secondCorrection.chargeNativeShell(1));
            secondCorrection.postprocessing().charge(1);
            assertEquals(FreePostprocessingWork.MAX_UNITS, secondCorrection.postprocessing().units());
            assertThrows(FreePostprocessingWork.Exhausted.class, () -> secondCorrection.postprocessing().charge(1));
        }
    }

    @Test void onlyTheNextWorldTickCreatesANewAccountingFrame() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("next-tick-frame"), PLAYER, false)) {
            var world = fixture.world();
            var scope = scope(0);
            var owner = new Object();
            var store = new FreeTickResources.Store();
            var beforeCorrection = store.acquire(owner, world, scope, 30);
            beforeCorrection.numeric().charge(17);

            var corrected = store.completeNativeCorrection(owner, world, scope, 31);
            assertSame(beforeCorrection.queries(), corrected.queries());
            assertEquals(17, corrected.numeric().units());

            world.getWorldInfo().setWorldTotalTime(world.getTotalWorldTime() + 1);
            var nextTick = store.acquire(owner, world, scope, 31);

            assertNotSame(corrected, nextTick);
            assertNotSame(corrected.queries(), nextTick.queries());
            assertEquals(0, nextTick.queries().calls());
            assertEquals(0, nextTick.numeric().units());
            assertEquals(0, nextTick.postprocessing().units());
            assertEquals(0, nextTick.nativeAttempts());
            assertTrue(nextTick.claimWaterForce());
            assertThrows(IllegalStateException.class,
                    () -> corrected.requireCurrent(owner, world, scope, 31));
        }
    }

    @Test void rejectedCorrectionTransfersLeaveTheCurrentLifetimeUntouched() throws Exception {
        UUID otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000009273");
        var scope = scope(0);
        var owner = new Object();
        var store = new FreeTickResources.Store();
        FreeTickResources current;
        WorldServer originalWorld;
        try (var fixture = BootWorldFixture.open(temp.resolve("current-world"), PLAYER, false)) {
            originalWorld = fixture.world();
            current = store.acquire(owner, originalWorld, scope, 40);
            current.numeric().charge(9);
            assertThrows(IllegalStateException.class,
                    () -> store.completeNativeCorrection(new Object(), originalWorld, scope, 41), "owner must match");
            assertThrows(IllegalStateException.class,
                    () -> store.completeNativeCorrection(owner, originalWorld, scope(1), 41), "scope must match");
            assertThrows(IllegalStateException.class,
                    () -> store.completeNativeCorrection(owner, originalWorld, scope, 40), "correction must advance");
            assertThrows(IllegalStateException.class,
                    () -> store.completeNativeCorrection(owner, originalWorld, scope, 39), "stale correction must not transfer");
        }
        try (var otherFixture = BootWorldFixture.open(temp.resolve("other-world"), otherPlayer, false)) {
            assertThrows(IllegalStateException.class,
                    () -> store.completeNativeCorrection(owner, otherFixture.world(), scope, 41), "world must match");
        }
        assertEquals(9, current.numeric().units());
        assertTrue(current.claimWaterForce(), "a rejected handoff must not retire or poison the current lease");
        assertDoesNotThrow(() -> current.requireCurrent(owner, originalWorld, scope, 40),
                "the rejected transfer leaves the old lease active");
    }

    @Test void recoveryRotationRetiresHandlesButPreservesSameTickSpentFrame() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("recovery-frame"), PLAYER, false)) {
            var world = fixture.world(); var owner = new Object(); var oldScope = scope(0);
            var nextScope = new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(), 0, 2);
            var store = new FreeTickResources.Store();
            var old = store.acquire(owner, world, oldScope, 7);
            old.numeric().charge(17); old.postprocessing().charge(3); old.claimNativeAttempt();
            old.queries().query(CAPSULE, OBSTACLE, ACCURACY); assertTrue(old.claimWaterForce());
            store.invalidate();
            assertThrows(IllegalStateException.class,
                    () -> store.transferRecoveryScope(new Object(), world, oldScope, nextScope, 8));
            var successor = store.transferRecoveryScope(owner, world, oldScope, nextScope, 8);
            var next = successor.acquire(owner, world, nextScope, 8);
            assertSame(old.queries(), next.queries()); assertSame(old.numeric(), next.numeric());
            assertEquals(17, next.numeric().units()); assertEquals(3, next.postprocessing().units());
            assertEquals(1, next.nativeAttempts()); assertFalse(next.claimWaterForce());
            assertThrows(IllegalStateException.class, () -> old.requireCurrent(owner, world, oldScope, 7));
            assertThrows(IllegalStateException.class, () -> store.acquire(owner, world, oldScope, 7));
            assertThrows(IllegalStateException.class,
                    () -> store.transferRecoveryScope(owner, world, oldScope, nextScope, 8));
            world.setTotalWorldTime(world.getTotalWorldTime() + 1);
            var tick = successor.acquire(owner, world, nextScope, 8);
            assertNotSame(next.queries(), tick.queries()); assertEquals(0, tick.numeric().units());
            assertEquals(0, tick.nativeAttempts()); assertTrue(tick.claimWaterForce());
        }
    }

    @Test void reentrantRecoveryClockCannotConsumeTheSameTransferTwice() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("reentrant-recovery-clock"), PLAYER, false)) {
            var world = fixture.world();
            var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            var store = new FreeTickResources.Store(() -> {
                var action = callback.getAndSet(null);
                if (action != null) action.run();
                return world.getTotalWorldTime();
            });
            var owner = new Object(); var originalScope = scope(0);
            var outerScope = new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 2);
            var innerScope = new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 3);
            var original = store.acquire(owner, world, originalScope, 7);
            original.numeric().charge(17);
            var winner = new java.util.concurrent.atomic.AtomicReference<FreeTickResources.Store>();
            callback.set(() -> winner.set(store.transferRecoveryScope(owner, world, originalScope, innerScope, 7)));
            assertThrows(IllegalStateException.class,
                    () -> store.transferRecoveryScope(owner, world, originalScope, outerScope, 7));
            var retained = winner.get().acquire(owner, world, innerScope, 7);
            assertSame(original.numeric(), retained.numeric());
            assertEquals(17, retained.numeric().units());
            assertThrows(IllegalStateException.class,
                    () -> store.transferRecoveryScope(owner, world, originalScope, outerScope, 7));
        }
    }

    private static PlayerPhysicalSnapshot.Scope scope(int dimension) {
        return new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, dimension, 1);
    }
    @Test void healthyNativeResumeTransfersCorrectionOnceWithoutRefund() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("resume-lifetime"),PLAYER,false)) {
            var world=fixture.world();var owner=new Object();var store=new FreeTickResources.Store();var scope=scope(0);
            var old=store.acquire(owner,world,scope,20);old.numeric().charge(17);old.postprocessing().charge(3);
            old.claimNativeAttempt();assertTrue(old.claimWaterForce());old.queries().query(CAPSULE,OBSTACLE,ACCURACY);
            var cap=old.environmentObservationWork();var queries=old.queries();var work=queries.work();
            var resumed=store.acquireNativeResume(owner,world,scope,21);
            assertNotSame(old,resumed);assertThrows(IllegalStateException.class,cap::requireCurrent);
            assertSame(queries,resumed.queries());assertEquals(work,resumed.queries().work());
            assertEquals(17,resumed.numeric().units());assertEquals(3,resumed.postprocessing().units());
            assertEquals(1,resumed.nativeAttempts());assertFalse(resumed.claimWaterForce());
            assertSame(resumed,store.acquireNativeResume(owner,world,scope,21));
            assertThrows(IllegalStateException.class,()->store.acquireNativeResume(new Object(),world,scope,22));
            assertThrows(IllegalStateException.class,()->store.acquireNativeResume(owner,world,scope,-1));
            store.invalidate();assertThrows(IllegalStateException.class,()->store.acquireNativeResume(owner,world,scope,22));
        }
    }

    @Test void retiredRecoveryObservationKeepsFrameAndRemainsObservationOnlyThroughScopeTransfer() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("recovery-observation"),PLAYER,false)) {
            var world=fixture.world();var owner=new Object();var store=new FreeTickResources.Store();var scope=scope(0);
            var old=store.acquire(owner,world,scope,20);old.numeric().charge(17);old.postprocessing().charge(3);
            old.claimNativeAttempt();assertTrue(old.claimWaterForce());old.queries().query(CAPSULE,OBSTACLE,ACCURACY);
            var oldCap=old.environmentObservationWork();var queries=old.queries();var queryWork=queries.work();
            store.invalidate();assertThrows(IllegalStateException.class,oldCap::requireCurrent);
            var fence=new java.util.concurrent.atomic.AtomicBoolean(true);
            Runnable current=()->{if(!fence.get())throw new IllegalStateException("recovery owner replaced");};
            var cap=store.acquireRecoveryObservation(owner,world,scope,21,current);
            cap.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            assertEquals(4,cap.usage().units());
            assertSame(cap,store.acquireRecoveryObservation(owner,world,scope,21,current));
            assertThrows(IllegalStateException.class,()->store.acquire(owner,world,scope,21));
            assertThrows(IllegalStateException.class,()->store.acquireRecoveryObservation(new Object(),world,scope,21,current));
            fence.set(false);assertThrows(IllegalStateException.class,cap::requireCurrent);fence.set(true);
            assertThrows(IllegalStateException.class,cap::requireCurrent,"a failed capability cannot revive after ABA");
            cap=store.acquireRecoveryObservation(owner,world,scope,21,current);
            cap.charge(EnvironmentObservationWork.Unit.COMPARISON);
            var previousCap=cap;
            cap=store.acquireRecoveryObservation(owner,world,scope,21,()->current.run());
            assertNotSame(previousCap,cap);
            assertThrows(IllegalStateException.class,previousCap::requireCurrent,
                    "a new recovery proof must retire the old proof without refunding its Frame");
            var successor=store.transferRecoveryScope(owner,world,scope,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),21);
            assertThrows(IllegalStateException.class,cap::requireCurrent);
            var installed=successor.acquire(owner,world,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),21);
            assertSame(queries,installed.queries());assertEquals(queryWork,installed.queries().work());
            assertEquals(17,installed.numeric().units());assertEquals(5,installed.postprocessing().units());
            assertEquals(1,installed.nativeAttempts());assertFalse(installed.claimWaterForce());
        }
    }

    @Test void recoveryObservationNextTickAndTerminalRetryNeverRefundPreflightWork() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("recovery-terminal"),PLAYER,false)) {
            var world=fixture.world();var owner=new Object();var store=new FreeTickResources.Store();var scope=scope(0);
            var first=store.acquire(owner,world,scope,20);first.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
            store.invalidate();var cap=store.acquireRecoveryObservation(owner,world,scope,20,()->{});
            assertThrows(FreePostprocessingWork.Exhausted.class,()->cap.charge(EnvironmentObservationWork.Unit.DISPATCH));
            var retry=store.acquireRecoveryObservation(owner,world,scope,20,()->{});
            assertThrows(FreePostprocessingWork.Exhausted.class,()->retry.charge(EnvironmentObservationWork.Unit.DISPATCH));
            assertEquals(FreePostprocessingWork.MAX_UNITS,retry.usage().units());
            world.setTotalWorldTime(world.getTotalWorldTime()+1);
            var next=store.acquireRecoveryObservation(owner,world,scope,20,()->{});
            assertThrows(IllegalStateException.class,retry::requireCurrent);
            next.charge(EnvironmentObservationWork.Unit.DISPATCH);
            assertEquals(1,next.usage().units());
            var successor=store.transferRecoveryScope(owner,world,scope,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20);
            assertThrows(IllegalStateException.class,next::requireCurrent);
            assertEquals(1,successor.acquire(owner,world,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20).postprocessing().units());
        }
    }    @Test void exhaustedRecoveryPreflightStaysTerminalAfterScopeTransferAndRejectsForeignIdentity() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("recovery-terminal-transfer"),PLAYER,false)) {
            var world=fixture.world();var owner=new Object();var store=new FreeTickResources.Store();var scope=scope(0);
            var before=store.acquire(owner,world,scope,20);
            before.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
            before.numeric().charge(17);before.claimNativeAttempt();assertTrue(before.claimWaterForce());
            store.invalidate();Runnable fence=()->{};
            var cap=store.acquireRecoveryObservation(owner,world,scope,20,fence);
            assertThrows(FreePostprocessingWork.Exhausted.class,()->cap.charge(EnvironmentObservationWork.Unit.DISPATCH));
            assertThrows(IllegalStateException.class,()->store.acquireRecoveryObservation(new Object(),world,scope,20,fence));
            assertThrows(IllegalStateException.class,()->store.acquireRecoveryObservation(owner,world,scope(1),20,fence));
            assertThrows(IllegalStateException.class,()->store.acquireRecoveryObservation(owner,world,scope,19,fence));
            var successor=store.transferRecoveryScope(owner,world,scope,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20);
            assertThrows(IllegalStateException.class,cap::requireCurrent);
            var installed=successor.acquire(owner,world,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20);
            assertTrue(installed.postprocessing().terminal());
            assertEquals(FreePostprocessingWork.MAX_UNITS,installed.postprocessing().units());
            assertThrows(FreePostprocessingWork.Exhausted.class,()->installed.postprocessing().charge(1));
            assertEquals(17,installed.numeric().units());assertEquals(1,installed.nativeAttempts());assertFalse(installed.claimWaterForce());
        }
    }    @Test void fencedHealthyRecoveryRetiresMovementHandleWithoutRefund() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("healthy-recovery"),PLAYER,false)) {
            var world=fixture.world();var owner=new Object();var store=new FreeTickResources.Store();var scope=scope(0);
            var old=store.acquire(owner,world,scope,20);old.postprocessing().charge(3);old.numeric().charge(17);
            var oldCap=old.environmentObservationWork();
            var admitted=new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable fence=()->{if(!admitted.get())throw new IllegalStateException("not a recovering owner");};
            assertThrows(IllegalStateException.class,()->store.acquireRecoveryObservation(owner,world,scope,20,fence));
            assertSame(old,store.acquire(owner,world,scope,20),"rejected admission cannot retire a healthy owner");
            admitted.set(true);
            var cap=store.acquireRecoveryObservation(owner,world,scope,20,fence);
            assertThrows(IllegalStateException.class,oldCap::requireCurrent);
            assertThrows(IllegalStateException.class,()->old.requireCurrent(owner,world,scope,20));
            assertThrows(IllegalStateException.class,()->store.acquire(owner,world,scope,20));
            assertEquals(3,cap.usage().units());cap.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            var successor=store.transferRecoveryScope(owner,world,scope,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20);
            var installed=successor.acquire(owner,world,new PlayerPhysicalSnapshot.Scope(PLAYER,SESSION,0,2),20);
            assertEquals(4,installed.postprocessing().units());assertEquals(17,installed.numeric().units());
        }
    }}
