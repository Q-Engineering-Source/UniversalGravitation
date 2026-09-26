package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class RemoteBoostBoundedDispatcherTest {
    @Test
    void preservesPerPacketScheduledOrderAndHoldsTokensUntilExecution() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        List<String> order = new ArrayList<>();
        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(2,
                scheduled::addLast);
        assertTrue(submit(dispatcher, "FRAME1", order::add));
        scheduled.addLast(() -> order.add("CP1"));
        assertTrue(submit(dispatcher, "FRAME2", order::add));
        scheduled.addLast(() -> order.add("CP2"));
        assertFalse(submit(dispatcher, "overflow", order::add));
        assertEquals(2, dispatcher.outstanding());
        while (!scheduled.isEmpty()) scheduled.removeFirst().run();
        assertEquals(List.of("FRAME1", "CP1", "FRAME2", "CP2"), order);
        assertEquals(0, dispatcher.outstanding());
    }

    @Test
    void reserveThenInvalidateThenSubmitDropsOldValueAndReleasesOnce() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        List<String> values = new ArrayList<>();
        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(1,
                scheduled::addLast);
        RemoteBoostBoundedDispatcher.Reservation<String> old = dispatcher.reserve("future-mode", values::add);
        assertTrue(old != null);
        dispatcher.invalidate();
        dispatcher.submit(old);
        assertEquals(1, dispatcher.outstanding());
        scheduled.removeFirst().run();
        assertEquals(0, dispatcher.outstanding());
        assertEquals(List.of(), values);

        assertTrue(submit(dispatcher, "new-mode", values::add));
        scheduled.removeFirst().run();
        assertEquals(List.of("new-mode"), values);
    }

    @Test
    void schedulerFailureBeforeExecutionCancelsPendingCallbackForRuntimeExceptionAndError() {
        ArrayDeque<Runnable> queuedAfterRuntimeFailure = new ArrayDeque<>();
        AtomicInteger runtimeConsumed = new AtomicInteger();
        RemoteBoostBoundedDispatcher<String> beforeRun = new RemoteBoostBoundedDispatcher<>(1, task -> {
            queuedAfterRuntimeFailure.addLast(task);
            throw new IllegalStateException("scheduler failed");
        });
        assertThrows(IllegalStateException.class,
                () -> submit(beforeRun, "x", ignored -> runtimeConsumed.incrementAndGet()));
        assertEquals(0, beforeRun.outstanding());
        queuedAfterRuntimeFailure.removeFirst().run();
        assertEquals(0, runtimeConsumed.get());
        assertEquals(0, beforeRun.outstanding());

        ArrayDeque<Runnable> queuedAfterFailure = new ArrayDeque<>();
        AtomicInteger consumed = new AtomicInteger();
        RemoteBoostBoundedDispatcher<String> error = new RemoteBoostBoundedDispatcher<>(1, task -> {
            queuedAfterFailure.addLast(task);
            throw new AssertionError("scheduler failed");
        });
        assertThrows(AssertionError.class,
                () -> submit(error, "x", ignored -> consumed.incrementAndGet()));
        assertEquals(0, error.outstanding());
        queuedAfterFailure.removeFirst().run();
        assertEquals(0, consumed.get());
        assertEquals(0, error.outstanding());
    }

    @Test
    void consumerFailureAndSchedulerThrowAfterInlineRunReleaseExactlyOnce() {
        RemoteBoostBoundedDispatcher<String> inline = new RemoteBoostBoundedDispatcher<>(1, Runnable::run);
        assertThrows(IllegalStateException.class,
                () -> submit(inline, "x", ignored -> { throw new IllegalStateException("consumer failed"); }));
        assertEquals(0, inline.outstanding());

        AtomicInteger consumed = new AtomicInteger();
        RemoteBoostBoundedDispatcher<String> ranThenThrew = new RemoteBoostBoundedDispatcher<>(1, task -> {
            task.run();
            throw new IllegalStateException("scheduler failed after run");
        });
        assertThrows(IllegalStateException.class,
                () -> submit(ranThenThrew, "x", ignored -> consumed.incrementAndGet()));
        assertEquals(1, consumed.get());
        assertEquals(0, ranThenThrew.outstanding());
    }

    @Test
    void schedulerAndConsumerRunOutsideDispatcherMonitorAndLeaveNoToken() {
        AtomicReference<RemoteBoostBoundedDispatcher<String>> holder = new AtomicReference<>();
        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(1, task -> {
            assertFalse(Thread.holdsLock(holder.get()));
            task.run();
        });
        holder.set(dispatcher);
        assertTrue(submit(dispatcher, "x", ignored -> assertFalse(Thread.holdsLock(dispatcher))));
        assertEquals(0, dispatcher.outstanding());
    }

    @Test
    void startedAsyncTaskRetainsCapacityWhenSchedulerThrowsUntilItsFinallyRuns() throws Exception {
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch allowConsumerFinish = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicReference<Thread> workerReference = new AtomicReference<>();
        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(1, task -> {
            Thread worker = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    taskFinished.countDown();
                }
            }, "remote-boost-dispatcher-test");
            worker.setDaemon(true);
            workerReference.set(worker);
            worker.start();
            await(consumerStarted);
            throw new AssertionError("scheduler failed after asynchronous start");
        });
        try {
            assertThrows(AssertionError.class, () ->
                    submit(dispatcher, "x", ignored -> {
                        consumerStarted.countDown();
                        await(allowConsumerFinish);
                    }));
            assertEquals(1, dispatcher.outstanding());
        } finally {
            allowConsumerFinish.countDown();
        }
        assertTrue(taskFinished.await(2, TimeUnit.SECONDS));
        Thread worker = workerReference.get();
        assertTrue(worker != null);
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertEquals(0, dispatcher.outstanding());
    }

    @Test
    void duplicateCallbackCannotExecuteOrReleaseItsTicketTwice() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        AtomicInteger consumed = new AtomicInteger();
        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(1,
                scheduled::addLast);
        assertTrue(submit(dispatcher, "x", ignored -> consumed.incrementAndGet()));
        Runnable callback = scheduled.removeFirst();
        callback.run();
        callback.run();
        assertEquals(1, consumed.get());
        assertEquals(0, dispatcher.outstanding());
    }

    @Test
    void invalidReservationsDoNotReleaseAnotherDispatchersToken() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        RemoteBoostBoundedDispatcher<String> owner = new RemoteBoostBoundedDispatcher<>(1,
                scheduled::addLast);
        RemoteBoostBoundedDispatcher<String> other = new RemoteBoostBoundedDispatcher<>(1,
                task -> { });
        RemoteBoostBoundedDispatcher.Reservation<String> reservation = owner.reserve("x", ignored -> { });
        assertTrue(reservation != null);

        assertThrows(IllegalArgumentException.class,
                () -> other.submit(reservation));
        assertThrows(IllegalArgumentException.class,
                () -> owner.submit(null));
        assertEquals(1, owner.outstanding());
        owner.submit(reservation);
        scheduled.removeFirst().run();
        assertEquals(0, owner.outstanding());
    }

    @Test
    void reserveThenSubmitPatternDoesNotHoldAuthorityWhileSchedulerWaitsForQueue() throws Exception {
        Object queueMonitor = new Object();
        Object authorityMonitor = new Object();
        CountDownLatch queueHeld = new CountDownLatch(1);
        CountDownLatch schedulerAttemptedQueue = new CountDownLatch(1);
        CountDownLatch authorityProbed = new CountDownLatch(1);
        CountDownLatch releaseQueue = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();

        Thread queueHolder = new Thread(() -> {
            synchronized (queueMonitor) {
                queueHeld.countDown();
                await(releaseQueue);
            }
        }, "remote-boost-queue-holder");
        queueHolder.setDaemon(true);
        queueHolder.start();
        await(queueHeld);

        RemoteBoostBoundedDispatcher<String> dispatcher = new RemoteBoostBoundedDispatcher<>(1, task -> {
            schedulerAttemptedQueue.countDown();
            synchronized (queueMonitor) {
                task.run();
            }
        });
        Thread submitter = new Thread(() -> {
            RemoteBoostBoundedDispatcher.Reservation<String> reservation;
            synchronized (authorityMonitor) {
                reservation = dispatcher.reserve("x", ignored -> {
                    synchronized (authorityMonitor) { consumed.incrementAndGet(); }
                });
            }
            dispatcher.submit(reservation);
            submitted.countDown();
        }, "remote-boost-submitter");
        submitter.setDaemon(true);
        submitter.start();
        await(schedulerAttemptedQueue);

        Thread authorityProbe = new Thread(() -> {
            synchronized (authorityMonitor) { authorityProbed.countDown(); }
        }, "remote-boost-authority-probe");
        authorityProbe.setDaemon(true);
        authorityProbe.start();
        try {
            assertTrue(authorityProbed.await(2, TimeUnit.SECONDS));
            assertEquals(1L, submitted.getCount());
            releaseQueue.countDown();
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseQueue.countDown();
            queueHolder.join(2_000);
            submitter.join(2_000);
            authorityProbe.join(2_000);
        }
        assertFalse(queueHolder.isAlive());
        assertFalse(submitter.isAlive());
        assertFalse(authorityProbe.isAlive());
        assertEquals(1, consumed.get());
        assertEquals(0, dispatcher.outstanding());
    }

    private static boolean submit(RemoteBoostBoundedDispatcher<String> dispatcher, String value,
            Consumer<String> consumer) {
        RemoteBoostBoundedDispatcher.Reservation<String> reservation = dispatcher.reserve(value, consumer);
        if (reservation == null) return false;
        dispatcher.submit(reservation);
        return true;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
