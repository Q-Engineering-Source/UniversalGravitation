package wi.unigrav.api.field;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.math.Vec3;

/**
 * Registry of gravity contributions. Entries are keyed by a
 * {@code namespace:path} pair (deliberately not Minecraft's
 * {@code ResourceLocation}, so this package stays free of MC dependencies).
 * Registering under an existing id replaces the old entry, following the
 * registration style of Revo UI's FlightApi: registration returns an
 * idempotent {@link Registration} handle; closing it unregisters the entry
 * (and does nothing if the id has since been replaced by a newer entry).
 *
 * <p>Two kinds of entries share the key space:
 * <ul>
 *   <li>{@link GravitySource} — massive bodies (point mass / harmonics);
 *       source-only views ({@link #snapshot()}, {@link #sources()}) see just
 *       these, so e.g. orbit-center lookups by id never pick up a plain
 *       contribution by accident;
 *   <li>any other {@link GravityContribution} — e.g. a uniform world-gravity
 *       field; visible via {@link #contributions()}.
 * </ul>
 * {@link #accelerationAt(Vec3, double)} superposes everything.
 *
 * <p>Thread-safe. Units as elsewhere: positions in m, times in s.
 */
public final class GravityField {

    /** Registration key: a {@code namespace:path} pair. */
    public record Id(String namespace, String path) {
        public Id {
            if (namespace == null || namespace.isBlank()
                    || path == null || path.isBlank()) {
                throw new IllegalArgumentException("namespace and path must be non-blank");
            }
        }

        @Override
        public String toString() {
            return namespace + ":" + path;
        }
    }

    /** Handle to one registration; {@link #close()} unregisters, idempotently. */
    public interface Registration extends AutoCloseable {
        Id id();

        /** The registered entry as a source, or {@code null} if it is a plain contribution. */
        GravitySource source();

        /** The registered entry. */
        GravityContribution contribution();

        @Override
        void close();
    }

    private final ConcurrentHashMap<Id, GravityContribution> entries = new ConcurrentHashMap<>();
    private long revision;

    /** An immutable contribution roster and its mutation revision, captured atomically. */
    public record Observation(long revision, Map<Id, GravityContribution> entries) {
        public Observation { entries = Map.copyOf(entries); }
    }

    public synchronized Observation observe() {
        return new Observation(revision, entries);
    }

    public synchronized boolean isCurrent(Observation observation) {
        return observation != null && observation.revision() == revision;
    }

    /** Registers (or replaces, for an existing id) a gravity source. */
    public Registration register(String namespace, String path, GravitySource source) {
        return register(new Id(namespace, path), source);
    }

    /** Registers (or replaces, for an existing id) a gravity source. */
    public Registration register(Id id, GravitySource source) {
        return registerContribution(id, source);
    }

    /** Registers (or replaces, for an existing id) a gravity contribution. */
    public Registration registerContribution(String namespace, String path,
                                             GravityContribution contribution) {
        return registerContribution(new Id(namespace, path), contribution);
    }

    /** Registers (or replaces, for an existing id) a gravity contribution. */
    public synchronized Registration registerContribution(Id id, GravityContribution contribution) {
        if (contribution == null) {
            throw new IllegalArgumentException("contribution must not be null");
        }
        entries.put(id, contribution);
        revision++;
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public Id id() {
                return id;
            }

            @Override
            public GravitySource source() {
                return contribution instanceof GravitySource source ? source : null;
            }

            @Override
            public GravityContribution contribution() {
                return contribution;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    // Identity comparison, NOT ConcurrentHashMap.remove(k, v):
                    // value equality would let a stale registration remove a
                    // newer entry whose record equals() matches (observed
                    // live: replacing the MP player's EntitySource with the
                    // SP one — equal entities, record equals — wiped the
                    // player source right out of the field).
                    synchronized (GravityField.this) {
                        if (entries.get(id) == contribution) {
                            entries.remove(id);
                            revision++;
                        }
                    }
                }
            }
        };
    }

    /** Removes the entry with the given id; returns true if one was present. */
    public synchronized boolean unregister(Id id) {
        boolean removed = entries.remove(id) != null;
        if (removed) revision++;
        return removed;
    }

    /**
     * Superposition of the accelerations of all registered entries, in m/s².
     */
    public Vec3 accelerationAt(Vec3 pos, double time) {
        Vec3 total = Vec3.ZERO;
        for (GravityContribution contribution : entries.values()) {
            total = total.add(contribution.accelerationAt(pos, time));
        }
        return total;
    }

    /** Immutable snapshot of the current gravity sources (contributions excluded). */
    public Map<Id, GravitySource> snapshot() {
        Map<Id, GravitySource> result = new LinkedHashMap<>();
        entries.forEach((id, contribution) -> {
            if (contribution instanceof GravitySource source) {
                result.put(id, source);
            }
        });
        return Map.copyOf(result);
    }

    /** Immutable snapshot of the current plain contributions (sources excluded). */
    public Map<Id, GravityContribution> contributions() {
        Map<Id, GravityContribution> result = new LinkedHashMap<>();
        entries.forEach((id, contribution) -> {
            if (!(contribution instanceof GravitySource)) {
                result.put(id, contribution);
            }
        });
        return Map.copyOf(result);
    }

    /** Immutable snapshot of the current sources. */
    public List<GravitySource> sources() {
        return entries.values().stream()
                .filter(GravitySource.class::isInstance)
                .map(GravitySource.class::cast)
                .toList();
    }

    public int size() {
        return entries.size();
    }
}
