package wi.unigrav.core.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SixDofSceneDepthDeletionQueueTest {
    @Test
    void clearInvalidateAndRepeatKeepEachOwnedNameUntilItsOwnerPumps() {
        Object context = new Object();
        SixDofSceneDepthDeletionQueue queue = new SixDofSceneDepthDeletionQueue();
        RecordingBackend backend = new RecordingBackend();

        queue.enqueue(context, 11, 12, 0); // clear
        queue.enqueue(context, 0, 0, 13);  // invalidate
        queue.enqueue(context, 14, 15, 16); // repeated lifecycle work
        queue.pump(new Object(), backend);
        assertEquals(List.of(), backend.deleted);
        assertEquals(6, queue.size());

        queue.pump(context, backend);
        assertEquals(List.of("texture:11", "framebuffer:12", "program:13", "texture:14", "framebuffer:15", "program:16"),
                backend.deleted);
        assertEquals(0, queue.size());
    }

    @Test
    void backendFailureConsumesOnlyTheAttemptedNameSoResumedPumpCannotDeleteItTwice() {
        Object context = new Object();
        SixDofSceneDepthDeletionQueue queue = new SixDofSceneDepthDeletionQueue();
        RecordingBackend backend = new RecordingBackend();
        backend.failTexture = true;
        queue.enqueue(context, 21, 22, 23);

        assertThrows(IllegalStateException.class, () -> queue.pump(context, backend));
        assertEquals(List.of("texture:21"), backend.deleted);
        assertEquals(2, queue.size());

        backend.failTexture = false;
        queue.pump(context, backend);
        assertEquals(List.of("texture:21", "framebuffer:22", "program:23"), backend.deleted);
        assertEquals(0, queue.size());
    }

    @Test
    void abandoningOldContextNeverCallsItsDeleteBackendInNewContext() {
        Object oldContext = new Object();
        Object newContext = new Object();
        SixDofSceneDepthDeletionQueue queue = new SixDofSceneDepthDeletionQueue();
        RecordingBackend backend = new RecordingBackend();
        queue.enqueue(oldContext, 31, 32, 33);

        queue.abandonForeign(newContext);
        queue.pump(newContext, backend);

        assertEquals(List.of(), backend.deleted);
        assertEquals(0, queue.size());
    }

    private static final class RecordingBackend implements SixDofSceneDepthDeletionQueue.Backend {
        private final List<String> deleted = new ArrayList<>();
        private boolean failTexture;

        @Override
        public void deleteTexture(int texture) {
            deleted.add("texture:" + texture);
            if (failTexture) {
                throw new IllegalStateException("intentional texture delete failure");
            }
        }

        @Override
        public void deleteFramebuffer(int framebuffer) {
            deleted.add("framebuffer:" + framebuffer);
        }

        @Override
        public void deleteProgram(int program) {
            deleted.add("program:" + program);
        }
    }
}
