package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        Objects.requireNonNull(measurements, "measurements");
        Objects.requireNonNull(candidate, "candidate");
        if (measurements.candidates().stream().noneMatch(actual -> actual == candidate)) {
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
            if (!capture.primitives().contains(contributor)) {
                throw new IllegalArgumentException("fragment contributor is absent from its qualified capture");
            }
            MaterialMatch materialMatch = qualified.materialMatches().get(contributor);
            if (materialMatch == null) {
                throw new IllegalArgumentException("fragment contributor lacks a same-read material match");
            }
            if (!fragment.qualification().ruleId().equals(materialMatch.selectionId())) {
                throw new IllegalArgumentException("fragment qualification must match its same-read material selector");
            }
            witnesses.add(witness(contributor, materialMatch));
        }
        return new BootSurfaceIdentity(measurements.source().physical().scope(), qualified.rulesRevision(),
                fragment.geometry(), fragment.qualification(), witnesses);
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

    private static Contributor witness(Primitive primitive, MaterialMatch materialMatch) {
        IBlockState state = primitive.storedState();
        ResourceLocation name = Objects.requireNonNull(Block.REGISTRY.getNameForObject(state.getBlock()),
                "registered primitive block ID");
        if (Block.REGISTRY.getObject(name) != state.getBlock()) {
            throw new IllegalArgumentException("primitive block registry inverse lookup does not retain its exact block");
        }
        return new Contributor(primitive.cell(), name.toString(), listedState(state), primitive.rawBounds(),
                primitive.ordinal(), materialMatch);
    }

    private static List<StateProperty> listedState(IBlockState state) {
        List<StateProperty> stateProperties = new ArrayList<>(state.getProperties().size());
        for (IProperty<?> property : state.getPropertyKeys()) {
            stateProperties.add(new StateProperty(property.getName(), canonicalValue(state, property)));
        }
        return canonicalState(stateProperties);
    }

    private static <T extends Comparable<T>> String canonicalValue(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    private static List<Contributor> canonicalContributors(List<Contributor> contributors) {
        Objects.requireNonNull(contributors, "contributors");
        List<Contributor> ordered = new ArrayList<>(contributors.size());
        for (Contributor contributor : contributors) ordered.add(Objects.requireNonNull(contributor, "contributor"));
        if (ordered.isEmpty()) throw new IllegalArgumentException("boot surface identity requires a contributor");
        ordered.sort(CONTRIBUTOR_ORDER);
        return List.copyOf(ordered);
    }

    private static List<StateProperty> canonicalState(List<StateProperty> listedState) {
        Objects.requireNonNull(listedState, "listedState");
        List<StateProperty> ordered = new ArrayList<>(listedState.size());
        Set<String> names = new HashSet<>();
        for (StateProperty property : listedState) {
            StateProperty nonNullProperty = Objects.requireNonNull(property, "listed state property");
            if (!names.add(nonNullProperty.name())) {
                throw new IllegalArgumentException("listed state property names must be unique");
            }
            ordered.add(nonNullProperty);
        }
        ordered.sort(Comparator.naturalOrder());
        return List.copyOf(ordered);
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
        int length = Math.min(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            int compare = comparator.compare(left.get(index), right.get(index));
            if (compare != 0) return compare;
        }
        return Integer.compare(left.size(), right.size());
    }
}
