package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;
import wi.unigrav.api.math.Vec3;

/**
 * Immutable, section-addressed coverage of the floor emitters owned by one chunk.
 *
 * <p>Each plane group stores one 16 by 16 tangent page as four primitive words.
 * Groups in a face/normal bucket have disjoint masks, so one physical cell can
 * contribute at most one source for a given face.</p>
 */
final class FloorChunkCoverage {

    record Mask256(long w0, long w1, long w2, long w3) {
        boolean contains(int bit) {
            if (bit < 0 || bit >= 256) {
                throw new IllegalArgumentException("coverage bit must be in [0, 256)");
            }
            long word = switch (bit >>> 6) {
                case 0 -> w0;
                case 1 -> w1;
                case 2 -> w2;
                case 3 -> w3;
                default -> throw new AssertionError("validated mask word");
            };
            return (word & (1L << (bit & 63))) != 0;
        }

        int cardinality() {
            return Long.bitCount(w0) + Long.bitCount(w1) + Long.bitCount(w2) + Long.bitCount(w3);
        }

        private long word(int index) {
            return switch (index) {
                case 0 -> w0;
                case 1 -> w1;
                case 2 -> w2;
                case 3 -> w3;
                default -> throw new IllegalArgumentException("mask word must be in [0, 4)");
            };
        }

        private Mask256 with(int bit) {
            long flag = 1L << (bit & 63);
            return switch (bit >>> 6) {
                case 0 -> new Mask256(w0 | flag, w1, w2, w3);
                case 1 -> new Mask256(w0, w1 | flag, w2, w3);
                case 2 -> new Mask256(w0, w1, w2 | flag, w3);
                case 3 -> new Mask256(w0, w1, w2, w3 | flag);
                default -> throw new IllegalArgumentException("coverage bit must be in [0, 256)");
            };
        }

        private Mask256 without(int bit) {
            long flag = ~(1L << (bit & 63));
            return switch (bit >>> 6) {
                case 0 -> new Mask256(w0 & flag, w1, w2, w3);
                case 1 -> new Mask256(w0, w1 & flag, w2, w3);
                case 2 -> new Mask256(w0, w1, w2 & flag, w3);
                case 3 -> new Mask256(w0, w1, w2, w3 & flag);
                default -> throw new IllegalArgumentException("coverage bit must be in [0, 256)");
            };
        }

        private boolean intersects(Mask256 other) {
            return (w0 & other.w0) != 0 || (w1 & other.w1) != 0
                    || (w2 & other.w2) != 0 || (w3 & other.w3) != 0;
        }
    }

    record PlaneGroup(Kind kind, FloorFieldProfile profile, Mask256 cells) {
        PlaneGroup {
            if (kind == null || profile == null || cells == null) {
                throw new IllegalArgumentException("plane group fields must not be null");
            }
        }
    }

    record Stats(long physicalBlockCount, long emitterCount, long groupCount,
                 long nonemptySectionCount, long maskPayloadBytes,
                 long tangentNormalLookupFaceCount, long tangentNormalLookupPayloadBytes) {
    }

    record BucketKey(int sectionY, Face face, int localNormal) {
        BucketKey {
            if (face == null || localNormal < 0 || localNormal >= 16) {
                throw new IllegalArgumentException("coverage bucket identity is invalid");
            }
        }
    }

    static final class SectionCoverage {
        private static final int BUCKETS_PER_FACE = 16;
        private static final int TANGENT_BITS_PER_FACE = 256;
        private static final long TANGENT_NORMAL_LOOKUP_BYTES = TANGENT_BITS_PER_FACE * Short.BYTES;
        private final List<PlaneGroup>[] groups;
        private final int[] normalPresence;
        private final short[][] normalPresenceByTangentBit;

        @SuppressWarnings("unchecked")
        private SectionCoverage(List<PlaneGroup>[] source) {
            this(source, null, null, null);
        }

        @SuppressWarnings("unchecked")
        private SectionCoverage(List<PlaneGroup>[] source, SectionCoverage base,
                                boolean[] changedFaces, short[][] lookupUpdates) {
            groups = new List[Face.values().length * BUCKETS_PER_FACE];
            normalPresence = new int[Face.values().length];
            normalPresenceByTangentBit = new short[Face.values().length][];
            for (Face face : Face.values()) {
                for (int normal = 0; normal < BUCKETS_PER_FACE; normal++) {
                    List<PlaneGroup> bucket = source[index(face, normal)];
                    if (bucket == null || bucket.isEmpty()) {
                        groups[index(face, normal)] = List.of();
                    } else {
                        groups[index(face, normal)] = List.copyOf(bucket);
                        normalPresence[face.ordinal()] |= 1 << normal;
                    }
                }
                int faceIndex = face.ordinal();
                int presence = normalPresence[faceIndex];
                if (Integer.bitCount(presence) <= 1) {
                    continue;
                }
                if (base != null && !changedFaces[faceIndex]) {
                    normalPresenceByTangentBit[faceIndex] = base.normalPresenceByTangentBit[faceIndex];
                } else if (lookupUpdates != null && lookupUpdates[faceIndex] != null) {
                    normalPresenceByTangentBit[faceIndex] = lookupUpdates[faceIndex];
                } else {
                    normalPresenceByTangentBit[faceIndex] = buildNormalPresenceByTangentBit(face);
                }
            }
        }

        List<PlaneGroup> groups(Face face, int localNormal) {
            if (face == null) {
                throw new IllegalArgumentException("face must not be null");
            }
            if (localNormal < 0 || localNormal >= BUCKETS_PER_FACE) {
                throw new IllegalArgumentException("localNormal must be in [0, 16)");
            }
            return groups[index(face, localNormal)];
        }

        int normalPresence(Face face) {
            if (face == null) {
                throw new IllegalArgumentException("face must not be null");
            }
            return normalPresence[face.ordinal()];
        }

        int normalPresenceAtBit(Face face, int tangentBit) {
            requireFaceAndBit(face, tangentBit);
            short[] lookup = normalPresenceByTangentBit[face.ordinal()];
            if (lookup != null) {
                return lookup[tangentBit] & 0xFFFF;
            }
            int presence = normalPresence[face.ordinal()];
            if (presence == 0) {
                return 0;
            }
            if (Integer.bitCount(presence) != 1) {
                throw new IllegalStateException("multi-normal face is missing its tangent lookup");
            }
            int localNormal = Integer.numberOfTrailingZeros(presence);
            return groupAt(face, localNormal, tangentBit) == null ? 0 : presence;
        }

        PlaneGroup groupAt(Face face, int localNormal, int tangentBit) {
            requireFaceAndBit(face, tangentBit);
            if (localNormal < 0 || localNormal >= BUCKETS_PER_FACE) {
                throw new IllegalArgumentException("localNormal must be in [0, 16)");
            }
            // Compile/editor boundaries reject overlapping masks. Keep this hot
            // lookup proportional to groups before the unique match, not the
            // whole profile bucket.
            List<PlaneGroup> planeGroups = groups[index(face, localNormal)];
            for (int groupIndex = 0; groupIndex < planeGroups.size(); groupIndex++) {
                PlaneGroup group = planeGroups.get(groupIndex);
                if (group.cells().contains(tangentBit)) {
                    return group;
                }
            }
            return null;
        }

        long tangentNormalLookupFaceCount() {
            long count = 0L;
            for (short[] lookup : normalPresenceByTangentBit) {
                if (lookup != null) {
                    count++;
                }
            }
            return count;
        }

        long tangentNormalLookupPayloadBytes() {
            return Math.multiplyExact(tangentNormalLookupFaceCount(), TANGENT_NORMAL_LOOKUP_BYTES);
        }

        void addTangentNormalLookupIdentities(Set<short[]> target) {
            if (target == null) {
                throw new IllegalArgumentException("lookup identity target must not be null");
            }
            for (short[] lookup : normalPresenceByTangentBit) {
                if (lookup != null) {
                    target.add(lookup);
                }
            }
        }

        boolean isEmpty() {
            for (int presence : normalPresence) {
                if (presence != 0) {
                    return false;
                }
            }
            return true;
        }

        private static int index(Face face, int localNormal) {
            return face.ordinal() * BUCKETS_PER_FACE + localNormal;
        }

        private short[] buildNormalPresenceByTangentBit(Face face) {
            short[] lookup = new short[TANGENT_BITS_PER_FACE];
            int presence = normalPresence[face.ordinal()];
            while (presence != 0) {
                int localNormal = Integer.numberOfTrailingZeros(presence);
                presence &= presence - 1;
                int normalBit = 1 << localNormal;
                for (PlaneGroup group : groups[index(face, localNormal)]) {
                    Mask256 mask = group.cells();
                    for (int wordIndex = 0; wordIndex < 4; wordIndex++) {
                        long word = mask.word(wordIndex);
                        while (word != 0L) {
                            int tangentBit = wordIndex * Long.SIZE + Long.numberOfTrailingZeros(word);
                            word &= word - 1L;
                            lookup[tangentBit] = (short) (lookup[tangentBit] | normalBit);
                        }
                    }
                }
            }
            return lookup;
        }

        private static void requireFaceAndBit(Face face, int tangentBit) {
            if (face == null) {
                throw new IllegalArgumentException("face must not be null");
            }
            if (tangentBit < 0 || tangentBit >= TANGENT_BITS_PER_FACE) {
                throw new IllegalArgumentException("tangent bit must be in [0, 256)");
            }
        }
    }

    private final ChunkKey key;
    private final Map<Integer, SectionCoverage> sections;
    private final int[] sectionYs;
    private volatile Stats stats;

    private FloorChunkCoverage(ChunkKey key, Map<Integer, SectionCoverage> sections, Stats stats) {
        this.key = key;
        this.sections = Map.copyOf(sections);
        this.sectionYs = sections.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        this.stats = stats;
    }

    private FloorChunkCoverage(ChunkKey key, Map<Integer, SectionCoverage> sections) {
        this(key, sections, null);
    }

    static int singleDescriptor(Kind kind, Face face) {
        if (kind == null || face == null) {
            throw new IllegalArgumentException("descriptor kind and face must not be null");
        }
        return kind == Kind.GRAVITY ? 1 << face.ordinal() : 1 << (6 + face.ordinal());
    }

    static int doubleGravityDescriptor(Face first, Face second) {
        if (first == null || second == null || first == second || opposite(first) != second) {
            throw new IllegalArgumentException("double gravity descriptor requires opposite faces");
        }
        return (1 << first.ordinal()) | (1 << second.ordinal());
    }

    private static Face opposite(Face face) {
        return switch (face) {
            case DOWN -> Face.UP;
            case UP -> Face.DOWN;
            case NORTH -> Face.SOUTH;
            case SOUTH -> Face.NORTH;
            case WEST -> Face.EAST;
            case EAST -> Face.WEST;
        };
    }

    static boolean validDescriptor(int descriptor) {
        if (descriptor == 0) {
            return true;
        }
        if ((descriptor & ~0xFFF) != 0) {
            return false;
        }
        int gravityFaces = descriptor & 0x3F;
        int magneticFaces = descriptor >>> 6;
        return magneticFaces == 0 ? gravityFaces != 0 && (gravityFaces & ~0x3F) == 0
                : gravityFaces == 0 && Integer.bitCount(magneticFaces) == 1;
    }

    private static int descriptorFaceMask(int descriptor) {
        return (descriptor & 0x3F) != 0 ? descriptor & 0x3F : descriptor >>> 6;
    }

    private static Kind descriptorKind(int descriptor) {
        return (descriptor & 0xFC0) != 0 ? Kind.MAGNETIC : Kind.GRAVITY;
    }

    static Editor edit(FloorChunkCoverage base) {
        if (base == null) {
            throw new IllegalArgumentException("base coverage must not be null");
        }
        return new Editor(base);
    }

    static FloorChunkCoverage compile(ChunkKey key, List<Source> sources) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (sources == null) {
            throw new IllegalArgumentException("sources must not be null");
        }

        Map<Integer, SectionBuilder> builders = new HashMap<>();
        Set<EmitterKey> emitters = new HashSet<>();
        Set<BlockKey> physicalBlocks = new HashSet<>();
        for (Source source : sources) {
            if (source == null) {
                throw new IllegalArgumentException("sources must not contain null");
            }
            if (!sourceChunk(source).equals(key)) {
                throw new IllegalArgumentException("source does not belong to replacement chunk");
            }
            if (!emitters.add(new EmitterKey(source.x(), source.y(), source.z(), source.face()))) {
                throw new IllegalArgumentException("sources must not duplicate an emitter face");
            }
            physicalBlocks.add(new BlockKey(source.x(), source.y(), source.z()));

            int sectionY = Math.floorDiv(source.y(), 16);
            int localY = Math.floorMod(source.y(), 16);
            int localNormal = switch (source.face()) {
                case DOWN, UP -> localY;
                case NORTH, SOUTH -> Math.floorMod(source.z(), 16);
                case WEST, EAST -> Math.floorMod(source.x(), 16);
            };
            int localA;
            int localB;
            switch (source.face()) {
                case DOWN, UP -> {
                    localA = Math.floorMod(source.x(), 16);
                    localB = Math.floorMod(source.z(), 16);
                }
                case NORTH, SOUTH -> {
                    localA = Math.floorMod(source.x(), 16);
                    localB = localY;
                }
                case WEST, EAST -> {
                    localA = localY;
                    localB = Math.floorMod(source.z(), 16);
                }
                default -> throw new IllegalStateException("unknown floor face");
            }
            int bit = localA + 16 * localB;
            SectionBuilder section = builders.computeIfAbsent(sectionY, ignored -> new SectionBuilder());
            BucketBuilder bucket = section.bucket(source.face(), localNormal);
            GroupKey groupKey = GroupKey.of(source.kind(), source.profile());
            Mask256 previous = bucket.groups.getOrDefault(groupKey, EMPTY_MASK);
            if (previous.contains(bit)) {
                throw new IllegalArgumentException("sources must not duplicate a cell face");
            }
            bucket.groups.put(groupKey, previous.with(bit));
        }

        Map<Integer, SectionCoverage> sections = new HashMap<>(builders.size());
        Map<Mask256, Mask256> canonicalMasks = new HashMap<>();
        long groupCount = 0;
        long maskPayloadBytes = 0;
        for (Map.Entry<Integer, SectionBuilder> sectionEntry : builders.entrySet()) {
            @SuppressWarnings("unchecked")
            List<PlaneGroup>[] groups = new List[Face.values().length * 16];
            SectionBuilder builder = sectionEntry.getValue();
            for (Face face : Face.values()) {
                for (int localNormal = 0; localNormal < 16; localNormal++) {
                    BucketBuilder bucket = builder.buckets[face.ordinal()][localNormal];
                    if (bucket == null || bucket.groups.isEmpty()) {
                        continue;
                    }
                    List<PlaneGroup> planes = new ArrayList<>(bucket.groups.size());
                    List<Mask256> masks = new ArrayList<>(bucket.groups.size());
                    for (Map.Entry<GroupKey, Mask256> group : bucket.groups.entrySet()) {
                        Mask256 mask = canonicalMasks.computeIfAbsent(group.getValue(), ignored -> group.getValue());
                        planes.add(new PlaneGroup(group.getKey().kind, group.getKey().profile, mask));
                        masks.add(mask);
                    }
                    for (int i = 0; i < masks.size(); i++) {
                        for (int j = i + 1; j < masks.size(); j++) {
                            if (masks.get(i).intersects(masks.get(j))) {
                                throw new IllegalArgumentException("coverage groups overlap within one face plane");
                            }
                        }
                    }
                    groups[SectionCoverage.index(face, localNormal)] = List.copyOf(planes);
                    groupCount = Math.addExact(groupCount, planes.size());
                }
            }
            sections.put(sectionEntry.getKey(), new SectionCoverage(groups));
        }
        maskPayloadBytes = Math.multiplyExact((long) canonicalMasks.size(), 32L);
        long nonemptySections = sections.size();
        long lookupFaces = 0L;
        for (SectionCoverage section : sections.values()) {
            lookupFaces = Math.addExact(lookupFaces, section.tangentNormalLookupFaceCount());
        }
        Stats stats = new Stats(physicalBlocks.size(), emitters.size(), groupCount,
                nonemptySections, maskPayloadBytes, lookupFaces,
                Math.multiplyExact(lookupFaces, SectionCoverage.TANGENT_NORMAL_LOOKUP_BYTES));
        return new FloorChunkCoverage(key, sections, stats);
    }

    static Builder builder(ChunkKey key) {
        return new Builder(key);
    }

    static final class Builder {
        private final ChunkKey key;
        private final Map<Integer, SectionBuilder> sections = new HashMap<>();

        private Builder(ChunkKey key) {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            this.key = key;
        }

        void addCell(int x, int y, int z, int descriptor, FloorFieldProfile profile) {
            if (Math.floorDiv(x, 16) != key.x() || Math.floorDiv(z, 16) != key.z()) {
                throw new IllegalArgumentException("source cell does not belong to coverage chunk");
            }
            if (!validDescriptor(descriptor)) {
                throw new IllegalArgumentException("floor descriptor is invalid");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
            int faceMask = descriptorFaceMask(descriptor);
            if (faceMask == 0) {
                return;
            }
            Kind kind = descriptorKind(descriptor);
            int sectionY = Math.floorDiv(y, 16);
            SectionBuilder section = sections.computeIfAbsent(sectionY, ignored -> new SectionBuilder());
            for (Face face : Face.values()) {
                if ((faceMask & (1 << face.ordinal())) == 0) {
                    continue;
                }
                section.addCell(face, localNormal(face, x, y, z), bitForCell(face, x, y, z), kind, profile);
            }
        }

        FloorChunkCoverage build() {
            Map<Integer, SectionCoverage> built = new HashMap<>(sections.size());
            for (Map.Entry<Integer, SectionBuilder> section : sections.entrySet()) {
                SectionCoverage coverage = section.getValue().finish();
                if (!coverage.isEmpty()) {
                    built.put(section.getKey(), coverage);
                }
            }
            return new FloorChunkCoverage(key, built);
        }
    }

    SectionCoverage section(int sectionY) {
        return sections.get(sectionY);
    }

    int sectionCount() {
        return sections.size();
    }

    int sectionYAt(int index) {
        return sectionYs[index];
    }

    Stats stats() {
        Stats current = stats;
        if (current != null) {
            return current;
        }
        current = computeStats();
        stats = current;
        return current;
    }

    void addTangentNormalLookupIdentities(Set<short[]> target) {
        if (target == null) {
            throw new IllegalArgumentException("lookup identity target must not be null");
        }
        for (SectionCoverage section : sections.values()) {
            section.addTangentNormalLookupIdentities(target);
        }
    }

    boolean belongsTo(ChunkKey candidate) {
        return key.equals(candidate);
    }

    boolean matchesCell(int x, int y, int z, int descriptor, FloorFieldProfile profile) {
        int faceMask = descriptorFaceMask(descriptor);
        Kind kind = descriptorKind(descriptor);
        SectionCoverage section = section(Math.floorDiv(y, 16));
        for (Face face : Face.values()) {
            boolean expected = (faceMask & (1 << face.ordinal())) != 0;
            PlaneGroup found = null;
            if (section != null) {
                int normal = localNormal(face, x, y, z);
                int bit = bitForCell(face, x, y, z);
                for (PlaneGroup group : section.groups(face, normal)) {
                    if (group.cells().contains(bit)) {
                        found = group;
                        break;
                    }
                }
            }
            if (expected != (found != null)) {
                return false;
            }
            if (expected && (found.kind() != kind || !sameProfile(found.profile(), profile))) {
                return false;
            }
        }
        return true;
    }

    List<Source> sources() {
        List<Source> sources = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < sectionCount(); sectionIndex++) {
            int sectionY = sectionYAt(sectionIndex);
            SectionCoverage section = section(sectionY);
            for (Face face : Face.values()) {
                int presence = section.normalPresence(face);
                while (presence != 0) {
                    int normal = Integer.numberOfTrailingZeros(presence);
                    presence &= presence - 1;
                    for (PlaneGroup group : section.groups(face, normal)) {
                        for (int bit = 0; bit < 256; bit++) {
                            if (!group.cells().contains(bit)) {
                                continue;
                            }
                            int localA = bit & 15;
                            int localB = bit >>> 4;
                            int x;
                            int y;
                            int z;
                            switch (face) {
                                case DOWN, UP -> {
                                    x = Math.addExact(Math.multiplyExact(key.x(), 16), localA);
                                    y = Math.addExact(Math.multiplyExact(sectionY, 16), normal);
                                    z = Math.addExact(Math.multiplyExact(key.z(), 16), localB);
                                }
                                case NORTH, SOUTH -> {
                                    x = Math.addExact(Math.multiplyExact(key.x(), 16), localA);
                                    y = Math.addExact(Math.multiplyExact(sectionY, 16), localB);
                                    z = Math.addExact(Math.multiplyExact(key.z(), 16), normal);
                                }
                                case WEST, EAST -> {
                                    x = Math.addExact(Math.multiplyExact(key.x(), 16), normal);
                                    y = Math.addExact(Math.multiplyExact(sectionY, 16), localA);
                                    z = Math.addExact(Math.multiplyExact(key.z(), 16), localB);
                                }
                                default -> throw new IllegalStateException("unknown floor face");
                            }
                            sources.add(new Source(x, y, z, face, group.kind(), group.profile()));
                        }
                    }
                }
            }
        }
        return List.copyOf(sources);
    }

    boolean hasEmitterAt(int x, int y, int z, Face face) {
        if (face == null) {
            throw new IllegalArgumentException("face must not be null");
        }
        if (Math.floorDiv(x, 16) != key.x() || Math.floorDiv(z, 16) != key.z()) {
            return false;
        }
        SectionCoverage section = sections.get(Math.floorDiv(y, 16));
        if (section == null) {
            return false;
        }
        int normal = switch (face) {
            case DOWN, UP -> Math.floorMod(y, 16);
            case NORTH, SOUTH -> Math.floorMod(z, 16);
            case WEST, EAST -> Math.floorMod(x, 16);
        };
        int bit = bitForCell(face, x, y, z);
        for (PlaneGroup group : section.groups(face, normal)) {
            if (group.cells().contains(bit)) {
                return true;
            }
        }
        return false;
    }

    private Stats computeStats() {
        Set<Mask256> masks = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BlockKey> blocks = new HashSet<>();
        long emitters = 0;
        long groups = 0;
        for (int sectionIndex = 0; sectionIndex < sectionCount(); sectionIndex++) {
            int sectionY = sectionYAt(sectionIndex);
            SectionCoverage section = section(sectionY);
            for (Face face : Face.values()) {
                int presence = section.normalPresence(face);
                while (presence != 0) {
                    int normal = Integer.numberOfTrailingZeros(presence);
                    presence &= presence - 1;
                    for (PlaneGroup group : section.groups(face, normal)) {
                        groups = Math.addExact(groups, 1L);
                        emitters = Math.addExact(emitters, group.cells().cardinality());
                        masks.add(group.cells());
                        for (int bit = 0; bit < 256; bit++) {
                            if (group.cells().contains(bit)) {
                                blocks.add(sourceBlock(face, sectionY, normal, bit));
                            }
                        }
                    }
                }
            }
        }
        long lookupFaces = 0L;
        for (SectionCoverage section : sections.values()) {
            lookupFaces = Math.addExact(lookupFaces, section.tangentNormalLookupFaceCount());
        }
        return new Stats(blocks.size(), emitters, groups, sections.size(),
                Math.multiplyExact((long) masks.size(), 32L), lookupFaces,
                Math.multiplyExact(lookupFaces, SectionCoverage.TANGENT_NORMAL_LOOKUP_BYTES));
    }

    Map<Double, Long> rangeCounts(List<BucketKey> buckets) {
        if (buckets == null) {
            throw new IllegalArgumentException("coverage buckets must not be null");
        }
        Map<Double, Long> counts = new HashMap<>();
        for (BucketKey bucket : buckets) {
            SectionCoverage section = sections.get(bucket.sectionY());
            if (section == null) {
                continue;
            }
            for (PlaneGroup group : section.groups(bucket.face(), bucket.localNormal())) {
                counts.merge(group.profile().range(), (long) group.cells().cardinality(), Math::addExact);
            }
        }
        return counts;
    }

    List<Source> nearbySources(Vec3 center, double halfExtent, int maximumSources) {
        if (center == null || !Double.isFinite(center.x()) || !Double.isFinite(center.y())
                || !Double.isFinite(center.z()) || !Double.isFinite(halfExtent) || halfExtent < 0.0
                || maximumSources < 0) {
            throw new IllegalArgumentException("debug source window must be finite and nonnegative");
        }
        List<Source> result = new ArrayList<>(maximumSources < 63 ? maximumSources + 1 : 64);
        int xBase = Math.multiplyExact(key.x(), 16);
        int zBase = Math.multiplyExact(key.z(), 16);
        for (int sectionIndex = 0; sectionIndex < sectionCount(); sectionIndex++) {
            int sectionY = sectionYAt(sectionIndex);
            int yBase = Math.multiplyExact(sectionY, 16);
            SectionCoverage section = section(sectionY);
            for (Face face : Face.values()) {
                int presence = section.normalPresence(face);
                while (presence != 0) {
                    int localNormal = Integer.numberOfTrailingZeros(presence);
                    presence &= presence - 1;
                    for (PlaneGroup group : section.groups(face, localNormal)) {
                        for (int wordIndex = 0; wordIndex < 4; wordIndex++) {
                            long word = group.cells().word(wordIndex);
                            while (word != 0L) {
                                int bit = wordIndex * Long.SIZE + Long.numberOfTrailingZeros(word);
                                word &= word - 1;
                                int localA = bit & 15;
                                int localB = bit >>> 4;
                                int x;
                                int y;
                                int z;
                                switch (face) {
                                    case DOWN, UP -> {
                                        x = xBase + localA;
                                        y = yBase + localNormal;
                                        z = zBase + localB;
                                    }
                                    case NORTH, SOUTH -> {
                                        x = xBase + localA;
                                        y = yBase + localB;
                                        z = zBase + localNormal;
                                    }
                                    case WEST, EAST -> {
                                        x = xBase + localNormal;
                                        y = yBase + localA;
                                        z = zBase + localB;
                                    }
                                    default -> throw new IllegalStateException("unknown floor face");
                                }
                                if (Math.abs((double) x + 0.5 - center.x()) > halfExtent
                                        || Math.abs((double) y + 0.5 - center.y()) > halfExtent
                                        || Math.abs((double) z + 0.5 - center.z()) > halfExtent) {
                                    continue;
                                }
                                result.add(new Source(x, y, z, face, group.kind(), group.profile()));
                                if (result.size() > maximumSources) {
                                    return List.copyOf(result);
                                }
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private BlockKey sourceBlock(Face face, int sectionY, int normal, int bit) {
        int localA = bit & 15;
        int localB = bit >>> 4;
        return switch (face) {
            case DOWN, UP -> new BlockKey(Math.addExact(Math.multiplyExact(key.x(), 16), localA),
                    Math.addExact(Math.multiplyExact(sectionY, 16), normal),
                    Math.addExact(Math.multiplyExact(key.z(), 16), localB));
            case NORTH, SOUTH -> new BlockKey(Math.addExact(Math.multiplyExact(key.x(), 16), localA),
                    Math.addExact(Math.multiplyExact(sectionY, 16), localB),
                    Math.addExact(Math.multiplyExact(key.z(), 16), normal));
            case WEST, EAST -> new BlockKey(Math.addExact(Math.multiplyExact(key.x(), 16), normal),
                    Math.addExact(Math.multiplyExact(sectionY, 16), localA),
                    Math.addExact(Math.multiplyExact(key.z(), 16), localB));
        };
    }

    static final class Editor {
        private final FloorChunkCoverage base;
        private final Map<Integer, SectionBuilder> changedSections = new HashMap<>();
        private final Set<BucketKey> candidateBuckets = new HashSet<>();
        private boolean changed;

        private Editor(FloorChunkCoverage base) {
            this.base = base;
        }

        boolean replaceCell(int x, int y, int z, int descriptor, FloorFieldProfile profile) {
            if (Math.floorDiv(x, 16) != base.key.x() || Math.floorDiv(z, 16) != base.key.z()) {
                throw new IllegalArgumentException("dirty position does not belong to coverage chunk");
            }
            if (!validDescriptor(descriptor)) {
                throw new IllegalArgumentException("floor descriptor is invalid");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
            if (base.matchesCell(x, y, z, descriptor, profile)) {
                return false;
            }

            int sectionY = Math.floorDiv(y, 16);
            SectionCoverage oldSection = base.section(sectionY);
            SectionBuilder section = changedSections.computeIfAbsent(sectionY,
                    ignored -> new SectionBuilder(oldSection));
            for (Face face : Face.values()) {
                int normal = localNormal(face, x, y, z);
                candidateBuckets.add(new BucketKey(sectionY, face, normal));
                section.removeCell(face, normal, bitForCell(face, x, y, z));
            }

            int faceMask = descriptorFaceMask(descriptor);
            if (faceMask != 0) {
                Kind kind = descriptorKind(descriptor);
                for (Face face : Face.values()) {
                    if ((faceMask & (1 << face.ordinal())) != 0) {
                        section.addCell(face, localNormal(face, x, y, z), bitForCell(face, x, y, z), kind, profile);
                    }
                }
            }
            changed = true;
            return true;
        }

        boolean changed() {
            return changed;
        }

        List<BucketKey> changedBuckets(FloorChunkCoverage next) {
            if (next == null || !base.key.equals(next.key)) {
                throw new IllegalArgumentException("next coverage must belong to this editor");
            }
            List<BucketKey> result = new ArrayList<>(candidateBuckets.size());
            for (BucketKey candidate : candidateBuckets) {
                if (!sameBucket(base, next, candidate)) {
                    result.add(candidate);
                }
            }
            result.sort(Comparator.comparingInt(BucketKey::sectionY)
                    .thenComparingInt(bucket -> bucket.face().ordinal())
                    .thenComparingInt(BucketKey::localNormal));
            return List.copyOf(result);
        }

        FloorChunkCoverage finish() {
            if (!changed) {
                return base;
            }
            Map<Integer, SectionCoverage> nextSections = new HashMap<>(base.sections);
            for (Map.Entry<Integer, SectionBuilder> entry : changedSections.entrySet()) {
                SectionCoverage next = entry.getValue().finish();
                if (next.isEmpty()) {
                    nextSections.remove(entry.getKey());
                } else {
                    nextSections.put(entry.getKey(), next);
                }
            }
            return new FloorChunkCoverage(base.key, nextSections);
        }

    }

    private static boolean sameProfile(FloorFieldProfile first, FloorFieldProfile second) {
        return Double.doubleToLongBits(first.strength()) == Double.doubleToLongBits(second.strength())
                && Double.doubleToLongBits(first.plateau()) == Double.doubleToLongBits(second.plateau())
                && Double.doubleToLongBits(first.range()) == Double.doubleToLongBits(second.range());
    }

    private static boolean sameBucket(FloorChunkCoverage first, FloorChunkCoverage second, BucketKey key) {
        SectionCoverage firstSection = first.sections.get(key.sectionY());
        SectionCoverage secondSection = second.sections.get(key.sectionY());
        List<PlaneGroup> firstGroups = firstSection == null
                ? List.of() : firstSection.groups(key.face(), key.localNormal());
        List<PlaneGroup> secondGroups = secondSection == null
                ? List.of() : secondSection.groups(key.face(), key.localNormal());
        if (firstGroups.size() != secondGroups.size()) {
            return false;
        }
        for (PlaneGroup firstGroup : firstGroups) {
            boolean matched = false;
            for (PlaneGroup secondGroup : secondGroups) {
                if (firstGroup.kind() == secondGroup.kind()
                        && sameProfile(firstGroup.profile(), secondGroup.profile())) {
                    if (!firstGroup.cells().equals(secondGroup.cells())) {
                        return false;
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static int localNormal(Face face, int x, int y, int z) {
        return switch (face) {
            case DOWN, UP -> Math.floorMod(y, 16);
            case NORTH, SOUTH -> Math.floorMod(z, 16);
            case WEST, EAST -> Math.floorMod(x, 16);
        };
    }

    private static int bitForCell(Face face, int x, int y, int z) {
        int a;
        int b;
        switch (face) {
            case DOWN, UP -> {
                a = Math.floorMod(x, 16);
                b = Math.floorMod(z, 16);
            }
            case NORTH, SOUTH -> {
                a = Math.floorMod(x, 16);
                b = Math.floorMod(y, 16);
            }
            case WEST, EAST -> {
                a = Math.floorMod(y, 16);
                b = Math.floorMod(z, 16);
            }
            default -> throw new IllegalStateException("unknown floor face");
        }
        return a + 16 * b;
    }

    private static ChunkKey sourceChunk(Source source) {
        return new ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
    }

    private static final Mask256 EMPTY_MASK = new Mask256(0, 0, 0, 0);

    private static final class SectionBuilder {
        private final BucketBuilder[][] buckets = new BucketBuilder[Face.values().length][16];
        private final SectionCoverage base;
        private final boolean[] changedFaces = new boolean[Face.values().length];
        private final short[][] normalPresenceUpdates = new short[Face.values().length][];

        private SectionBuilder() {
            this.base = null;
        }

        private SectionBuilder(SectionCoverage base) {
            this.base = base;
        }

        private List<PlaneGroup> groups(Face face, int localNormal) {
            BucketBuilder bucket = buckets[face.ordinal()][localNormal];
            return bucket != null ? bucket.planes()
                    : base == null ? List.of() : base.groups(face, localNormal);
        }

        private BucketBuilder bucket(Face face, int localNormal) {
            BucketBuilder bucket = buckets[face.ordinal()][localNormal];
            if (bucket == null) {
                List<PlaneGroup> original = base == null ? List.of() : base.groups(face, localNormal);
                bucket = new BucketBuilder(original);
                if (base != null) {
                    for (PlaneGroup group : original) {
                        bucket.groups.put(GroupKey.of(group.kind(), group.profile()), group.cells());
                    }
                }
                buckets[face.ordinal()][localNormal] = bucket;
            }
            return bucket;
        }

        private void removeCell(Face face, int localNormal, int bit) {
            BucketBuilder bucket = buckets[face.ordinal()][localNormal];
            if (bucket == null && base != null) {
                boolean present = false;
                for (PlaneGroup group : base.groups(face, localNormal)) {
                    if (group.cells().contains(bit)) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    return;
                }
                bucket = bucket(face, localNormal);
            } else if (bucket == null) {
                return;
            }
            GroupKey matched = null;
            for (Map.Entry<GroupKey, Mask256> entry : bucket.groups.entrySet()) {
                if (entry.getValue().contains(bit)) {
                    if (matched != null) {
                        throw new IllegalStateException("coverage contains duplicate cell faces");
                    }
                    matched = entry.getKey();
                }
            }
            if (matched == null) {
                // A previous cell in this same batch may have created the bucket
                // for another bit. Absence of this cell/face there is normal.
                return;
            }
            Mask256 next = bucket.groups.get(matched).without(bit);
            if (next.cardinality() == 0) {
                bucket.groups.remove(matched);
            } else {
                bucket.groups.put(matched, next);
            }
            changedFaces[face.ordinal()] = true;
            updateNormalPresence(face, localNormal, bit, false);
        }

        private void addCell(Face face, int localNormal, int bit, Kind kind, FloorFieldProfile profile) {
            BucketBuilder bucket = bucket(face, localNormal);
            for (Mask256 mask : bucket.groups.values()) {
                if (mask.contains(bit)) {
                    throw new IllegalArgumentException("coverage edit duplicates a cell face");
                }
            }
            GroupKey key = GroupKey.of(kind, profile);
            Mask256 current = bucket.groups.getOrDefault(key, EMPTY_MASK);
            bucket.groups.put(key, current.with(bit));
            changedFaces[face.ordinal()] = true;
            updateNormalPresence(face, localNormal, bit, true);
        }

        private boolean isEmpty() {
            for (Face face : Face.values()) {
                for (int localNormal = 0; localNormal < 16; localNormal++) {
                    BucketBuilder bucket = buckets[face.ordinal()][localNormal];
                    if (bucket != null ? !bucket.groups.isEmpty()
                            : base != null && !base.groups(face, localNormal).isEmpty()) {
                        return false;
                    }
                }
            }
            return true;
        }

        private SectionCoverage finish() {
            @SuppressWarnings("unchecked")
            List<PlaneGroup>[] groups = new List[Face.values().length * 16];
            for (Face face : Face.values()) {
                for (int localNormal = 0; localNormal < 16; localNormal++) {
                    List<PlaneGroup> planes = groups(face, localNormal);
                    groups[SectionCoverage.index(face, localNormal)] = planes;
                }
            }
            return new SectionCoverage(groups, base, changedFaces, normalPresenceUpdates);
        }

        private void updateNormalPresence(Face face, int localNormal, int tangentBit, boolean present) {
            if (base == null) {
                return;
            }
            int faceIndex = face.ordinal();
            short[] baseLookup = base.normalPresenceByTangentBit[faceIndex];
            if (baseLookup == null) {
                return;
            }
            short[] update = normalPresenceUpdates[faceIndex];
            if (update == null) {
                update = baseLookup.clone();
                normalPresenceUpdates[faceIndex] = update;
            }
            int bit = 1 << localNormal;
            update[tangentBit] = (short) (present
                    ? (update[tangentBit] | bit)
                    : (update[tangentBit] & ~bit));
        }
    }

    private static final class BucketBuilder {
        private final Map<GroupKey, Mask256> groups = new HashMap<>();
        private final Map<GroupKey, PlaneGroup> originalGroups = new HashMap<>();

        private BucketBuilder(List<PlaneGroup> originals) {
            for (PlaneGroup group : originals) {
                originalGroups.put(GroupKey.of(group.kind(), group.profile()), group);
            }
        }

        private List<PlaneGroup> planes() {
            if (groups.isEmpty()) {
                return List.of();
            }
            List<PlaneGroup> planes = new ArrayList<>(groups.size());
            for (Map.Entry<GroupKey, Mask256> entry : groups.entrySet()) {
                PlaneGroup original = originalGroups.get(entry.getKey());
                planes.add(original != null && original.cells() == entry.getValue()
                        ? original : new PlaneGroup(entry.getKey().kind, entry.getKey().profile, entry.getValue()));
            }
            return List.copyOf(planes);
        }
    }

    private record GroupKey(Kind kind, long strengthBits, long plateauBits, long rangeBits,
                            FloorFieldProfile profile) {
        private static GroupKey of(Kind kind, FloorFieldProfile profile) {
            return new GroupKey(kind,
                    Double.doubleToLongBits(profile.strength()),
                    Double.doubleToLongBits(profile.plateau()),
                    Double.doubleToLongBits(profile.range()), profile);
        }
    }

    private record EmitterKey(int x, int y, int z, Face face) {
    }

    private record BlockKey(int x, int y, int z) {
    }
}
