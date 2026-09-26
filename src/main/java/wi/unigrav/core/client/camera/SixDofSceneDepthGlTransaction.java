package wi.unigrav.core.client.camera;

/** Small production transaction seam: every raw-GL mutation must restore its captured state. */
final class SixDofSceneDepthGlTransaction {
    @FunctionalInterface
    interface Work {
        void run(SixDofSceneDepthGlState.Snapshot snapshot);
    }

    private SixDofSceneDepthGlTransaction() {
    }

    static void run(SixDofSceneDepthGlState.Driver driver, Work work) {
        SixDofSceneDepthGlState.Snapshot snapshot = SixDofSceneDepthGlState.capture(driver);
        try {
            work.run(snapshot);
        } finally {
            snapshot.restore(driver);
        }
    }
}
