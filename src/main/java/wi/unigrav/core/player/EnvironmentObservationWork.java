package wi.unigrav.core.player;

import java.util.Objects;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;

/** Observation-only authority over the owner's existing tick ledgers. */
public final class EnvironmentObservationWork {
    public enum Unit { CLASSIFICATION, COMPARISON, FACE_PREDICATE, COORDINATE_CONVERSION, DISPATCH }
    public record Usage(long units, int nativeAttempts, BootQueryArithmetic.Usage numeric) { }

    private final FreeTickResources resources;
    private final BootQueryArithmetic arithmetic;

    EnvironmentObservationWork(FreeTickResources resources) {
        this.resources = Objects.requireNonNull(resources);
        arithmetic = BootQueryArithmetic.fromSameWork(resources.numeric(), this::requireCurrent);
    }

    public void requireCurrent() { resources.requireObservationCurrent(); }
    public void requireCurrent(World world) { resources.requireObservationWorld(world); }
    public void requireCurrent(Entity actor) { resources.requireObservationActor(actor); }
    public void requireCurrent(World world, PlayerPhysicalSnapshot.Scope scope) {
        resources.requireObservationScope(world, scope);
    }

    /** A dispatch bounds callback attempts, not work performed inside third-party code. */
    public void charge(Unit unit) {
        Objects.requireNonNull(unit);
        requireCurrent();
        resources.postprocessing().charge(unit);
    }

    /** Called immediately before the actual native aggregate collision callback. */
    public void claimNativeAttempt() { resources.claimObservationNativeAttempt(); }

    /** Only existing exact geometry paths may consume this shared numeric capability. */
    public BootQueryArithmetic arithmetic() { requireCurrent(); return arithmetic; }

    public Usage usage() {
        requireCurrent();
        return new Usage(resources.postprocessing().units(), resources.nativeAttempts(), arithmetic.usage());
    }
}
