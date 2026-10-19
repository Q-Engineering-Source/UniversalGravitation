package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Invocation-local native output provenance. Geometry and block-callback permission remain distinct. */
public final class NativeCollisionProvenance {
    private NativeCollisionProvenance() { }
    /** Minted only at this capture's real finite declaration/final-list boundary. */
    static final class DeclarationLimit extends IllegalStateException {
        private final int limit;
        private final long attempted;
        private final int accepted;
        private DeclarationLimit(String detail, int limit, long attempted, int accepted) {
            super(detail); this.limit = limit; this.attempted = attempted; this.accepted = accepted;
        }
        int limit() { return limit; }
        long attempted() { return attempted; }
        int accepted() { return accepted; }
    }
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<NativeCall>> NATIVES = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<CollectorCall>> COLLECTORS = new ThreadLocal<>();
    enum Kind { BLOCK, UNATTRIBUTED, AMBIGUOUS }
    private record Producer(BlockPos cell, IBlockState state, long invocation, boolean blockRead) { }
    private record BoundsBits(long x0, long y0, long z0, long x1, long y1, long z1) {
        static BoundsBits of(AxisAlignedBB box) {
            return new BoundsBits(Double.doubleToLongBits(box.minX), Double.doubleToLongBits(box.minY), Double.doubleToLongBits(box.minZ),
                    Double.doubleToLongBits(box.maxX), Double.doubleToLongBits(box.maxY), Double.doubleToLongBits(box.maxZ));
        }
    }
    private static final class Claim {
        final Producer producer; final Aabb geometry; final BoundsBits bits; final ArrayList<Producer> origins = new ArrayList<>(); boolean ambiguous;
        Claim(Producer producer, Aabb geometry, AxisAlignedBB object) {
            this.producer = producer; this.geometry = geometry; bits = BoundsBits.of(object); origins.add(producer);
        }
    }
    static final class Entry {
        private final int ordinal; private final Kind kind; private final Producer producer; private final Aabb bounds;
        private final List<Producer> origins; private final BoundsBits bits;
        private Entry(int ordinal, Kind kind, Claim claim, Aabb bounds, AxisAlignedBB object) {
            this.ordinal = ordinal; this.kind = kind; this.producer = claim == null ? null : claim.producer; this.bounds = bounds;
            origins = claim == null ? List.of() : List.copyOf(claim.origins); bits = BoundsBits.of(object);
        }
        int ordinal() { return ordinal; }
        Kind kind() { return kind; }
        Aabb bounds() { return bounds; }
        BlockPos cell() { if (kind != Kind.BLOCK) throw new IllegalStateException("entry has no block authority"); return producer.cell(); }
        IBlockState state() { if (kind != Kind.BLOCK) throw new IllegalStateException("entry has no block authority"); return producer.state(); }
        /** Cross-capture producer description only; neither ordinal nor AABB object identity crosses scopes. */
        boolean sameBlockDescription(Entry other, EnvironmentObservationWork work) {
            work.charge(EnvironmentObservationWork.Unit.COMPARISON);
            return other != null && kind == Kind.BLOCK && other.kind == Kind.BLOCK && bits.equals(other.bits)
                    && producer.cell.equals(other.producer.cell) && producer.state == other.producer.state
                    && producer.blockRead && other.producer.blockRead;
        }
    }
    static final class Observation {
        private final Scope scope; private final List<Entry> entries;
        private Observation(Scope scope, List<Entry> entries) { this.scope = scope; this.entries = List.copyOf(entries); }
        List<Entry> entries() { return entries; }
        boolean sameDescription(Observation other) {
            scope.check();
            if (other == null || scope.transaction != other.scope.transaction || entries.size() != other.entries.size()) return false;
            other.scope.check();
            for (int n = 0; n < entries.size(); n++) {
                scope.charge(EnvironmentObservationWork.Unit.COMPARISON);
                var a = entries.get(n); var b = other.entries.get(n);
                if (a.ordinal != b.ordinal || a.kind != b.kind || !a.bits.equals(b.bits)) return false;
                if (a.origins.size() != b.origins.size()) return false;
                for (int nOrigin = 0; nOrigin < a.origins.size(); nOrigin++) {
                    scope.charge(EnvironmentObservationWork.Unit.COMPARISON);
                    var left = a.origins.get(nOrigin); var right = b.origins.get(nOrigin);
                    if (!left.cell.equals(right.cell) || left.state != right.state || left.invocation != right.invocation
                            || left.blockRead != right.blockRead) return false;
                }
            }
            scope.check(); other.scope.check(); return true;
        }
        boolean matchesOwner(FreeOwnerTransaction exactTransaction) {
            scope.check();
            return scope.transaction == exactTransaction;
        }
        void requireOwnerCurrent() { scope.check(); }
    }
    static final class Scope implements AutoCloseable {
        private final FreeOwnerTransaction transaction;
        private final EnvironmentObservationWork work;
        private final AxisAlignedBB query;
        private final long maxCalls;
        private final int maxOutputs;
        private final IdentityHashMap<AxisAlignedBB, Claim> claims = new IdentityHashMap<>();
        private long calls; private int outputs; private boolean claimed; private boolean collectorSeen; private boolean nativeReturned; private boolean closed;
        private List<AxisAlignedBB> nativeList;
        private RuntimeException terminalFailure;
        private Scope(FreeOwnerTransaction transaction, AxisAlignedBB query, EnvironmentObservationWork work,
                      long maxCalls, int maxOutputs) {
            this.transaction = transaction; this.query = query; this.work = work; this.maxCalls = maxCalls; this.maxOutputs = maxOutputs;
        }
        private void check() {
            if (terminalFailure != null) throw terminalFailure;
            try { transaction.requireCurrent(); work.requireCurrent(transaction.world()); }
            catch (RuntimeException failure) { terminalFailure = failure; throw failure; }
        }
        private void charge(EnvironmentObservationWork.Unit unit) {
            try { check(); work.charge(unit); }
            catch (RuntimeException failure) { terminalFailure = failure; throw failure; }
        }
        private RuntimeException fail(String message) {
            var failure = new IllegalStateException(message); terminalFailure = failure; return failure;
        }
        private DeclarationLimit declarationLimit(String message, long attempted) {
            var failure = new DeclarationLimit(message, maxOutputs, attempted, outputs);
            terminalFailure = failure; return failure;
        }
        private Aabb declaration(AxisAlignedBB box) {
            try {
                charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
                if (outputs >= maxOutputs) throw declarationLimit("native producer declaration limit exhausted: " + maxOutputs, (long) outputs + 1);
                outputs++;
                charge(EnvironmentObservationWork.Unit.COMPARISON);
                return copy(box);
            } catch (RuntimeException failure) { terminalFailure = failure; throw failure; }
        }
        private void record(AxisAlignedBB box, Producer producer, Aabb raw) {
            Claim previous = claims.get(box);
            if (previous == null) claims.put(box, new Claim(producer, raw, box));
            else {
                charge(EnvironmentObservationWork.Unit.COMPARISON);
                if (previous.origins.getLast().invocation != producer.invocation) {
                    previous.origins.add(producer); previous.ambiguous = true;
                }
                if (!previous.bits.equals(BoundsBits.of(box))) previous.ambiguous = true;
            }
        }
        private Aabb copy(AxisAlignedBB box) {
            Objects.requireNonNull(box, "native AABB");
            for (int axis = 0; axis < 6; axis++) charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                    || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                    || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ)
                throw new IllegalArgumentException("native producer returned malformed bounds");
            return new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ));
        }
        Observation finish(List<AxisAlignedBB> finalList) {
            check();
            if (closed || !claimed || !collectorSeen || !nativeReturned || nativeList != finalList)
                throw new IllegalStateException("native collision provenance hook/list is missing or incomplete");
            if (finalList.size() > maxOutputs) throw declarationLimit("native final candidate limit exhausted", finalList.size());
            var counts = new IdentityHashMap<AxisAlignedBB, Integer>();
            for (var box : finalList) {
                charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
                counts.put(box, counts.getOrDefault(box, 0) + 1);
            }
            var entries = new ArrayList<Entry>(finalList.size());
            for (int ordinal = 0; ordinal < finalList.size(); ordinal++) {
                charge(EnvironmentObservationWork.Unit.COMPARISON);
                var box = finalList.get(ordinal); var raw = copy(box); var claim = claims.get(box);
                boolean ambiguous = counts.get(box) != 1 || claim != null && claim.ambiguous;
                Kind kind = ambiguous ? Kind.AMBIGUOUS : claim == null || !claim.bits.equals(BoundsBits.of(box))
                        || !claim.producer.blockRead ? Kind.UNATTRIBUTED : Kind.BLOCK;
                entries.add(new Entry(ordinal, kind, claim, raw, box));
            }
            check(); return new Observation(this, entries);
        }
        @Override public void close() {
            if (closed) return;
            var scopes = SCOPES.get();
            if (scopes == null || scopes.peek() != this) throw new IllegalStateException("native collision scopes closed out of order");
            scopes.pop(); if (scopes.isEmpty()) SCOPES.remove(); closed = true;
        }
    }
    static Scope open(FreeOwnerTransaction transaction, AxisAlignedBB query, EnvironmentObservationWork work,
                      CollisionQueryFootprint footprint, int maxOutputs) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(query); Objects.requireNonNull(work);
        if (work != transaction.resources().environmentObservationWork()) throw new IllegalArgumentException("foreign native observation work");
        Objects.requireNonNull(footprint);
        var planned = footprint.nativeBounds();
        if (query.minX != planned.min().x() || query.minY != planned.min().y() || query.minZ != planned.min().z()
                || query.maxX != planned.max().x() || query.maxY != planned.max().y() || query.maxZ != planned.max().z())
            throw new IllegalArgumentException("native provenance query differs from planned bounds");
        long maxCalls = footprint.blockCellsUpperBound();
        if (maxCalls < 0 || maxOutputs < 1) throw new IllegalArgumentException("finite native limits required");
        var scope = new Scope(transaction, query, work, maxCalls, maxOutputs); scope.check();
        var scopes = SCOPES.get(); if (scopes == null) { scopes = new ArrayDeque<>(); SCOPES.set(scopes); }
        scopes.push(scope); return scope;
    }
    public static boolean scoped() { var stack = SCOPES.get(); return stack != null && !stack.isEmpty(); }
    private static Scope topScope() { var stack = SCOPES.get(); return stack == null ? null : stack.peek(); }
    public static final class NativeCall implements AutoCloseable {
        private final Scope scope; private final World world; private final Entity actor; private final AxisAlignedBB query;
        private NativeCall(Scope scope, World world, Entity actor, AxisAlignedBB query) {
            this.scope = scope; this.world = world; this.actor = actor; this.query = query;
        }
        public void returned(List<AxisAlignedBB> result) {
            if (scope != null) { scope.check(); if (scope.nativeList != result) throw new IllegalStateException("native collision list replaced"); scope.nativeReturned = true; }
        }
        @Override public void close() { pop(NATIVES, this); }
    }
    public static NativeCall enterNative(World world, Entity actor, AxisAlignedBB query) {
        var scope = topScope();
        boolean active = scope != null && !scope.claimed && scope.transaction.world() == world
                && scope.transaction.actor() == actor && scope.query == query;
        if (active) { scope.check(); scope.claimed = true; }
        var call = new NativeCall(active ? scope : null, world, actor, query);
        var stack = NATIVES.get(); if (stack == null) { stack = new ArrayDeque<>(); NATIVES.set(stack); } stack.push(call); return call;
    }
    public static final class CollectorCall implements AutoCloseable {
        private final NativeCall nativeCall; private final List<AxisAlignedBB> list;
        private BlockPos readCell; private IBlockState readState;
        private CollectorCall(NativeCall nativeCall, List<AxisAlignedBB> list) { this.nativeCall = nativeCall; this.list = list; }
        private Scope active() { return nativeCall == null ? null : nativeCall.scope; }
        @Override public void close() { readCell = null; readState = null; pop(COLLECTORS, this); }
    }
    public static CollectorCall enterCollector(World world, Entity actor, AxisAlignedBB query, List<AxisAlignedBB> list) {
        var natives = NATIVES.get(); var nativeCall = natives == null ? null : natives.peek();
        if (nativeCall != null && (nativeCall.world != world || nativeCall.actor != actor || nativeCall.query != query)) nativeCall = null;
        if (nativeCall != null && nativeCall.scope != null) {
            var scope = nativeCall.scope; scope.check();
            if (scope.collectorSeen) throw new IllegalStateException("native collision collector entered twice");
            scope.collectorSeen = true; scope.nativeList = list;
        }
        var call = new CollectorCall(nativeCall, list);
        var stack = COLLECTORS.get(); if (stack == null) { stack = new ArrayDeque<>(); COLLECTORS.set(stack); } stack.push(call); return call;
    }
    private static CollectorCall collector() { var stack = COLLECTORS.get(); return stack == null ? null : stack.peek(); }
    public static IBlockState read(World world, BlockPos position, Supplier<IBlockState> original) {
        var call = collector(); var scope = call == null ? null : call.active();
        if (scope == null) return original.get();
        scope.check(); call.readCell = null; call.readState = null;
        if (world != call.nativeCall.world) throw new IllegalStateException("foreign native block read");
        scope.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        var cell = position.toImmutable();
        scope.charge(EnvironmentObservationWork.Unit.DISPATCH);
        IBlockState state;
        try { state = original.get(); scope.check(); }
        catch (RuntimeException failure) { scope.terminalFailure = failure; throw failure; }
        if (!cell.equals(position)) throw new IllegalStateException("native block-read position changed during callback");
        call.readCell = cell; call.readState = state; return state;
    }
    public static void produce(IBlockState state, World world, BlockPos position, AxisAlignedBB query,
            List<AxisAlignedBB> list, Entity actor, boolean actualState, Consumer<List<AxisAlignedBB>> original) {
        var call = collector(); var scope = call == null ? null : call.active();
        if (scope == null) { original.accept(list); return; }
        scope.check();
        if (call.list != list || call.nativeCall.world != world || call.nativeCall.actor != actor || call.nativeCall.query != query)
            throw new IllegalStateException("foreign native producer invocation");
        scope.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
        if (scope.calls >= scope.maxCalls) throw scope.fail("native producer call limit exhausted: " + scope.maxCalls);
        long invocation = ++scope.calls;
        boolean read = call.readState == state && call.readCell != null && call.readCell.equals(position);
        scope.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        var producer = new Producer(position.toImmutable(), state, invocation, read);
        call.readCell = null; call.readState = null;
        var observer = new OutputList(list, scope, producer);
        scope.charge(EnvironmentObservationWork.Unit.DISPATCH);
        try { original.accept(observer); scope.check(); }
        catch (RuntimeException failure) { scope.terminalFailure = failure; throw failure; }
        finally { observer.close(); }
    }
    /** Only this callback's actual writes claim objects. No suffix or post-return list scan. */
    private static final class OutputList extends AbstractList<AxisAlignedBB> {
        private final List<AxisAlignedBB> delegate; private final Scope scope; private final Producer producer; private boolean closed;
        private OutputList(List<AxisAlignedBB> delegate, Scope scope, Producer producer) { this.delegate = delegate; this.scope = scope; this.producer = producer; }
        private void live() { scope.check(); if (closed) throw new IllegalStateException("native producer list used after callback"); }
        private void close() { closed = true; }
        @Override public AxisAlignedBB get(int index) { live(); return delegate.get(index); }
        @Override public int size() { live(); return delegate.size(); }
        @Override public void add(int index, AxisAlignedBB box) {
            live(); var raw = scope.declaration(box); delegate.add(index, box); scope.record(box, producer, raw); modCount++;
        }
        @Override public AxisAlignedBB set(int index, AxisAlignedBB box) {
            live(); var raw = scope.declaration(box); var previous = delegate.set(index, box); scope.record(box, producer, raw); return previous;
        }
        @Override public AxisAlignedBB remove(int index) { live(); var removed = delegate.remove(index); modCount++; return removed; }
        @Override public boolean addAll(int index, Collection<? extends AxisAlignedBB> values) {
            live(); Objects.checkIndex(index, size() + 1);
            // Snapshot before writes preserves addAll(self/subList) and avoids iterator invalidation.
            var copy = new ArrayList<AxisAlignedBB>();
            for (var value : values) {
                scope.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
                if (scope.outputs + copy.size() >= scope.maxOutputs)
                    throw scope.declarationLimit("native producer declaration limit exhausted during addAll", (long) scope.outputs + copy.size() + 1);
                copy.add(value);
            }
            if (copy.isEmpty()) return false;
            for (var value : copy) add(index++, value);
            return true;
        }
        @Override public boolean addAll(Collection<? extends AxisAlignedBB> values) { return addAll(size(), values); }
    }
    private static <T> void pop(ThreadLocal<ArrayDeque<T>> local, T exact) {
        var stack = local.get(); if (stack == null || stack.peek() != exact) throw new IllegalStateException("native collision invocation closed out of order");
        stack.pop(); if (stack.isEmpty()) local.remove();
    }
}
