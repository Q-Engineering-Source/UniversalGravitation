package wi.unigrav.core.client.camera;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Context-owned deferred GL-name deletion with a pure, testable ownership boundary. */
final class SixDofSceneDepthDeletionQueue {
    interface Backend {
        void deleteTexture(int texture);
        void deleteFramebuffer(int framebuffer);
        void deleteProgram(int program);
    }

    private final List<Entry> pending = new ArrayList<>();

    void enqueue(Object ownerContext, int texture, int framebuffer, int program) {
        Objects.requireNonNull(ownerContext, "ownerContext");
        if (texture != 0) {
            pending.add(new Entry(ownerContext, Kind.TEXTURE, texture));
        }
        if (framebuffer != 0) {
            pending.add(new Entry(ownerContext, Kind.FRAMEBUFFER, framebuffer));
        }
        if (program != 0) {
            pending.add(new Entry(ownerContext, Kind.PROGRAM, program));
        }
    }

    void pump(Object currentContext, Backend backend) {
        Objects.requireNonNull(currentContext, "currentContext");
        Objects.requireNonNull(backend, "backend");
        for (Iterator<Entry> iterator = pending.iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            if (entry.ownerContext != currentContext) {
                continue;
            }
            // Consume before calling GL. If the backend throws after accepting a delete, a
            // resumed pump must not delete a reused name a second time.
            iterator.remove();
            switch (entry.kind) {
                case TEXTURE -> backend.deleteTexture(entry.name);
                case FRAMEBUFFER -> backend.deleteFramebuffer(entry.name);
                case PROGRAM -> backend.deleteProgram(entry.name);
            }
        }
    }

    /** Drops names whose owner context can no longer be current; it never calls a foreign GL context. */
    void abandonForeign(Object currentContext) {
        Objects.requireNonNull(currentContext, "currentContext");
        pending.removeIf(entry -> entry.ownerContext != currentContext);
    }

    int size() {
        return pending.size();
    }

    private enum Kind {
        TEXTURE,
        FRAMEBUFFER,
        PROGRAM
    }

    private record Entry(Object ownerContext, Kind kind, int name) {
    }
}
