package wi.unigrav.core.client.camera;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.ARBSamplerObjects;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLContext;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;

/**
 * One-frame, fail-closed scene-depth composite for the selected ground plane.
 *
 * <p>This class deliberately uses raw GL only. In particular it never calls GlStateManager:
 * changing that cache behind Minecraft's back is worse than declining to draw the overlay.
 */
final class SixDofSceneDepthOverlay {
    private static final ResourceLocation VERTEX_SHADER =
            new ResourceLocation("unigrav", "shaders/ground_plane.vsh");
    private static final ResourceLocation FRAGMENT_SHADER =
            new ResourceLocation("unigrav", "shaders/ground_plane.fsh");
    private static final int D24 = 0x81A6;
    private static final int D24S8 = 0x88F0;
    private static final int PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;

    private final SixDofSceneDepthResources resources = new SixDofSceneDepthResources();
    private int depthTexture;
    private int depthFramebuffer;
    private int program;
    private Uniforms uniforms;
    private Object programContext;
    private Object clipControlContext;
    private Boolean clipControlSupported;
    private String programFailure;
    private String lastLoggedFailure;
    private final SixDofSceneDepthDeletionQueue pendingDeletes = new SixDofSceneDepthDeletionQueue();
    private String status = "ground preview scene depth: waiting";

    void render(Vec3 hitWorld, Vec3 outwardNormal, Vec3 viewerFeet) {
        if (hitWorld == null || outwardNormal == null || viewerFeet == null) {
            clear();
            return;
        }
        Object context = currentContextToken();
        if (context == null) {
            status = "ground preview scene depth: no current Display render context";
            return;
        }
        ContextCapabilities capabilities = GLContext.getCapabilities();
        pumpDeletion(context);
        if (!capabilities.OpenGL30) {
            fail("OpenGL 3.0 framebuffer support unavailable");
            return;
        }
        Source source = inspectSource(capabilities, context);
        if (source == null) {
            return;
        }
        SixDofSceneDepthMath.Frame frame = SixDofSceneDepthMath.prepare(
                matrix(GL11.GL_MODELVIEW_MATRIX), matrix(GL11.GL_PROJECTION_MATRIX),
                source.width, source.height, source.depthNear, source.depthFar, source.clearDepth,
                hitWorld.sub(viewerFeet), outwardNormal);
        if (frame == null) {
            status = "ground preview scene depth: invalid camera/depth frame";
            return;
        }
        SixDofSceneDepthResources.Format format = new SixDofSceneDepthResources.Format(
                source.width, source.height, source.internalFormat, source.attachment);
        if (!resources.ownsCurrentContext(context) && resources.ownerContext() != null) {
            abandonForeignNames(context);
        }
        SixDofSceneDepthResources.Action action = resources.request(context, format);
        if (action == SixDofSceneDepthResources.Action.ABANDON) {
            // Shader names and failures are context-local. A new context is a permitted retry
            // boundary, unlike repeated frames in the same broken context.
            programFailure = null;
            lastLoggedFailure = null;
            action = SixDofSceneDepthResources.Action.CREATE;
        }
        if (action == SixDofSceneDepthResources.Action.BLOCKED) {
            status = "ground preview scene depth: " + resources.failure();
            return;
        }
        SixDofSceneDepthResources.Action finalAction = action;
        boolean samplerObjects = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects;
        SixDofSceneDepthGlTransaction.run(new RawGlStateDriver(samplerObjects, capabilities.OpenGL33), snapshot -> {
            SixDofSceneDepthGlState.Snapshot before = snapshot;
            if (!ensureProgram(context) || !ensureDepth(context, format, finalAction)) {
                return;
            }
            if (!capture(source, before)) {
                return;
            }
            composite(frame, source, before, capabilities.OpenGL33);
            status = "ground preview scene depth: active";
            lastLoggedFailure = null;
        });
    }

    /** Stops drawing and releases only the per-size depth copy; repeated selection clears are cheap. */
    void clear() {
        if (depthTexture == 0 && depthFramebuffer == 0) {
            return;
        }
        queueDepthDeletion();
        resources.clearDepth();
        status = "ground preview scene depth: cleared";
    }

    /** Invalidates shaders and copied depth after a resource reload or adapter shutdown. */
    void invalidate() {
        queueAllDeletion();
        resources.invalidate();
        programFailure = null;
        lastLoggedFailure = null;
        status = "ground preview scene depth: invalidated";
    }

    /** Must run on the render thread at tick start, including when no target is selected. */
    void lifecycle(Object world) {
        Object context = currentContextToken();
        if (context == null) {
            return;
        }
        if (resources.ownerContext() != null && !resources.ownsCurrentContext(context)) {
            // A name from a lost context must be abandoned, never deleted in this one.
            abandonForeignNames(context);
        }
        if (resources.lifecycle(world)) {
            invalidate();
        }
        pumpDeletion(context);
    }

    String status() {
        return status;
    }

    private Source inspectSource(ContextCapabilities capabilities, Object context) {
        Minecraft minecraft = Minecraft.getMinecraft();
        Framebuffer main = minecraft == null ? null : minecraft.getFramebuffer();
        if (!OpenGlHelper.isFramebufferEnabled() || main == null || !main.useDepth
                || main.framebufferObject == 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.framebufferObject) {
            fail("main depth framebuffer is not the active draw target");
            return null;
        }
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1), width = viewport.get(2), height = viewport.get(3);
        if (width <= 0 || height <= 0 || x < 0 || y < 0
                || x + width > main.framebufferWidth || y + height > main.framebufferHeight
                || GL11.glGetInteger(GL11.GL_DRAW_BUFFER) != GL30.GL_COLOR_ATTACHMENT0
                || GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            fail("main framebuffer/viewport is unsupported");
            return null;
        }
        int drawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        for (int index = 1; index < drawBuffers; index++) {
            if (GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index) != GL11.GL_NONE) {
                fail("main framebuffer uses multiple draw buffers");
                return null;
            }
        }
        if (hasClipControl(capabilities, context) && (GL11.glGetInteger(GL45.GL_CLIP_ORIGIN) != 0x8CA1
                || GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) != GL45.GL_NEGATIVE_ONE_TO_ONE)) {
            fail("non-default clip-control convention is unsupported");
            return null;
        }
        int objectType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int objectName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (objectType != GL30.GL_RENDERBUFFER || objectName <= 0 || objectName != main.depthBuffer
                || !GL30.glIsRenderbuffer(objectName)) {
            fail("main depth attachment is not its live renderbuffer");
            return null;
        }
        int priorRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int internalFormat;
        int samples;
        int depthWidth;
        int depthHeight;
        try {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, main.depthBuffer);
            internalFormat = GL30.glGetRenderbufferParameteri(
                    GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            samples = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
            depthWidth = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
            depthHeight = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, priorRenderbuffer);
        }
        if (samples != 0
                || depthWidth != main.framebufferWidth || depthHeight != main.framebufferHeight
                || (internalFormat != D24 && internalFormat != D24S8)
                || main.isStencilEnabled() != (internalFormat == D24S8)) {
            fail("main depth attachment is not single-sample D24/D24S8 renderbuffer");
            return null;
        }
        DoubleBuffer range = BufferUtils.createDoubleBuffer(16);
        GL11.glGetDouble(GL11.GL_DEPTH_RANGE, range);
        double near = range.get(0), far = range.get(1), clear = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        int function = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean supportedDirection = (function == GL11.GL_LESS || function == GL11.GL_LEQUAL) && clear == 1.0D
                || (function == GL11.GL_GREATER || function == GL11.GL_GEQUAL) && clear == 0.0D;
        if (!supportedDirection) {
            fail("depth direction/clear value is unsupported");
            return null;
        }
        return new Source(x, y, width, height, internalFormat,
                internalFormat == D24S8 ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
                near, far, clear);
    }

    private boolean ensureProgram(Object context) {
        if (program != 0 && programContext == context) return true;
        if (programFailure != null) {
            status = "ground preview scene depth: " + programFailure;
            return false;
        }
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
            fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            candidate = GL20.glCreateProgram();
            GL20.glAttachShader(candidate, vertex);
            GL20.glAttachShader(candidate, fragment);
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("shader link: " + GL20.glGetProgramInfoLog(candidate, 4096));
            }
            uniforms = requiredUniforms(candidate);
            program = candidate;
            programContext = context;
            candidate = 0;
            return true;
        } catch (IOException | RuntimeException failure) {
            programFailure = "shader unavailable (" + failure.getMessage() + ')';
            fail(programFailure, failure);
            return false;
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (candidate != 0) GL20.glDeleteProgram(candidate);
        }
    }

    private int compile(int type, ResourceLocation resource) throws IOException {
        String source;
        try (InputStream input = Minecraft.getMinecraft().getResourceManager().getResource(resource).getInputStream()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        boolean ownershipTransferred = false;
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("shader compile " + resource + ": "
                        + GL20.glGetShaderInfoLog(shader, 4096));
            }
            ownershipTransferred = true;
            return shader;
        } finally {
            if (!ownershipTransferred) {
                GL20.glDeleteShader(shader);
            }
        }
    }

    private static Uniforms requiredUniforms(int shaderProgram) {
        Uniforms result = new Uniforms(
                uniformLocation(shaderProgram, "uDepthTex"),
                uniformLocation(shaderProgram, "uInverseClip"),
                uniformLocation(shaderProgram, "uViewport"),
                uniformLocation(shaderProgram, "uViewportOrigin"),
                uniformLocation(shaderProgram, "uEyeRelative"),
                uniformLocation(shaderProgram, "uHitRelative"),
                uniformLocation(shaderProgram, "uNormal"),
                uniformLocation(shaderProgram, "uTangent0"),
                uniformLocation(shaderProgram, "uTangent1"),
                uniformLocation(shaderProgram, "uAnchorWindow"),
                uniformLocation(shaderProgram, "uDepthNear"),
                uniformLocation(shaderProgram, "uDepthFar"),
                uniformLocation(shaderProgram, "uClearDepth"));
        return result;
    }

    private static int uniformLocation(int shaderProgram, String name) {
        int location = GL20.glGetUniformLocation(shaderProgram, name);
        if (location < 0) {
            throw new IllegalStateException("required shader uniform missing: " + name);
        }
        return location;
    }

    private boolean ensureDepth(Object context, SixDofSceneDepthResources.Format format,
                                SixDofSceneDepthResources.Action action) {
        if (action == SixDofSceneDepthResources.Action.KEEP) return true;
        int texture = 0, framebuffer = 0;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL15.glBindBuffer(GL21Pixel.UNPACK, 0);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12Clamp.EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Clamp.EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12Clamp.BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12Clamp.MAX_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            if (format.internalFormat() == D24S8) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, D24S8, format.width(), format.height(), 0,
                        GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
            } else {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, D24, format.width(), format.height(), 0,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            }
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, format.attachment(), GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("private depth framebuffer incomplete");
            }
            int oldTexture = depthTexture, oldFramebuffer = depthFramebuffer;
            depthTexture = texture;
            depthFramebuffer = framebuffer;
            resources.committed(format);
            if (oldTexture != 0) GL11.glDeleteTextures(oldTexture);
            if (oldFramebuffer != 0) GL30.glDeleteFramebuffers(oldFramebuffer);
            return true;
        } catch (RuntimeException failure) {
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (texture != 0) GL11.glDeleteTextures(texture);
            resources.failed(format, "private depth allocation failed: " + failure.getMessage());
            fail(resources.failure(), failure);
            return false;
        }
    }

    private boolean capture(Source source, SixDofSceneDepthGlState.Snapshot before) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFramebuffer);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(true);
        GL11.glClearDepth(source.clearDepth);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, before.drawFramebuffer());
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFramebuffer);
        GL30.glBlitFramebuffer(source.x, source.y, source.x + source.width, source.y + source.height,
                0, 0, source.width, source.height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, before.drawFramebuffer());
        return true;
    }

    private void composite(SixDofSceneDepthMath.Frame frame, Source source,
                           SixDofSceneDepthGlState.Snapshot before, boolean coreSampler) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, before.drawFramebuffer());
        GL11.glViewport(source.x, source.y, source.width, source.height);
        SixDofSceneDepthGlState.IntRect savedScissor = before.scissorBox();
        int[] intersection = intersect(savedScissor.x(), savedScissor.y(),
                savedScissor.width(), savedScissor.height(),
                source.x, source.y, source.width, source.height);
        if (before.scissorTest() && intersection == null) return;
        if (before.scissorTest()) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(intersection[0], intersection[1], intersection[2], intersection[3]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        if (before.hasSamplerObjects()) bindSampler(coreSampler, 0, 0);
        GL20.glUseProgram(program);
        uniform(uniforms.depthTexture(), 0);
        uniform(uniforms.inverseClip(), frame.inverseClip());
        uniform(uniforms.viewport(), source.width, source.height);
        uniform(uniforms.viewportOrigin(), source.x, source.y);
        uniform(uniforms.eyeRelative(), frame.eyeRelative());
        uniform(uniforms.hitRelative(), frame.hitRelative());
        uniform(uniforms.normal(), frame.normal());
        uniform(uniforms.tangent0(), frame.tangent0());
        uniform(uniforms.tangent1(), frame.tangent1());
        uniform(uniforms.anchorWindow(), frame.anchorU(), frame.anchorV());
        uniform(uniforms.depthNear(), frame.depthNear());
        uniform(uniforms.depthFar(), frame.depthFar());
        uniform(uniforms.clearDepth(), frame.clearDepth());
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glPolygonMode(GL11.GL_FRONT, GL11.GL_FILL);
        GL11.glPolygonMode(GL11.GL_BACK, GL11.GL_FILL);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        GL11.glColorMask(true, true, true, false);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(-1.0F, -1.0F); GL11.glVertex2f(3.0F, -1.0F); GL11.glVertex2f(-1.0F, 3.0F);
        GL11.glEnd();
    }

    private static void uniform(int location, int value) {
        GL20.glUniform1i(location, value);
    }
    private static void bindSampler(boolean core, int unit, int sampler) {
        if (core) GL33.glBindSampler(unit, sampler); else ARBSamplerObjects.glBindSampler(unit, sampler);
    }
    private static void uniform(int location, double x, double y) {
        GL20.glUniform2f(location, (float) x, (float) y);
    }

    private static void uniform(int location, double value) {
        GL20.glUniform1f(location, (float) value);
    }

    private static void uniform(int location, Vec3 value) {
        GL20.glUniform3f(location, (float) value.x(), (float) value.y(), (float) value.z());
    }

    private static void uniform(int location, float[] values) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(values).flip();
        GL20.glUniformMatrix4(location, false, buffer);
    }

    private static double[] matrix(int name) { DoubleBuffer b = BufferUtils.createDoubleBuffer(16); GL11.glGetDouble(name, b); double[] value = new double[16]; b.get(value); return value; }
    private static int[] intersect(int x0, int y0, int w0, int h0, int x1, int y1, int w1, int h1) { int x = Math.max(x0,x1), y = Math.max(y0,y1), r = Math.min(x0+w0,x1+w1), t = Math.min(y0+h0,y1+h1); return r > x && t > y ? new int[] {x,y,r-x,t-y} : null; }

    private void queueDepthDeletion() {
        queue(depthTexture, depthFramebuffer, 0);
        depthTexture = 0;
        depthFramebuffer = 0;
    }

    private void queueAllDeletion() {
        queue(depthTexture, depthFramebuffer, program);
        depthTexture = 0;
        depthFramebuffer = 0;
        program = 0;
        programContext = null;
        uniforms = null;
    }

    private void queue(int texture, int framebuffer, int shaderProgram) {
        if (texture == 0 && framebuffer == 0 && shaderProgram == 0) {
            return;
        }
        Object owner = texture != 0 || framebuffer != 0 ? resources.ownerContext() : programContext;
        if (owner != null) {
            pendingDeletes.enqueue(owner, texture, framebuffer, shaderProgram);
        }
    }

    private void pumpDeletion(Object context) {
        pendingDeletes.pump(context, new SixDofSceneDepthDeletionQueue.Backend() {
            @Override
            public void deleteTexture(int texture) {
                GL11.glDeleteTextures(texture);
            }

            @Override
            public void deleteFramebuffer(int framebuffer) {
                GL30.glDeleteFramebuffers(framebuffer);
            }

            @Override
            public void deleteProgram(int shaderProgram) {
                GL20.glDeleteProgram(shaderProgram);
            }
        });
    }

    private void abandonForeignNames(Object currentContext) {
        depthTexture = 0;
        depthFramebuffer = 0;
        program = 0;
        programContext = null;
        uniforms = null;
        pendingDeletes.abandonForeign(currentContext);
    }

    /**
     * {@link GLContext#getCapabilities()} is a singleton in the supported Cleanroom shim and is
     * therefore capability data only, never a context identity. Display creates a new DrawableGL
     * on each public Display lifecycle creation, so that drawable is the supported owner token.
     */
    private static Object currentContextToken() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || !minecraft.isCallingFromMinecraftThread() || !Display.isCreated()) {
            return null;
        }
        return Display.getDrawable();
    }

    private boolean hasClipControl(ContextCapabilities capabilities, Object context) {
        if (clipControlContext != context) {
            clipControlContext = context;
            boolean supported = capabilities.OpenGL45;
            if (!supported) {
                int extensionCount = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
                for (int index = 0; index < extensionCount; index++) {
                    if ("GL_ARB_clip_control".equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, index))) {
                        supported = true;
                        break;
                    }
                }
            }
            clipControlSupported = supported;
        }
        return Boolean.TRUE.equals(clipControlSupported);
    }

    private void fail(String reason) {
        fail(reason, null);
    }

    private void fail(String reason, Throwable cause) {
        if (!reason.equals(lastLoggedFailure)) {
            if (cause == null) {
                UnigravMod.LOGGER.warn("Scene-depth ground preview disabled: {}", reason);
            } else {
                UnigravMod.LOGGER.warn("Scene-depth ground preview disabled: " + reason, cause);
            }
            lastLoggedFailure = reason;
        }
        status = "ground preview scene depth: " + reason;
    }

    private record Source(int x, int y, int width, int height, int internalFormat, int attachment,
                          double depthNear, double depthFar, double clearDepth) {
    }

    private record Uniforms(int depthTexture, int inverseClip, int viewport, int viewportOrigin,
                            int eyeRelative, int hitRelative, int normal, int tangent0, int tangent1,
                            int anchorWindow, int depthNear, int depthFar, int clearDepth) {
    }

    private static final class GL12Clamp {
        private static final int EDGE = 0x812F;
        private static final int BASE_LEVEL = 0x813C;
        private static final int MAX_LEVEL = 0x813D;
    }

    private static final class GL21Pixel {
        private static final int UNPACK = 0x88EC;
    }

    /** Production implementation of the complete pure state driver. */
    private static final class RawGlStateDriver implements SixDofSceneDepthGlState.Driver {
        private final boolean samplerObjects;
        private final boolean coreSampler;

        private RawGlStateDriver(boolean samplerObjects, boolean coreSampler) {
            this.samplerObjects = samplerObjects;
            this.coreSampler = coreSampler;
        }

        @Override public int program() { return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM); }
        @Override public void program(int value) { GL20.glUseProgram(value); }
        @Override public int readFramebuffer() { return GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING); }
        @Override public void readFramebuffer(int value) { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, value); }
        @Override public int drawFramebuffer() { return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING); }
        @Override public void drawFramebuffer(int value) { GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, value); }
        @Override public int renderbuffer() { return GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING); }
        @Override public void renderbuffer(int value) { GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, value); }
        @Override public int activeTexture() { return GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0; }
        @Override public void activeTexture(int value) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + value); }
        @Override public int texture0() { return withUnit0(() -> GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)); }
        @Override public void texture0(int value) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, value); }
        @Override public boolean hasSamplerObjects() { return samplerObjects; }
        @Override public int sampler0() { return withUnit0(() -> GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)); }
        @Override public void sampler0(int value) { bindSampler(coreSampler, 0, value); }
        @Override public int unpackPbo() { return GL11.glGetInteger(PIXEL_UNPACK_BUFFER_BINDING); }
        @Override public void unpackPbo(int value) { GL15.glBindBuffer(GL21Pixel.UNPACK, value); }
        @Override public int blendSourceRgb() { return GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB); }
        @Override public int blendDestinationRgb() { return GL11.glGetInteger(GL14.GL_BLEND_DST_RGB); }
        @Override public int blendSourceAlpha() { return GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA); }
        @Override public int blendDestinationAlpha() { return GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA); }
        @Override public void blendFactors(int sr, int dr, int sa, int da) { GL14.glBlendFuncSeparate(sr, dr, sa, da); }
        @Override public int blendEquationRgb() { return GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB); }
        @Override public int blendEquationAlpha() { return GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA); }
        @Override public void blendEquations(int rgb, int alpha) { GL20.glBlendEquationSeparate(rgb, alpha); }
        @Override public boolean blend() { return GL11.glIsEnabled(GL11.GL_BLEND); }
        @Override public void blend(boolean value) { enable(GL11.GL_BLEND, value); }
        @Override public boolean depthTest() { return GL11.glIsEnabled(GL11.GL_DEPTH_TEST); }
        @Override public void depthTest(boolean value) { enable(GL11.GL_DEPTH_TEST, value); }
        @Override public boolean depthMask() { return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK); }
        @Override public void depthMask(boolean value) { GL11.glDepthMask(value); }
        @Override public int depthFunction() { return GL11.glGetInteger(GL11.GL_DEPTH_FUNC); }
        @Override public void depthFunction(int value) { GL11.glDepthFunc(value); }
        @Override public boolean stencilTest() { return GL11.glIsEnabled(GL11.GL_STENCIL_TEST); }
        @Override public void stencilTest(boolean value) { enable(GL11.GL_STENCIL_TEST, value); }
        @Override public boolean alphaTest() { return GL11.glIsEnabled(GL11.GL_ALPHA_TEST); }
        @Override public void alphaTest(boolean value) { enable(GL11.GL_ALPHA_TEST, value); }
        @Override public int alphaFunction() { return GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC); }
        @Override public float alphaReference() { return GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF); }
        @Override public void alphaFunction(int function, float reference) { GL11.glAlphaFunc(function, reference); }
        @Override public boolean cullFace() { return GL11.glIsEnabled(GL11.GL_CULL_FACE); }
        @Override public void cullFace(boolean value) { enable(GL11.GL_CULL_FACE, value); }
        @Override public int cullFaceMode() { return GL11.glGetInteger(GL11.GL_CULL_FACE_MODE); }
        @Override public void cullFaceMode(int value) { GL11.glCullFace(value); }
        @Override public boolean colorLogic() { return GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP); }
        @Override public void colorLogic(boolean value) { enable(GL11.GL_COLOR_LOGIC_OP, value); }
        @Override public int logicOperation() { return GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE); }
        @Override public void logicOperation(int value) { GL11.glLogicOp(value); }
        @Override public int frontPolygonMode() { return polygonModes().get(0); }
        @Override public int backPolygonMode() { return polygonModes().get(1); }
        @Override public void polygonModes(int front, int back) { GL11.glPolygonMode(GL11.GL_FRONT, front); GL11.glPolygonMode(GL11.GL_BACK, back); }
        @Override public boolean scissorTest() { return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); }
        @Override public void scissorTest(boolean value) { enable(GL11.GL_SCISSOR_TEST, value); }
        @Override public SixDofSceneDepthGlState.IntRect scissorBox() { return rectangle(GL11.GL_SCISSOR_BOX); }
        @Override public void scissorBox(SixDofSceneDepthGlState.IntRect value) { GL11.glScissor(value.x(), value.y(), value.width(), value.height()); }
        @Override public SixDofSceneDepthGlState.IntRect viewport() { return rectangle(GL11.GL_VIEWPORT); }
        @Override public void viewport(SixDofSceneDepthGlState.IntRect value) { GL11.glViewport(value.x(), value.y(), value.width(), value.height()); }
        @Override public SixDofSceneDepthGlState.ColorMask colorMask() { ByteBuffer mask = BufferUtils.createByteBuffer(4); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask); return new SixDofSceneDepthGlState.ColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0); }
        @Override public void colorMask(SixDofSceneDepthGlState.ColorMask value) { GL11.glColorMask(value.red(), value.green(), value.blue(), value.alpha()); }
        @Override public SixDofSceneDepthGlState.ClearColor clearColor() { FloatBuffer color = BufferUtils.createFloatBuffer(4); GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, color); return new SixDofSceneDepthGlState.ClearColor(color.get(0), color.get(1), color.get(2), color.get(3)); }
        @Override public void clearColor(SixDofSceneDepthGlState.ClearColor value) { GL11.glClearColor(value.red(), value.green(), value.blue(), value.alpha()); }
        @Override public double clearDepth() { return GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE); }
        @Override public void clearDepth(double value) { GL11.glClearDepth(value); }
        @Override public int clearStencil() { return GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE); }
        @Override public void clearStencil(int value) { GL11.glClearStencil(value); }
        @Override public boolean rasterizerDiscard() { return GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD); }
        @Override public void rasterizerDiscard(boolean value) { enable(GL30.GL_RASTERIZER_DISCARD, value); }
        @Override public boolean framebufferSrgb() { return GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB); }
        @Override public void framebufferSrgb(boolean value) { enable(GL30.GL_FRAMEBUFFER_SRGB, value); }

        private static void enable(int capability, boolean value) { if (value) GL11.glEnable(capability); else GL11.glDisable(capability); }
        private static IntBuffer polygonModes() { IntBuffer values = BufferUtils.createIntBuffer(2); GL11.glGetInteger(GL11.GL_POLYGON_MODE, values); return values; }
        private static SixDofSceneDepthGlState.IntRect rectangle(int name) { IntBuffer values = BufferUtils.createIntBuffer(4); GL11.glGetInteger(name, values); return new SixDofSceneDepthGlState.IntRect(values.get(0), values.get(1), values.get(2), values.get(3)); }
        private static int withUnit0(IntQuery query) { int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0); try { return query.get(); } finally { GL13.glActiveTexture(active); } }
        @FunctionalInterface private interface IntQuery { int get(); }
    }
}
