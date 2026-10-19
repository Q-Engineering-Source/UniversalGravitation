package wi.unigrav.core.floor;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.RandomAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.floor.MagneticBlockReader.MaterialMatch;
import wi.unigrav.core.player.BootSurfaceGeometry.FaceRectangle;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/**
 * Immutable qualified exterior-patch value for stable boot-memory comparison.
 *
 * <p>This is neither live-world authority nor a proof of support, visibility, fit, attachment,
 * owner validity, or an accepted body transition. Its public constructor represents only an
 * immutable value; {@link #from(BootSurfaceQuery.Measurements, BootSurfaceQuery.Candidate)} is
 * the sole path that derives one from a same-read qualified capture.
 */
public record BootSurfaceIdentity(PlayerPhysicalSnapshot.Scope scope, long rulesRevision, FaceRectangle face,
                                  Qualification qualification, List<Contributor> contributors)
        implements Comparable<BootSurfaceIdentity> {
    private static final Comparator<Contributor> CONTRIBUTOR_ORDER = Comparator.naturalOrder();

    public BootSurfaceIdentity {
        Objects.requireNonNull(scope, "scope");
        if (rulesRevision < 0L) throw new IllegalArgumentException("rulesRevision must be nonnegative");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(qualification, "qualification");
        contributors = canonicalContributors(contributors);
    }

    /**
     * Derives one identity from the exact selected candidate object in a qualified measurement.
     * Capture tick, primitive observation tick, physical revision, source position and proximity
     * are intentionally absent: they must not turn the same qualified world patch into a new value.
     */
    public static BootSurfaceIdentity from(BootSurfaceQuery.Measurements measurements,
                                           BootSurfaceQuery.Candidate candidate) {
        return from(measurements, candidate, ignored -> { });
    }

    /** Same derivation with an owner-supplied charge for variable-length identity scans. */
    public static BootSurfaceIdentity from(BootSurfaceQuery.Measurements measurements,
                                           BootSurfaceQuery.Candidate candidate,
                                           LongConsumer postprocessingWork) {
        Objects.requireNonNull(measurements, "measurements");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(postprocessingWork, "postprocessingWork");
        boolean retainedCandidate = false;
        for (BootSurfaceQuery.Candidate actual : measurements.candidates()) {
            postprocessingWork.accept(1);
            if (actual == candidate) {
                retainedCandidate = true;
                break;
            }
        }
        if (!retainedCandidate) {
            throw new IllegalArgumentException("candidate must be the exact candidate retained by measurements");
        }
        BootWorldSurfaces.QualifiedSnapshot qualified = measurements.qualifiedCapture()
                .orElseThrow(() -> new IllegalArgumentException("boot surface identity requires a qualified capture"));
        BootWorldSurfaces.Snapshot capture = measurements.capture()
                .orElseThrow(() -> new IllegalArgumentException("qualified measurements must retain their capture"));
        if (capture != qualified.snapshot()) {
            throw new IllegalArgumentException("qualified measurements do not retain the same capture instance");
        }

        var fragment = candidate.fragment();
        List<Contributor> witnesses = new ArrayList<>(fragment.contributors().size());
        for (Primitive contributor : fragment.contributors()) {
            boolean captured = false;
            for (Primitive actual : capture.primitives()) {
                postprocessingWork.accept(1);
                if (actual == contributor) {
                    captured = true;
                    break;
                }
            }
            if (!captured) {
                throw new IllegalArgumentException("fragment contributor is absent from its qualified capture");
            }
            postprocessingWork.accept(1);
            MaterialMatch materialMatch = qualified.materialMatches().get(contributor);
            if (materialMatch == null) {
                throw new IllegalArgumentException("fragment contributor lacks a same-read material match");
            }
            postprocessingWork.accept(1);
            if (!fragment.qualification().ruleId().equals(materialMatch.selectionId())) {
                throw new IllegalArgumentException("fragment qualification must match its same-read material selector");
            }
            postprocessingWork.accept(1);
            witnesses.add(witness(contributor, materialMatch, postprocessingWork));
        }
        List<Contributor> canonical = canonicalContributors(witnesses, postprocessingWork);
        // The private marker tells the public record constructor this list is already metered and canonical.
        return new BootSurfaceIdentity(measurements.source().physical().scope(), qualified.rulesRevision(),
                fragment.geometry(), fragment.qualification(), canonical);
    }
    /** Deterministic primary provenance cell for adapters; it does not establish physical support. */
    public BlockPos primaryContributorCell() {
        return contributors.getFirst().cell();
    }

    @Override
    public int compareTo(BootSurfaceIdentity other) {
        Objects.requireNonNull(other, "other");
        int compare = compareScope(scope, other.scope);
        if (compare != 0) return compare;
        compare = Long.compare(rulesRevision, other.rulesRevision);
        if (compare != 0) return compare;
        compare = compareFace(face, other.face);
        if (compare != 0) return compare;
        compare = compareQualification(qualification, other.qualification);
        if (compare != 0) return compare;
        return compareLists(contributors, other.contributors, CONTRIBUTOR_ORDER);
    }

    /** One contributor's non-live, same-read provenance. No IBlockState, TileEntity or NBT is retained. */
    public record Contributor(BlockPos cell, String blockRegistryId, List<StateProperty> listedState,
                              Aabb rawBounds, int callbackOrdinal, MaterialMatch materialMatch)
            implements Comparable<Contributor> {
        public Contributor {
            cell = Objects.requireNonNull(cell, "cell").toImmutable();
            blockRegistryId = requireNamespacedId(blockRegistryId);
            listedState = canonicalState(listedState);
            Objects.requireNonNull(rawBounds, "rawBounds");
            if (callbackOrdinal < 0) throw new IllegalArgumentException("callbackOrdinal must be nonnegative");
            Objects.requireNonNull(materialMatch, "materialMatch");
        }

        @Override
        public int compareTo(Contributor other) {
            Objects.requireNonNull(other, "other");
            int compare = compareCell(cell, other.cell);
            if (compare != 0) return compare;
            compare = blockRegistryId.compareTo(other.blockRegistryId);
            if (compare != 0) return compare;
            compare = compareLists(listedState, other.listedState, Comparator.naturalOrder());
            if (compare != 0) return compare;
            compare = compareAabb(rawBounds, other.rawBounds);
            if (compare != 0) return compare;
            compare = Integer.compare(callbackOrdinal, other.callbackOrdinal);
            if (compare != 0) return compare;
            compare = materialMatch.selectionId().compareTo(other.materialMatch.selectionId());
            if (compare != 0) return compare;
            return Integer.compare(materialMatch.evidenceKind().ordinal(), other.materialMatch.evidenceKind().ordinal());
        }
    }

    /** Canonical public listed-state name/value pair, not a mutable IProperty or IBlockState reference. */
    public record StateProperty(String name, String value) implements Comparable<StateProperty> {
        public StateProperty {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("state property name must not be blank");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public int compareTo(StateProperty other) {
            Objects.requireNonNull(other, "other");
            int compare = name.compareTo(other.name);
            return compare != 0 ? compare : value.compareTo(other.value);
        }
    }

    private static Contributor witness(Primitive primitive, MaterialMatch materialMatch, LongConsumer work) {
        IBlockState state = primitive.storedState();
        ResourceLocation name = Objects.requireNonNull(Block.REGISTRY.getNameForObject(state.getBlock()),
                "registered primitive block ID");
        if (Block.REGISTRY.getObject(name) != state.getBlock()) {
            throw new IllegalArgumentException("primitive block registry inverse lookup does not retain its exact block");
        }
        List<StateProperty> listed = listedState(state, work);
        // The private state marker records the validation and ordering work charged above.
        return new Contributor(primitive.cell(), name.toString(), listed, primitive.rawBounds(),
                primitive.ordinal(), materialMatch);
    }

    private static List<StateProperty> listedState(IBlockState state, LongConsumer work) {
        List<StateProperty> stateProperties = new ArrayList<>(state.getProperties().size());
        for (IProperty<?> property : state.getPropertyKeys()) {
            work.accept(1);
            stateProperties.add(new StateProperty(property.getName(), canonicalValue(state, property)));
        }
        return canonicalState(stateProperties, work);
    }
    private static <T extends Comparable<T>> String canonicalValue(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    private static List<Contributor> canonicalContributors(List<Contributor> contributors) {
        return canonicalContributors(contributors, ignored -> { });
    }

    private static List<Contributor> canonicalContributors(List<Contributor> contributors, LongConsumer work) {
        Objects.requireNonNull(contributors, "contributors");
        if (contributors instanceof CanonicalContributors canonical) return canonical;
        List<Contributor> ordered = new ArrayList<>(contributors.size());
        for (Contributor contributor : contributors) {
            work.accept(1);
            ordered.add(Objects.requireNonNull(contributor, "contributor"));
        }
        if (ordered.isEmpty()) throw new IllegalArgumentException("boot surface identity requires a contributor");
        boolean sorted = true;
        for (int index = 1; index < ordered.size(); index++) {
            if (compareContributors(ordered.get(index - 1), ordered.get(index), work) > 0) sorted = false;
        }
        if (!sorted) ordered.sort((left, right) -> compareContributors(left, right, work));
        return new CanonicalContributors(ordered);
    }

    private static List<StateProperty> canonicalState(List<StateProperty> listedState) {
        return canonicalState(listedState, ignored -> { });
    }

    private static List<StateProperty> canonicalState(List<StateProperty> listedState, LongConsumer work) {
        Objects.requireNonNull(listedState, "listedState");
        if (listedState instanceof CanonicalState canonical) return canonical;
        List<StateProperty> ordered = new ArrayList<>(listedState.size());
        Set<String> names = new HashSet<>();
        for (StateProperty property : listedState) {
            work.accept(1);
            StateProperty nonNullProperty = Objects.requireNonNull(property, "listed state property");
            if (!names.add(nonNullProperty.name())) {
                throw new IllegalArgumentException("listed state property names must be unique");
            }
            ordered.add(nonNullProperty);
        }
        boolean sorted = true;
        for (int index = 1; index < ordered.size(); index++) {
            work.accept(1);
            if (ordered.get(index - 1).compareTo(ordered.get(index)) > 0) sorted = false;
        }
        if (!sorted) ordered.sort((left, right) -> {
            work.accept(1);
            return left.compareTo(right);
        });
        return new CanonicalState(ordered);
    }

    private static int compareContributors(Contributor left, Contributor right, LongConsumer work) {
        work.accept(1);
        int compare = compareCell(left.cell(), right.cell());
        if (compare != 0) return compare;
        work.accept(1);
        compare = left.blockRegistryId().compareTo(right.blockRegistryId());
        if (compare != 0) return compare;
        compare = compareLists(left.listedState(), right.listedState(), Comparator.naturalOrder(), work);
        if (compare != 0) return compare;
        work.accept(1);
        compare = compareAabb(left.rawBounds(), right.rawBounds());
        if (compare != 0) return compare;
        work.accept(1);
        compare = Integer.compare(left.callbackOrdinal(), right.callbackOrdinal());
        if (compare != 0) return compare;
        work.accept(1);
        compare = left.materialMatch().selectionId().compareTo(right.materialMatch().selectionId());
        if (compare != 0) return compare;
        work.accept(1);
        return Integer.compare(left.materialMatch().evidenceKind().ordinal(), right.materialMatch().evidenceKind().ordinal());
    }

    /** Private immutable markers avoid repeating already charged canonical scans in record constructors. */
    private static final class CanonicalContributors extends AbstractList<Contributor> implements RandomAccess {
        private final List<Contributor> values;
        private CanonicalContributors(List<Contributor> values) { this.values = Collections.unmodifiableList(values); }
        @Override public Contributor get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
    }

    private static final class CanonicalState extends AbstractList<StateProperty> implements RandomAccess {
        private final List<StateProperty> values;
        private CanonicalState(List<StateProperty> values) { this.values = Collections.unmodifiableList(values); }
        @Override public StateProperty get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
    }
    private static String requireNamespacedId(String value) {
        if (value == null || value.isBlank() || value.indexOf(':') <= 0 || value.indexOf(':') == value.length() - 1) {
            throw new IllegalArgumentException("blockRegistryId must be an exact namespaced ID");
        }
        return value;
    }

    private static int compareScope(PlayerPhysicalSnapshot.Scope left, PlayerPhysicalSnapshot.Scope right) {
        int compare = left.playerId().compareTo(right.playerId());
        if (compare != 0) return compare;
        compare = left.sessionId().compareTo(right.sessionId());
        if (compare != 0) return compare;
        compare = Integer.compare(left.dimension(), right.dimension());
        return compare != 0 ? compare : Long.compare(left.worldEpoch(), right.worldEpoch());
    }

    private static int compareFace(FaceRectangle left, FaceRectangle right) {
        int compare = Integer.compare(left.outward().ordinal(), right.outward().ordinal());
        return compare != 0 ? compare : compareAabb(left.bounds(), right.bounds());
    }

    private static int compareQualification(Qualification left, Qualification right) {
        int compare = left.ruleId().compareTo(right.ruleId());
        if (compare != 0) return compare;
        var leftTier = left.effectiveTier();
        var rightTier = right.effectiveTier();
        compare = Double.compare(leftTier.profile().strength(), rightTier.profile().strength());
        if (compare != 0) return compare;
        compare = Double.compare(leftTier.profile().plateau(), rightTier.profile().plateau());
        if (compare != 0) return compare;
        compare = Double.compare(leftTier.profile().range(), rightTier.profile().range());
        if (compare != 0) return compare;
        compare = Double.compare(leftTier.captureReachMetres(), rightTier.captureReachMetres());
        return compare != 0 ? compare : Long.compare(leftTier.jumpProtectionTicks(), rightTier.jumpProtectionTicks());
    }

    private static int compareCell(BlockPos left, BlockPos right) {
        int compare = Integer.compare(left.getX(), right.getX());
        if (compare != 0) return compare;
        compare = Integer.compare(left.getY(), right.getY());
        return compare != 0 ? compare : Integer.compare(left.getZ(), right.getZ());
    }

    private static int compareAabb(Aabb left, Aabb right) {
        int compare = compareVec(left.min(), right.min());
        return compare != 0 ? compare : compareVec(left.max(), right.max());
    }

    private static int compareVec(Vec3d left, Vec3d right) {
        int compare = Double.compare(left.x(), right.x());
        if (compare != 0) return compare;
        compare = Double.compare(left.y(), right.y());
        return compare != 0 ? compare : Double.compare(left.z(), right.z());
    }

    private static <T> int compareLists(List<T> left, List<T> right, Comparator<? super T> comparator) {
        return compareLists(left, right, comparator, ignored -> { });
    }

    private static <T> int compareLists(List<T> left, List<T> right, Comparator<? super T> comparator,
                                        LongConsumer work) {
        int length = Math.min(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            work.accept(1);
            int compare = comparator.compare(left.get(index), right.get(index));
            if (compare != 0) return compare;
        }
        work.accept(1);
        return Integer.compare(left.size(), right.size());
    }
}
