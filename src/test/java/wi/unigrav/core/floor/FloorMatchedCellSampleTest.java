package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
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

class FloorMatchedCellSampleTest {

    @Test
    void matchedCellSamplingPreservesHalfOpenTangentBitsAtNegativeAndIntegerExtremes() {
        FloorFieldProfile profile = new FloorFieldProfile(7.25, 0.25, 2.0);
        int[][] positions = {
                {-1, -2, -3},
                {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}
        };

        for (int[] position : positions) {
            for (Face face : Face.values()) {
                Source source = new Source(position[0], position[1], position[2],
                        face, Kind.GRAVITY, profile);
                FloorFieldIndex index = indexOf(List.of(source));
                int[] tangentAxes = tangentAxes(face);
                for (int axis : tangentAxes) {
                    double minimum = (double) coordinate(source, axis);
                    // Convert before adding one so Integer.MAX_VALUE never wraps.
                    double maximum = minimum + 1.0;
                    double[] edgeSamples = {
                            Math.nextDown(minimum), minimum, Math.nextUp(minimum),
                            Math.nextDown(maximum), maximum, Math.nextUp(maximum)
                    };
                    for (double tangent : edgeSamples) {
                        double first = (double) source.x() + 0.5;
                        double second = (double) source.y() + 0.5;
                        double third = (double) source.z() + 0.5;
                        double[] coordinates = {first, second, third};
                        coordinates[axis] = tangent;
                        Vec3 point = pointAt(source, face, 0.0,
                                coordinates[0], coordinates[1], coordinates[2]);
                        boolean inside = tangent >= minimum && tangent < maximum;
                        String label = face + " source=" + source + " tangentAxis=" + axis
                                + " tangent=" + tangent;
                        assertMatchesOracle(index, List.of(source), point, 0.0, label);
                        assertEquals(inside ? Set.of(face) : Set.of(),
                                index.sample(point, 0.0).effectiveGravityFaces(), label);
                    }
                }
            }
        }
    }

    @Test
    void matchedCellSamplingPreservesDistanceZeroPlateauAndExclusiveRangeForEveryFace() {
        FloorFieldProfile profile = new FloorFieldProfile(13.75, 0.75, 3.5);
        List<Source> sources = new ArrayList<>();
        for (Face face : Face.values()) {
            sources.add(new Source(-8, -8, -8, face, Kind.GRAVITY, profile));
        }
        FloorFieldIndex index = indexOf(sources);
        for (Source source : sources) {
            Face face = source.face();
            double surface = surfaceCoordinate(source, face);
            boolean outwardDecreases = outwardDecreasesCoordinate(face);
            double plateauEdge = surface + (outwardDecreases ? -profile.plateau() : profile.plateau());
            double rangeEdge = surface + (outwardDecreases ? -profile.range() : profile.range());
            double justBeyondPlateau = Math.nextAfter(plateauEdge,
                    outwardDecreases ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
            double justInsideRange = Math.nextAfter(rangeEdge, surface);
            double[] normalCoordinates = {
                    surface, plateauEdge, justBeyondPlateau, justInsideRange, rangeEdge
            };

            for (int i = 0; i < normalCoordinates.length; i++) {
                Vec3 point = pointAtNormal(source, face, normalCoordinates[i],
                        (double) source.x() + 0.5,
                        (double) source.y() + 0.5,
                        (double) source.z() + 0.5);
                double actualDistance = outwardDistance(source, face, point);
                String label = face + " actualDistance=" + actualDistance;
                if (i == 0) {
                    assertEquals(0.0, actualDistance, label + " surface");
                } else if (i == 1) {
                    assertEquals(profile.plateau(), actualDistance, label + " plateau endpoint");
                } else if (i == 2) {
                    assertTrue(actualDistance > profile.plateau(), label + " just beyond plateau");
                } else if (i == 3) {
                    assertTrue(actualDistance < profile.range(), label + " just inside range");
                } else {
                    assertEquals(profile.range(), actualDistance, label + " exclusive range endpoint");
                }
                assertMatchesOracle(index, sources, point, 0.0, label);
                assertEquals(actualDistance >= 0.0 && actualDistance < profile.range()
                                && profile.strengthAt(actualDistance) != 0.0 ? Set.of(face) : Set.of(),
                        index.sample(point, 0.0).effectiveGravityFaces(), label);
            }
        }
    }

    @Test
    void matchedCellSamplingKeepsProfileOrderingMagneticResponseAndUnderflowPresence() {
        FloorFieldProfile first = new FloorFieldProfile(8.25, 0.25, 4.0);
        FloorFieldProfile second = new FloorFieldProfile(5.5, 1.0, 3.0);
        FloorFieldProfile third = new FloorFieldProfile(2.75, 0.5, 2.5);
        FloorFieldProfile fourth = new FloorFieldProfile(13.0, 0.0, 6.0);
        List<Source> sources = List.of(
                new Source(0, -1, 0, Face.UP, Kind.GRAVITY, first),
                new Source(0, -2, 0, Face.UP, Kind.MAGNETIC, second),
                new Source(0, 1, 0, Face.DOWN, Kind.GRAVITY, third),
                new Source(0, 2, 0, Face.DOWN, Kind.MAGNETIC, fourth),
                new Source(0, 0, 1, Face.NORTH, Kind.MAGNETIC, first),
                new Source(0, 0, -2, Face.SOUTH, Kind.GRAVITY, third),
                new Source(1, 0, 0, Face.WEST, Kind.GRAVITY, second),
                new Source(-2, 0, 0, Face.EAST, Kind.MAGNETIC, fourth),
                new Source(10, 10, 10, Face.UP, Kind.MAGNETIC,
                        new FloorFieldProfile(Double.MIN_VALUE, 0.0, 2.0)));
        FloorFieldIndex index = indexOf(sources);
        Vec3 sharedPoint = new Vec3(0.5, 0.5, 0.5);

        for (double response : new double[] {0.0, 0.5, 2.0}) {
            assertMatchesOracle(index, sources, sharedPoint, response, "response=" + response);
        }
        var zeroResponse = index.sampleForStanding(sharedPoint, 0.0);
        assertTrue(zeroResponse.forces().effectiveMagneticFaces().isEmpty());
        assertTrue(zeroResponse.magneticStrengths().isEmpty());

        Source underflowSource = sources.get(sources.size() - 1);
        Vec3 underflowPoint = new Vec3(10.5, 11.0, 10.5);
        assertMatchesOracle(index, sources, underflowPoint, 0.5, "magnetic underflow response=0.5");
        var underflow = index.sampleForStanding(underflowPoint, 0.5);
        assertEquals(Set.of(Face.UP), underflow.forces().effectiveMagneticFaces());
        assertEquals(Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(underflow.forces().magneticAccel().y()));
        assertTrue(underflow.magneticStrengths().isEmpty());

        assertMatchesOracle(index, sources, underflowPoint, 1.0, "magnetic subnormal response=1");
        var retained = index.sampleForStanding(underflowPoint, 1.0);
        assertEquals(Set.of(underflowSource.face()), retained.forces().effectiveMagneticFaces());
        assertEquals(Double.doubleToRawLongBits(-Double.MIN_VALUE),
                Double.doubleToRawLongBits(retained.forces().magneticAccel().y()));
        assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE),
                Double.doubleToRawLongBits(retained.magneticStrengths().get(Face.UP)));
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

    private static void assertMatchesOracle(FloorFieldIndex index, List<Source> sources,
                                            Vec3 point, double response, String label) {
        OracleResult expected = scalarOracle(sources, point, response);
        Sample actual = index.sample(point, response);
        assertSampleBits(expected.forces(), actual, label + " sample");

        var standing = index.sampleForStanding(point, response);
        assertSampleBits(expected.forces(), standing.forces(), label + " standing forces");
        assertMapBits(expected.gravityStrengths(), standing.gravityStrengths(), label + " gravity strengths");
        assertMapBits(expected.magneticStrengths(), standing.magneticStrengths(), label + " magnetic strengths");
    }

    private static OracleResult scalarOracle(List<Source> sources, Vec3 point, double response) {
        double gravityX = 0.0;
        double gravityY = 0.0;
        double gravityZ = 0.0;
        double magneticX = 0.0;
        double magneticY = 0.0;
        double magneticZ = 0.0;
        EnumSet<Face> gravityFaces = EnumSet.noneOf(Face.class);
        EnumSet<Face> magneticFaces = EnumSet.noneOf(Face.class);
        EnumMap<Face, Double> gravityStrengths = new EnumMap<>(Face.class);
        EnumMap<Face, Double> magneticStrengths = new EnumMap<>(Face.class);

        for (Face face : Face.values()) {
            List<Source> ordered = new ArrayList<>();
            for (Source source : sources) {
                if (source.face() == face) {
                    ordered.add(source);
                }
            }
            ordered.sort(Comparator.comparingInt(source -> normalCoordinate(source, face)));
            for (Source source : ordered) {
                Vec3 contribution = new FloorFieldEmitter(source.x(), source.y(), source.z(),
                        source.face(), source.profile()).accelerationAt(point);
                if (isZero(contribution)) {
                    continue;
                }
                if (source.kind() == Kind.GRAVITY) {
                    gravityX += contribution.x();
                    gravityY += contribution.y();
                    gravityZ += contribution.z();
                    gravityFaces.add(face);
                    addStrength(gravityStrengths, face, contribution);
                } else if (response > 0.0) {
                    double scaledX = contribution.x() * response;
                    double scaledY = contribution.y() * response;
                    double scaledZ = contribution.z() * response;
                    magneticX += scaledX;
                    magneticY += scaledY;
                    magneticZ += scaledZ;
                    magneticFaces.add(face);
                    addStrength(magneticStrengths, face, new Vec3(scaledX, scaledY, scaledZ));
                }
            }
        }

        Sample forces = new Sample(
                gravityFaces.isEmpty() ? Vec3.ZERO : new Vec3(gravityX, gravityY, gravityZ),
                magneticFaces.isEmpty() ? Vec3.ZERO : new Vec3(magneticX, magneticY, magneticZ),
                gravityFaces, magneticFaces);
        return new OracleResult(forces, gravityStrengths, magneticStrengths);
    }

    private static void addStrength(EnumMap<Face, Double> strengths, Face face, Vec3 contribution) {
        double strength = Math.max(Math.abs(contribution.x()),
                Math.max(Math.abs(contribution.y()), Math.abs(contribution.z())));
        if (strength > 0.0) {
            strengths.put(face, strengths.getOrDefault(face, 0.0) + strength);
        }
    }

    private static boolean isZero(Vec3 vector) {
        return vector.x() == 0.0 && vector.y() == 0.0 && vector.z() == 0.0;
    }

    private static int normalCoordinate(Source source, Face face) {
        return switch (face) {
            case DOWN, UP -> source.y();
            case NORTH, SOUTH -> source.z();
            case WEST, EAST -> source.x();
        };
    }

    private static int[] tangentAxes(Face face) {
        return switch (face) {
            case DOWN, UP -> new int[] {0, 2};
            case NORTH, SOUTH -> new int[] {0, 1};
            case WEST, EAST -> new int[] {1, 2};
        };
    }

    private static int coordinate(Source source, int axis) {
        return switch (axis) {
            case 0 -> source.x();
            case 1 -> source.y();
            case 2 -> source.z();
            default -> throw new IllegalArgumentException("unknown coordinate axis: " + axis);
        };
    }

    private static Vec3 pointAt(Source source, Face face, double distance,
                                double x, double y, double z) {
        return switch (face) {
            case DOWN -> new Vec3(x, (double) source.y() - distance, z);
            case UP -> new Vec3(x, (double) source.y() + 1.0 + distance, z);
            case NORTH -> new Vec3(x, y, (double) source.z() - distance);
            case SOUTH -> new Vec3(x, y, (double) source.z() + 1.0 + distance);
            case WEST -> new Vec3((double) source.x() - distance, y, z);
            case EAST -> new Vec3((double) source.x() + 1.0 + distance, y, z);
        };
    }

    private static Vec3 pointAtNormal(Source source, Face face, double normal,
                                      double x, double y, double z) {
        return switch (face) {
            case DOWN, UP -> new Vec3(x, normal, z);
            case NORTH, SOUTH -> new Vec3(x, y, normal);
            case WEST, EAST -> new Vec3(normal, y, z);
        };
    }

    private static double surfaceCoordinate(Source source, Face face) {
        return switch (face) {
            case DOWN, NORTH, WEST -> (double) normalCoordinate(source, face);
            case UP, SOUTH, EAST -> (double) normalCoordinate(source, face) + 1.0;
        };
    }

    private static boolean outwardDecreasesCoordinate(Face face) {
        return face == Face.DOWN || face == Face.NORTH || face == Face.WEST;
    }

    private static double outwardDistance(Source source, Face face, Vec3 point) {
        double normal = switch (face) {
            case DOWN, UP -> point.y();
            case NORTH, SOUTH -> point.z();
            case WEST, EAST -> point.x();
        };
        double surface = surfaceCoordinate(source, face);
        return outwardDecreasesCoordinate(face) ? surface - normal : normal - surface;
    }

    private static void assertSampleBits(Sample expected, Sample actual, String label) {
        assertVectorBits(expected.gravityAccel(), actual.gravityAccel(), label + " gravity");
        assertVectorBits(expected.magneticAccel(), actual.magneticAccel(), label + " magnetic");
        assertEquals(expected.effectiveGravityFaces(), actual.effectiveGravityFaces(), label + " gravity faces");
        assertEquals(expected.effectiveMagneticFaces(), actual.effectiveMagneticFaces(), label + " magnetic faces");
    }

    private static void assertVectorBits(Vec3 expected, Vec3 actual, String label) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()),
                label + " x");
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()),
                label + " y");
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()),
                label + " z");
    }

    private static void assertMapBits(Map<Face, Double> expected, Map<Face, Double> actual, String label) {
        assertEquals(expected.keySet(), actual.keySet(), label + " keys");
        for (Face face : Face.values()) {
            if (expected.containsKey(face)) {
                assertEquals(Double.doubleToRawLongBits(expected.get(face)),
                        Double.doubleToRawLongBits(actual.get(face)), label + " " + face);
            }
        }
    }

    private record OracleResult(Sample forces, Map<Face, Double> gravityStrengths,
                                Map<Face, Double> magneticStrengths) {
    }
}
