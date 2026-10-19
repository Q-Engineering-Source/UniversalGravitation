package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorSampleFaceSetsTest {

    private static final Vec3 SAMPLE_POINT = new Vec3(0.5, 0.5, 0.5);
    private static final int ALL_FACE_BITS = (1 << Face.values().length) - 1;

    @Test
    void allSixtyFourFaceMasksPublishCanonicalIndependentGravityAndMagneticSets() {
        FloorFieldProfile profile = new FloorFieldProfile(12.0, 0.5, 4.0);
        for (int gravityMask = 0; gravityMask <= ALL_FACE_BITS; gravityMask++) {
            for (boolean complementMagneticMask : new boolean[] {false, true}) {
                int magneticMask = complementMagneticMask
                        ? ALL_FACE_BITS ^ gravityMask : gravityMask;
                List<Source> sources = sourcesForMasks(gravityMask, magneticMask, profile);
                FloorFieldIndex index = indexOf(sources);
                EnumSet<Face> expectedGravityFaces = facesForMask(gravityMask);
                EnumSet<Face> expectedMagneticFaces = facesForMask(magneticMask);

                Sample expected = scalarOracle(sources, SAMPLE_POINT, 1.0);
                Sample actual = index.sample(SAMPLE_POINT, 1.0);
                assertSampleBits(expected, actual, "gravityMask=" + gravityMask
                        + " magneticMask=" + magneticMask);
                assertCanonicalSet(expectedGravityFaces, actual.effectiveGravityFaces(), "sample gravity faces");
                assertCanonicalSet(expectedMagneticFaces, actual.effectiveMagneticFaces(), "sample magnetic faces");

                var standing = index.sampleForStanding(SAMPLE_POINT, 1.0);
                assertSampleBits(expected, standing.forces(), "standing forces");
                assertCanonicalSet(expectedGravityFaces, standing.forces().effectiveGravityFaces(),
                        "standing gravity faces");
                assertCanonicalSet(expectedMagneticFaces, standing.forces().effectiveMagneticFaces(),
                        "standing magnetic faces");

                Sample expectedWithoutMagnetism = scalarOracle(sources, SAMPLE_POINT, 0.0);
                Sample withoutMagnetism = index.sample(SAMPLE_POINT, 0.0);
                assertSampleBits(expectedWithoutMagnetism, withoutMagnetism, "zero magnetic response");
                assertCanonicalSet(expectedGravityFaces, withoutMagnetism.effectiveGravityFaces(),
                        "zero response gravity faces");
                assertCanonicalSet(EnumSet.noneOf(Face.class), withoutMagnetism.effectiveMagneticFaces(),
                        "zero response magnetic faces");
                var standingWithoutMagnetism = index.sampleForStanding(SAMPLE_POINT, 0.0);
                assertSampleBits(expectedWithoutMagnetism, standingWithoutMagnetism.forces(),
                        "zero response standing forces");
                assertTrue(standingWithoutMagnetism.magneticStrengths().isEmpty(),
                        "zero response standing magnetic strengths");
            }
        }
    }

    @Test
    void publicSampleCopiesMutableFaceSetsRejectsNullsAndKeepsEveryReturnedSetImmutable() {
        EnumSet<Face> mutableEnumSet = EnumSet.of(Face.UP, Face.EAST);
        Set<Face> mutableHashSet = new HashSet<>(Set.of(Face.NORTH));
        Sample copied = new Sample(Vec3.ZERO, Vec3.ZERO, mutableEnumSet, mutableHashSet);

        mutableEnumSet.add(Face.DOWN);
        mutableEnumSet.remove(Face.UP);
        mutableEnumSet.clear();
        mutableHashSet.add(Face.SOUTH);
        mutableHashSet.remove(Face.NORTH);
        mutableHashSet.clear();

        EnumSet<Face> expectedGravity = EnumSet.of(Face.UP, Face.EAST);
        EnumSet<Face> expectedMagnetic = EnumSet.of(Face.NORTH);
        assertCanonicalSet(expectedGravity, copied.effectiveGravityFaces(), "copied EnumSet input");
        assertCanonicalSet(expectedMagnetic, copied.effectiveMagneticFaces(), "copied HashSet input");

        EnumSet<Face> emptyEnumSet = EnumSet.noneOf(Face.class);
        Set<Face> emptyHashSet = new HashSet<>();
        Sample copiedEmpty = new Sample(Vec3.ZERO, Vec3.ZERO, emptyEnumSet, emptyHashSet);
        emptyEnumSet.add(Face.WEST);
        emptyEnumSet.remove(Face.WEST);
        emptyEnumSet.clear();
        emptyHashSet.add(Face.DOWN);
        emptyHashSet.remove(Face.DOWN);
        emptyHashSet.clear();
        assertCanonicalSet(EnumSet.noneOf(Face.class), copiedEmpty.effectiveGravityFaces(), "empty EnumSet input");
        assertCanonicalSet(EnumSet.noneOf(Face.class), copiedEmpty.effectiveMagneticFaces(), "empty HashSet input");

        Sample reconstructed = new Sample(copied.gravityAccel(), copied.magneticAccel(),
                copied.effectiveGravityFaces(), copied.effectiveMagneticFaces());
        Set<Face> bridgeGravity = new HashSet<>(reconstructed.effectiveGravityFaces());
        Set<Face> bridgeMagnetic = new HashSet<>(reconstructed.effectiveMagneticFaces());
        Sample forwarded = new Sample(reconstructed.gravityAccel(), reconstructed.magneticAccel(),
                bridgeGravity, bridgeMagnetic);
        bridgeGravity.clear();
        bridgeMagnetic.clear();
        assertCanonicalSet(expectedGravity, copied.effectiveGravityFaces(), "original sample after forwarding");
        assertCanonicalSet(expectedMagnetic, copied.effectiveMagneticFaces(), "original magnetic after forwarding");
        assertCanonicalSet(expectedGravity, forwarded.effectiveGravityFaces(), "forwarded sample");
        assertCanonicalSet(expectedMagnetic, forwarded.effectiveMagneticFaces(), "forwarded magnetic sample");

        FloorFieldProfile profile = new FloorFieldProfile(8.0, 0.5, 4.0);
        FloorFieldIndex index = indexOf(List.of(source(Face.UP, Kind.GRAVITY, false, profile)));
        Sample indexed = index.sample(SAMPLE_POINT, 0.0);
        var standing = index.sampleForStanding(SAMPLE_POINT, 0.0);
        assertCanonicalSet(EnumSet.of(Face.UP), indexed.effectiveGravityFaces(), "real index sample");
        assertCanonicalSet(EnumSet.of(Face.UP), standing.forces().effectiveGravityFaces(),
                "real standing sample");

        for (Sample sample : List.of(copied, copiedEmpty, reconstructed, forwarded, indexed, standing.forces())) {
            assertAllMutationMethodsThrow(sample.effectiveGravityFaces(), sample.effectiveMagneticFaces());
        }

        assertThrows(IllegalArgumentException.class,
                () -> new Sample(Vec3.ZERO, Vec3.ZERO, null, EnumSet.noneOf(Face.class)));
        assertThrows(IllegalArgumentException.class,
                () -> new Sample(Vec3.ZERO, Vec3.ZERO, EnumSet.noneOf(Face.class), null));
        Set<Face> gravityWithNull = new HashSet<>();
        gravityWithNull.add(null);
        Set<Face> magneticWithNull = new HashSet<>();
        magneticWithNull.add(null);
        assertThrows(NullPointerException.class,
                () -> new Sample(Vec3.ZERO, Vec3.ZERO, gravityWithNull, EnumSet.noneOf(Face.class)));
        assertThrows(NullPointerException.class,
                () -> new Sample(Vec3.ZERO, Vec3.ZERO, EnumSet.noneOf(Face.class), magneticWithNull));
    }

    @Test
    void opposingSourcesKeepEffectiveFacesWhenGravityAndMagneticForcesCancel() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 0.5, 4.0);
        List<Source> sources = new ArrayList<>();
        for (Face face : Face.values()) {
            sources.add(source(face, Kind.GRAVITY, false, profile));
            sources.add(source(face, Kind.MAGNETIC, true, profile));
        }
        FloorFieldIndex index = indexOf(sources);
        EnumSet<Face> everyFace = EnumSet.allOf(Face.class);

        Sample expected = scalarOracle(sources, SAMPLE_POINT, 1.0);
        Sample sample = index.sample(SAMPLE_POINT, 1.0);
        assertSampleBits(expected, sample, "opposing sample");
        assertVectorBits(Vec3.ZERO, sample.gravityAccel(), "cancelled gravity");
        assertVectorBits(Vec3.ZERO, sample.magneticAccel(), "cancelled magnetism");
        assertCanonicalSet(everyFace, sample.effectiveGravityFaces(), "cancelled gravity faces");
        assertCanonicalSet(everyFace, sample.effectiveMagneticFaces(), "cancelled magnetic faces");

        var standing = index.sampleForStanding(SAMPLE_POINT, 1.0);
        assertSampleBits(expected, standing.forces(), "opposing standing sample");
        assertCanonicalSet(everyFace, standing.forces().effectiveGravityFaces(), "standing gravity faces");
        assertCanonicalSet(everyFace, standing.forces().effectiveMagneticFaces(), "standing magnetic faces");
        for (Face face : Face.values()) {
            assertEquals(Double.doubleToRawLongBits(profile.strengthAt(0.5)),
                    Double.doubleToRawLongBits(standing.gravityStrengths().get(face)),
                    "gravity standing strength " + face);
            assertEquals(Double.doubleToRawLongBits(profile.strengthAt(1.5)),
                    Double.doubleToRawLongBits(standing.magneticStrengths().get(face)),
                    "magnetic standing strength " + face);
        }
    }

    private static List<Source> sourcesForMasks(int gravityMask, int magneticMask,
                                                FloorFieldProfile profile) {
        List<Source> sources = new ArrayList<>();
        for (Face face : Face.values()) {
            int bit = 1 << face.ordinal();
            if ((gravityMask & bit) != 0) {
                sources.add(source(face, Kind.GRAVITY, false, profile));
            }
            if ((magneticMask & bit) != 0) {
                // A second normal cell allows both kinds to own the same face without duplicate identity.
                sources.add(source(face, Kind.MAGNETIC, true, profile));
            }
        }
        return List.copyOf(sources);
    }

    private static Source source(Face face, Kind kind, boolean farNormal, FloorFieldProfile profile) {
        int normal = farNormal ? 2 : 1;
        return switch (face) {
            case DOWN -> new Source(0, normal, 0, face, kind, profile);
            case UP -> new Source(0, -normal, 0, face, kind, profile);
            case NORTH -> new Source(0, 0, normal, face, kind, profile);
            case SOUTH -> new Source(0, 0, -normal, face, kind, profile);
            case WEST -> new Source(normal, 0, 0, face, kind, profile);
            case EAST -> new Source(-normal, 0, 0, face, kind, profile);
        };
    }

    private static FloorFieldIndex indexOf(List<Source> sources) {
        FloorFieldIndex index = new FloorFieldIndex();
        Map<ChunkKey, List<Source>> byChunk = new LinkedHashMap<>();
        for (Source source : sources) {
            ChunkKey key = new ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
        }
        for (Map.Entry<ChunkKey, List<Source>> chunk : byChunk.entrySet()) {
            index.replaceChunk(chunk.getKey(), new Object(), List.copyOf(chunk.getValue()));
        }
        return index;
    }

    private static EnumSet<Face> facesForMask(int mask) {
        EnumSet<Face> faces = EnumSet.noneOf(Face.class);
        for (Face face : Face.values()) {
            if ((mask & (1 << face.ordinal())) != 0) {
                faces.add(face);
            }
        }
        return faces;
    }

    private static Sample scalarOracle(List<Source> sources, Vec3 point, double magneticResponse) {
        double gravityX = 0.0;
        double gravityY = 0.0;
        double gravityZ = 0.0;
        double magneticX = 0.0;
        double magneticY = 0.0;
        double magneticZ = 0.0;
        EnumSet<Face> gravityFaces = EnumSet.noneOf(Face.class);
        EnumSet<Face> magneticFaces = EnumSet.noneOf(Face.class);

        for (Face face : Face.values()) {
            List<Source> ordered = new ArrayList<>();
            for (Source source : sources) {
                if (source.face() == face) {
                    ordered.add(source);
                }
            }
            ordered.sort(Comparator.comparingInt(FloorSampleFaceSetsTest::normalCoordinate));
            for (Source source : ordered) {
                Vec3 contribution = new FloorFieldEmitter(source.x(), source.y(), source.z(),
                        face, source.profile()).accelerationAt(point);
                if (isZero(contribution)) {
                    continue;
                }
                if (source.kind() == Kind.GRAVITY) {
                    gravityX += contribution.x();
                    gravityY += contribution.y();
                    gravityZ += contribution.z();
                    gravityFaces.add(face);
                } else if (magneticResponse > 0.0) {
                    magneticX += contribution.x() * magneticResponse;
                    magneticY += contribution.y() * magneticResponse;
                    magneticZ += contribution.z() * magneticResponse;
                    magneticFaces.add(face);
                }
            }
        }

        return new Sample(
                gravityFaces.isEmpty() ? Vec3.ZERO : new Vec3(gravityX, gravityY, gravityZ),
                magneticFaces.isEmpty() ? Vec3.ZERO : new Vec3(magneticX, magneticY, magneticZ),
                gravityFaces, magneticFaces);
    }

    private static int normalCoordinate(Source source) {
        return switch (source.face()) {
            case DOWN, UP -> source.y();
            case NORTH, SOUTH -> source.z();
            case WEST, EAST -> source.x();
        };
    }

    private static boolean isZero(Vec3 vector) {
        return vector.x() == 0.0 && vector.y() == 0.0 && vector.z() == 0.0;
    }

    private static void assertSampleBits(Sample expected, Sample actual, String label) {
        assertVectorBits(expected.gravityAccel(), actual.gravityAccel(), label + " gravity");
        assertVectorBits(expected.magneticAccel(), actual.magneticAccel(), label + " magnetic");
        assertCanonicalSet(expected.effectiveGravityFaces(), actual.effectiveGravityFaces(), label + " gravity set");
        assertCanonicalSet(expected.effectiveMagneticFaces(), actual.effectiveMagneticFaces(), label + " magnetic set");
    }

    private static void assertVectorBits(Vec3 expected, Vec3 actual, String label) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()), label + " x");
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()), label + " y");
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()), label + " z");
    }

    private static void assertCanonicalSet(Set<Face> expected, Set<Face> actual, String label) {
        assertEquals(expected.size(), actual.size(), label + " size");
        assertEquals(expected, actual, label + " equals expected");
        assertEquals(actual, expected, label + " equals in reverse");
        assertEquals(actual, new HashSet<>(expected), label + " equals independent HashSet");
        assertEquals(expected.hashCode(), actual.hashCode(), label + " hashCode");

        List<Face> enumOrder = new ArrayList<>();
        for (Face face : Face.values()) {
            if (expected.contains(face)) {
                enumOrder.add(face);
            }
        }
        assertEquals(enumOrder, new ArrayList<>(actual), label + " enum iteration order");
        Face[] expectedArray = enumOrder.toArray(new Face[0]);
        Face[] actualArray = actual.toArray(new Face[0]);
        assertArrayEquals(expectedArray, actualArray, label + " typed toArray");
        Object[] objectArray = actual.toArray();
        assertArrayEquals(enumOrder.toArray(), objectArray, label + " object toArray");

        if (actualArray.length > 0) {
            actualArray[0] = actualArray[0] == Face.DOWN ? Face.UP : Face.DOWN;
            objectArray[0] = null;
        }
        assertEquals(expected, actual, label + " arrays are detached");
    }

    private static void assertAllMutationMethodsThrow(Set<Face> nonEmpty, Set<Face> empty) {
        assertMutationMethodsThrow(nonEmpty);
        assertMutationMethodsThrow(empty);
    }

    private static void assertMutationMethodsThrow(Set<Face> faces) {
        if (faces.isEmpty()) {
            assertThrows(UnsupportedOperationException.class, () -> faces.add(Face.DOWN));
            assertThrows(UnsupportedOperationException.class, () -> faces.remove(Face.DOWN));
            assertThrows(UnsupportedOperationException.class, () -> faces.addAll(Set.of(Face.DOWN)));
            assertThrows(UnsupportedOperationException.class, () -> faces.removeAll(Set.of(Face.DOWN)));
            assertThrows(UnsupportedOperationException.class, () -> faces.retainAll(Set.of(Face.DOWN)));
            assertThrows(UnsupportedOperationException.class, faces::clear);
            assertThrows(UnsupportedOperationException.class, () -> faces.removeIf(face -> false));
            return;
        }

        Face present = faces.iterator().next();
        Face absentCandidate = null;
        for (Face face : Face.values()) {
            if (!faces.contains(face)) {
                absentCandidate = face;
                break;
            }
        }
        assertTrue(absentCandidate != null, "fixture must leave an absent face");
        Face absent = absentCandidate;

        assertThrows(UnsupportedOperationException.class, () -> faces.add(absent));
        assertThrows(UnsupportedOperationException.class, () -> faces.remove(absent));
        assertThrows(UnsupportedOperationException.class, () -> faces.remove(present));
        assertThrows(UnsupportedOperationException.class, () -> faces.addAll(Set.of(absent)));
        assertThrows(UnsupportedOperationException.class, () -> faces.removeAll(Set.of(present)));
        assertThrows(UnsupportedOperationException.class, () -> faces.retainAll(Set.of(present)));
        assertThrows(UnsupportedOperationException.class, faces::clear);
        assertThrows(UnsupportedOperationException.class, () -> faces.removeIf(face -> false));
        assertThrows(UnsupportedOperationException.class, () -> {
            Iterator<Face> iterator = faces.iterator();
            iterator.next();
            iterator.remove();
        });
    }
}
