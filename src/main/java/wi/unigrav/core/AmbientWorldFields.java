package wi.unigrav.core;

import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraft.server.MinecraftServer;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.EnvironmentObservationWork;
import wi.unigrav.core.player.EnvironmentObservationWork.Unit;

/** World identity is the ownership boundary; a dimension number is only a source selector. */
public final class AmbientWorldFields {
    private static final Map<World, Owner> OWNERS = new IdentityHashMap<>();
    private static final class Owner {
        final GravityField field = new GravityField();
        boolean ready;
    }

    /** Optional selection for process-wide contributions. Unmarked entries apply to every world. */
    public interface Selection {
        boolean appliesTo(World world);
    }

    interface SnapshotContribution extends GravityContribution {
        GravityContribution snapshot();
        boolean isCurrent(GravityContribution snapshot);
    }
    private record Observed(GravityContribution original, GravityContribution sample) {
        static Observed capture(GravityContribution original, EnvironmentObservationWork work) {
            charge(work, Unit.CLASSIFICATION);
            if (original instanceof SnapshotContribution) charge(work, Unit.DISPATCH);
            return new Observed(original, original instanceof SnapshotContribution source
                    ? source.snapshot() : original);
        }
        boolean isCurrent(EnvironmentObservationWork work) {
            charge(work, Unit.CLASSIFICATION);
            if (original instanceof SnapshotContribution) charge(work, Unit.DISPATCH);
            return !(original instanceof SnapshotContribution source) || source.isCurrent(sample);
        }
    }
    private AmbientWorldFields() {}

    public static synchronized void load(World world) {
        Objects.requireNonNull(world, "world");
        OWNERS.computeIfAbsent(world, ignored -> new Owner()).ready = true;
    }

    static synchronized void prepare(World world) {
        OWNERS.computeIfAbsent(world, ignored -> new Owner());
    }

    public static synchronized void retire(World world) { OWNERS.remove(world); }

    public static synchronized void stop(MinecraftServer server) {
        OWNERS.keySet().removeIf(world -> world.getMinecraftServer() == server);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static synchronized void onWorldLoad(WorldEvent.Load event) {
        OWNERS.computeIfAbsent(event.getWorld(), ignored -> new Owner()).ready = false;
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWorldReady(WorldEvent.Load event) { load(event.getWorld()); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void onWorldUnload(WorldEvent.Unload event) { retire(event.getWorld()); }

    /** Registration is rejected after retirement, rather than implicitly resurrecting the world. */
    public static synchronized GravityField field(World world) {
        Owner owner = OWNERS.get(world);
        if (owner == null) throw new IllegalStateException("ambient gravity world is not loaded");
        return owner.field;
    }

    public static synchronized Decision capture(World world) {
        return captureInternal(world, null);
    }

    public static synchronized Decision capture(World world, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(world);
        return captureInternal(world, work);
    }

    private static Decision captureInternal(World world, EnvironmentObservationWork work) {
        Owner owner = OWNERS.get(world);
        return owner == null || !owner.ready ? null : new Decision(world, owner,
                owner.field.observe(), GravityFields.get().observe(), work);
    }

    private static void charge(EnvironmentObservationWork work, Unit unit) {
        if (work != null) work.charge(unit);
    }

    public static final class Decision {
        private final World world;
        private final Owner owner;
        private final GravityField.Observation local;
        private final GravityField.Observation shared;
        private final List<Observed> localEntries;
        private final List<Observed> sharedEntries;
        private final EnvironmentObservationWork work;
        private Decision(World world, Owner owner, GravityField.Observation local,
                         GravityField.Observation shared, EnvironmentObservationWork work) {
            this.world = world; this.owner = owner; this.local = local; this.shared = shared;
            this.work = work;
            this.localEntries = local.entries().values().stream().map(entry -> Observed.capture(entry, work)).toList();
            this.sharedEntries = shared.entries().values().stream().map(entry -> Observed.capture(entry, work)).toList();
        }
        public long worldRevision() { return local.revision(); }
        public long sharedRevision() { return shared.revision(); }
        public boolean isCurrent() {
            if (work != null) work.requireCurrent();
            synchronized (AmbientWorldFields.class) {
                if (!metadataCurrent()) return false;
                boolean current = localEntries.stream().allMatch(entry -> entry.isCurrent(work))
                        && sharedEntries.stream().allMatch(entry -> entry.isCurrent(work));
                // A current callback may retire this owner or mutate either roster before returning true.
                if (work != null) work.requireCurrent();
                return current && metadataCurrent();
            }
        }
        private boolean metadataCurrent() {
            return OWNERS.get(world) == owner && owner.ready && owner.field.isCurrent(local)
                    && GravityFields.get().isCurrent(shared);
        }
        public Vec3 accelerationAt(Vec3 position, double time) {
            return accelerationAt(position, time, null);
        }
        Vec3 accelerationAt(Vec3 position, double time, GravityContribution excluded) {
            if (work != null) work.requireCurrent();
            Vec3 total = Vec3.ZERO;
            for (Observed entry : sharedEntries) {
                GravityContribution contribution = entry.original();
                charge(work, Unit.COMPARISON);
                if (contribution == excluded) continue;
                charge(work, Unit.CLASSIFICATION);
                if (contribution instanceof Selection selection) {
                    charge(work, Unit.DISPATCH);
                    if (!selection.appliesTo(world)) continue;
                }
                {
                    charge(work, Unit.DISPATCH);
                    total = total.add(entry.sample().accelerationAt(position, time));
                }
            }
            for (Observed entry : localEntries) {
                GravityContribution contribution = entry.original();
                charge(work, Unit.COMPARISON);
                if (contribution != excluded) {
                    charge(work, Unit.DISPATCH);
                    total = total.add(entry.sample().accelerationAt(position, time));
                }
            }
            return total;
        }
    }
}
