package wi.unigrav.core.client.camera;

import java.util.Objects;

/**
 * Pure resource-generation bookkeeping for {@link SixDofSceneDepthOverlay}.
 *
 * <p>The GL owner deliberately keeps this policy separate from name allocation so tests can
 * exercise resize, context replacement and failure latching without creating an MC or GL class.
 */
final class SixDofSceneDepthResources {
    enum Action {
        KEEP, CREATE, REPLACE, ABANDON, BLOCKED
    }

    record Format(int width, int height, int internalFormat, int attachment) {
        Format {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("non-positive depth texture size");
            }
        }
    }

    private Object context;
    private Object world;
    private Format format;
    private boolean depthAllocated;
    private boolean invalidated;
    private String failure;

    Action request(Object currentContext, Format requested) {
        Objects.requireNonNull(currentContext, "currentContext");
        Objects.requireNonNull(requested, "requested");
        if (context != null && context != currentContext) {
            context = currentContext;
            format = null;
            depthAllocated = false; // names belong to the old context: never delete them here.
            invalidated = false;
            failure = null;
            return Action.ABANDON;
        }
        if (context == null) {
            context = currentContext;
        }
        if (failure != null && !invalidated && requested.equals(format)) {
            return Action.BLOCKED;
        }
        if (invalidated || !depthAllocated) {
            return Action.CREATE;
        }
        return requested.equals(format) ? Action.KEEP : Action.REPLACE;
    }

    void committed(Format committed) {
        format = Objects.requireNonNull(committed, "committed");
        depthAllocated = true;
        invalidated = false;
        failure = null;
    }

    void failed(Format attempted, String reason) {
        format = Objects.requireNonNull(attempted, "attempted");
        depthAllocated = false;
        invalidated = false;
        failure = Objects.requireNonNull(reason, "reason");
    }

    void clearDepth() {
        depthAllocated = false;
    }

    void invalidate() {
        invalidated = true;
        failure = null;
    }

    boolean lifecycle(Object currentWorld) {
        if (world == currentWorld) {
            return false;
        }
        world = currentWorld;
        invalidate();
        return true;
    }

    boolean ownsCurrentContext(Object currentContext) {
        return context != null && context == currentContext;
    }

    Object ownerContext() {
        return context;
    }

    boolean depthAllocated() {
        return depthAllocated;
    }

    String failure() {
        return failure;
    }
}
