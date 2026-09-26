package wi.unigrav.core.boost;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Pure bounded reservation dispatcher; tokens release only in the exact task. */
public final class RemoteBoostBoundedDispatcher<T> {
    public interface Scheduler { void schedule(Runnable task); }

    private final int capacity;
    private final Scheduler scheduler;
    private int outstanding;
    private long generation;

    public RemoteBoostBoundedDispatcher(int capacity, Scheduler scheduler) {
        if (capacity <= 0 || scheduler == null) throw new IllegalArgumentException("Invalid dispatcher");
        this.capacity = capacity;
        this.scheduler = scheduler;
    }

    public synchronized Reservation<T> reserve(T value, Consumer<T> consumer) {
        if (outstanding >= capacity) return null;
        outstanding++;
        return new Reservation<>(this, value, consumer, generation);
    }

    public void submit(Reservation<T> reservation) {
        synchronized (this) {
            if (reservation == null || reservation.owner != this || reservation.submitted) {
                throw new IllegalArgumentException("Invalid dispatcher reservation");
            }
            reservation.submitted = true;
        }
        Runnable task = () -> {
            if (!reservation.start()) return;
            try {
                synchronized (RemoteBoostBoundedDispatcher.this) {
                    if (reservation.generation != generation) return;
                }
                reservation.consumer.accept(reservation.value);
            } finally {
                if (reservation.finish()) release();
            }
        };
        try {
            scheduler.schedule(task);
        } catch (RuntimeException | Error failure) {
            if (reservation.cancelPending()) release();
            throw failure;
        }
    }

    public synchronized void invalidate() { generation++; }

    synchronized int outstanding() { return outstanding; }

    private synchronized void release() { outstanding--; }

    public static final class Reservation<T> {
        private enum State { PENDING, RUNNING, CANCELLED, FINISHED }

        private final RemoteBoostBoundedDispatcher<T> owner;
        private final T value;
        private final Consumer<T> consumer;
        private final long generation;
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        private boolean submitted;
        private Reservation(RemoteBoostBoundedDispatcher<T> owner, T value, Consumer<T> consumer,
                long generation) {
            this.owner = owner;
            this.value = value;
            this.consumer = consumer;
            this.generation = generation;
        }

        private boolean start() { return state.compareAndSet(State.PENDING, State.RUNNING); }

        private boolean cancelPending() { return state.compareAndSet(State.PENDING, State.CANCELLED); }

        private boolean finish() { return state.compareAndSet(State.RUNNING, State.FINISHED); }
    }
}
