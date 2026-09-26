package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RemoteBoostLoadedColumnsTest {
    @Test
    void coverageIsXZOnlyAcrossNativeHeightBoundaries() {
        Set<Long> loaded = Set.of(RemoteBoostLoadedColumns.key(0, 0));
        assertTrue(RemoteBoostLoadedColumns.covers(volume(0, 255, 0, 15, 256, 15), loaded::contains));
        assertTrue(RemoteBoostLoadedColumns.covers(volume(0, 256, 0, 15, 320, 15), loaded::contains));
        assertTrue(RemoteBoostLoadedColumns.covers(volume(0, 300, 0, 15, 400, 15), loaded::contains));
    }

    @Test
    void coversNegativeAndBoundaryColumnsButRejectsAnActualMissingColumn() {
        Set<Long> loaded = new HashSet<>();
        loaded.add(RemoteBoostLoadedColumns.key(-1, -1));
        loaded.add(RemoteBoostLoadedColumns.key(0, -1));
        loaded.add(RemoteBoostLoadedColumns.key(-1, 0));
        loaded.add(RemoteBoostLoadedColumns.key(0, 0));
        assertTrue(RemoteBoostLoadedColumns.covers(volume(-1, -20, -1, 0, 300, 0), loaded::contains));
        loaded.remove(RemoteBoostLoadedColumns.key(0, 0));
        assertFalse(RemoteBoostLoadedColumns.covers(volume(-1, -20, -1, 0, 300, 0), loaded::contains));
    }

    private static RemoteBoostLoadedColumns.BlockVolume volume(int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        return new RemoteBoostLoadedColumns.BlockVolume(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
