package wi.unigrav.probe.agent;

/** Loads only the exact transformed classes with full JVM verification; it never initializes game code. */
public final class ProbeBytecodePreflightMain {
    private static final String[] TARGET_CLASSES = {
            "wi.unigrav.core.client.PlayerPhysicsClient",
            "wi.unigrav.core.client.camera.RevoUiSixDofCamera$1",
            "neofontrender.addons.camera.CameraEyeAnchorSampler",
            "neofontrender.addons.camera.CameraRuntime",
            "neofontrender.addons.api.camera.CameraApi",
            "wi.unigrav.probe.ProbeHooks",
            "wi.unigrav.probe.AcceptedConsumerProbeMod"
    };

    private ProbeBytecodePreflightMain() {}

    public static void main(String[] arguments) throws ClassNotFoundException {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        for (String name : TARGET_CLASSES) {
            Class<?> loaded = Class.forName(name, false, loader);
            System.out.println("PREFLIGHT_LOADED " + name + " loader=" + loaded.getClassLoader());
        }
    }
}
