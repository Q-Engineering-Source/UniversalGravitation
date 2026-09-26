package wi.unigrav.core.boost;

import java.util.function.LongPredicate;

/** Pure XZ-only loaded-column coverage used by the live World adapter. */
final class RemoteBoostLoadedColumns {
    private RemoteBoostLoadedColumns() {
    }

    static boolean covers(BlockVolume volume, LongPredicate loadedChunk) {
        return firstMissing(volume, loadedChunk) == Long.MIN_VALUE;
    }

    static long firstMissing(BlockVolume volume, LongPredicate loadedChunk) {
        int minChunkX = Math.floorDiv(volume.minX(), 16);
        int maxChunkX = Math.floorDiv(volume.maxX(), 16);
        int minChunkZ = Math.floorDiv(volume.minZ(), 16);
        int maxChunkZ = Math.floorDiv(volume.maxZ(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = key(chunkX, chunkZ);
                if (!loadedChunk.test(key)) return key;
            }
        }
        return Long.MIN_VALUE;
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
    }

    record BlockVolume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BlockVolume {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Inverted block volume");
            }
        }
    }
}
