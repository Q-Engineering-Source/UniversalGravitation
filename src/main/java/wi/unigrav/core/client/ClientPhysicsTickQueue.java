package wi.unigrav.core.client;

import java.util.ArrayDeque;
import java.util.Objects;

/** Tick-boundary scheduler for the existing capacity-limited physical packet dispatcher. */
public final class ClientPhysicsTickQueue {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    /** Called by Netty; the upstream reservation retains capacity until the task actually runs. */
    public synchronized void schedule(Runnable task) {
        tasks.addLast(Objects.requireNonNull(task, "task"));
    }

    /** ClientTick START only. Arrivals during this batch cannot extend the current tick indefinitely. */
    public void drain() {
        int count;
        synchronized (this) { count = tasks.size(); }
        for (int i = 0; i < count; i++) {
            Runnable task;
            synchronized (this) { task = tasks.removeFirst(); }
            // Leave subsequent tasks queued on failure; the dispatcher releases this task in finally.
            task.run();
        }
    }
}
