package wi.unigrav.core.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SixDofSceneDepthResourcesTest {
    private static final SixDofSceneDepthResources.Format D24 =
            new SixDofSceneDepthResources.Format(320, 180, 0x81A6, 0x8D00);

    @Test
    void recordsCreationResizeAndIdempotentClearWithoutLoadingClientClasses() {
        SixDofSceneDepthResources resources = new SixDofSceneDepthResources();
        Object context = new Object();
        assertEquals(SixDofSceneDepthResources.Action.CREATE, resources.request(context, D24));
        resources.committed(D24);
        assertEquals(SixDofSceneDepthResources.Action.KEEP, resources.request(context, D24));
        resources.clearDepth();
        resources.clearDepth();
        assertFalse(resources.depthAllocated());
        assertEquals(SixDofSceneDepthResources.Action.CREATE, resources.request(context, D24));

        resources.committed(D24);
        SixDofSceneDepthResources.Format resized =
                new SixDofSceneDepthResources.Format(640, 360, 0x81A6, 0x8D00);
        assertEquals(SixDofSceneDepthResources.Action.REPLACE, resources.request(context, resized));
    }

    @Test
    void latchesFailuresUntilExplicitInvalidationAndNeverDeletesForeignContextNames() {
        SixDofSceneDepthResources resources = new SixDofSceneDepthResources();
        Object firstContext = new Object();
        assertEquals(SixDofSceneDepthResources.Action.CREATE, resources.request(firstContext, D24));
        resources.failed(D24, "incomplete framebuffer");
        assertEquals(SixDofSceneDepthResources.Action.BLOCKED, resources.request(firstContext, D24));
        resources.invalidate();
        assertEquals(SixDofSceneDepthResources.Action.CREATE, resources.request(firstContext, D24));
        resources.committed(D24);

        Object secondContext = new Object();
        assertEquals(SixDofSceneDepthResources.Action.ABANDON, resources.request(secondContext, D24));
        assertFalse(resources.depthAllocated());
        assertTrue(resources.ownsCurrentContext(secondContext));
        assertNull(resources.failure());
    }

    @Test
    void worldChangeInvalidatesOnceAndLetsDeferredRenderThreadCleanupRunWithoutTarget() {
        SixDofSceneDepthResources resources = new SixDofSceneDepthResources();
        Object world = new Object();
        assertTrue(resources.lifecycle(world));
        assertFalse(resources.lifecycle(world));
        assertTrue(resources.lifecycle(new Object()));
    }
}
