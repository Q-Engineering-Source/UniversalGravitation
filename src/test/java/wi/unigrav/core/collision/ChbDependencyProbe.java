package wi.unigrav.core.collision;

/** Isolated JVM startup probe: deliberately does not load FML or a Minecraft instance. */
public final class ChbDependencyProbe {
    public static void main(String[] args) {
        ChbDependency.requireSupportedContract();
    }
}
