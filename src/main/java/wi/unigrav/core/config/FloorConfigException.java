package wi.unigrav.core.config;

import java.nio.file.Path;
import java.util.Objects;

/** A floor configuration failure retains source, field, stage, and the original cause. */
public final class FloorConfigException extends RuntimeException {
    private final Path file;
    private final String field;
    private final String stage;

    public FloorConfigException(Path file, String field, String stage, String message, Throwable cause) {
        super(message, cause);
        this.file = Objects.requireNonNull(file, "file");
        this.field = Objects.requireNonNull(field, "field");
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    public Path file() {
        return file;
    }

    public String field() {
        return field;
    }

    public String stage() {
        return stage;
    }
}
