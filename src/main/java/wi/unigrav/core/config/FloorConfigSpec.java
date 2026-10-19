package wi.unigrav.core.config;

import java.nio.file.Path;
import java.util.Objects;

/** Parsed, immutable source text for one complete floor configuration transaction. */
public record FloorConfigSpec(Path path, String json) {
    public FloorConfigSpec {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(json, "json");
    }
}
