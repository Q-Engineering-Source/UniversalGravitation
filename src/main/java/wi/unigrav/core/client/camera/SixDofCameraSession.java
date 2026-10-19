package wi.unigrav.core.client.camera;

/** Tracks the exact player/world identities that own one local camera session. */
final class SixDofCameraSession {

    private Object player;
    private Object world;

    boolean update(Object currentPlayer, Object currentWorld) {
        if (currentPlayer == player && currentWorld == world) {
            return false;
        }
        player = currentPlayer;
        world = currentWorld;
        return true;
    }

    boolean unloadWorld(Object unloadedWorld) {
        if (world != unloadedWorld) {
            return false;
        }
        player = null;
        world = null;
        return true;
    }

    void clear() {
        player = null;
        world = null;
    }
}
