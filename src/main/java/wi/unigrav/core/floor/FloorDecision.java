package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.WorldServer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;

/** One sampler observation pinned to a single active generation and world mutation fence. */
public final class FloorDecision {
    private final FloorRuntime runtime;
    private final ActiveFloorGeneration generation;
    private final WorldServer world;
    private final FloorWorldFields.WorldFields fields;
    private final ActiveBootRules.Snapshot bootRules;
    private final MagneticResponseRules passiveResponses;
    private final long mutationEpoch;
    private final long rosterEpoch;
    private final boolean localCompatibilityOwner;

    FloorDecision(FloorRuntime runtime, ActiveFloorGeneration generation, WorldServer world,
                  FloorWorldFields.WorldFields fields, ActiveBootRules.Snapshot bootRules,
                  MagneticResponseRules passiveResponses, long mutationEpoch, long rosterEpoch,
                  boolean localCompatibilityOwner) {
        this.runtime = runtime;
        this.generation = generation;
        this.world = Objects.requireNonNull(world, "world");
        this.fields = Objects.requireNonNull(fields, "fields");
        this.bootRules = Objects.requireNonNull(bootRules, "bootRules");
        this.passiveResponses = Objects.requireNonNull(passiveResponses, "passiveResponses");
        this.mutationEpoch = mutationEpoch;
        this.rosterEpoch = rosterEpoch;
        this.localCompatibilityOwner = localCompatibilityOwner;
    }

    public ActiveBootRules.Snapshot bootRules() {
        return bootRules;
    }

    public MagneticResponseRules passiveResponses() {
        return passiveResponses;
    }

    public long configurationRevision() {
        return generation == null ? bootRules.revision() : generation.configurationRevision();
    }

    public boolean isLocalCompatibilityOwner() {
        return localCompatibilityOwner;
    }

    public Optional<StandingSample> sampleForStanding(Vec3 position, double magneticResponse) {
        if (!isCurrent()) return Optional.empty();
        Optional<StandingSample> sample = fields.sampleForStanding(position, magneticResponse,
                world.captureBlockSnapshots || world.restoringBlockSnapshots);
        return sample.isPresent() && isCurrent() ? sample : Optional.empty();
    }

    public Optional<Sample> sample(Vec3 position, double magneticResponse) {
        if (!isCurrent()) return Optional.empty();
        Optional<Sample> sample = fields.sample(position, magneticResponse,
                world.captureBlockSnapshots || world.restoringBlockSnapshots);
        return sample.isPresent() && isCurrent() ? sample : Optional.empty();
    }

    public Optional<StandingSample> sampleForStanding(Vec3 position, double magneticResponse,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(world);
        if (!isCurrent()) return Optional.empty();
        Optional<StandingSample> sample = fields.sampleForStanding(position, magneticResponse,
                world.captureBlockSnapshots || world.restoringBlockSnapshots, work);
        work.requireCurrent();
        return sample.isPresent() && isCurrent() ? sample : Optional.empty();
    }

    public Optional<Sample> sample(Vec3 position, double magneticResponse,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(world);
        if (!isCurrent()) return Optional.empty();
        Optional<Sample> sample = fields.sample(position, magneticResponse,
                world.captureBlockSnapshots || world.restoringBlockSnapshots, work);
        work.requireCurrent();
        return sample.isPresent() && isCurrent() ? sample : Optional.empty();
    }

    public Optional<FloorDebugSnapshot> debugSnapshot(Vec3 center) {
        if (!isCurrent()) return Optional.empty();
        Optional<FloorDebugSnapshot> snapshot = fields.debugSnapshot(center,
                world.captureBlockSnapshots || world.restoringBlockSnapshots);
        return snapshot.isPresent() && isCurrent() ? snapshot : Optional.empty();
    }

    public boolean isCurrent() {
        if (world.captureBlockSnapshots || world.restoringBlockSnapshots) return false;
        if (localCompatibilityOwner) {
            return runtime == null && FloorWorldFields.isLocalOwnerCurrent(world, fields)
                    && ActiveBootRules.core().isCurrent(bootRules);
        }
        return runtime != null && generation != null && runtime.isCurrent(this, generation, world, fields,
                mutationEpoch, rosterEpoch);
    }

    WorldServer world() {
        return world;
    }

    FloorWorldFields.WorldFields fields() {
        return fields;
    }

    long mutationEpoch() {
        return mutationEpoch;
    }

    long rosterEpoch() {
        return rosterEpoch;
    }

    boolean belongsTo(FloorRuntime expectedRuntime, ActiveFloorGeneration expectedGeneration,
                      WorldServer expectedWorld, FloorWorldFields.WorldFields expectedFields,
                      long expectedMutationEpoch, long expectedRosterEpoch) {
        return runtime == expectedRuntime && generation == expectedGeneration && world == expectedWorld
                && fields == expectedFields && mutationEpoch == expectedMutationEpoch
                && rosterEpoch == expectedRosterEpoch;
    }
}
