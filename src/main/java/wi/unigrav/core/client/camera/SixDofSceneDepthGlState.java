package wi.unigrav.core.client.camera;

import java.util.Objects;

/**
 * The raw OpenGL state touched by the scene-depth capture/composite transaction.
 *
 * <p>The driver is deliberately a small typed boundary: production reads and writes raw GL
 * through it, while unit tests exercise this exact snapshot/restore contract without a GL
 * context. Do not replace it with {@code GlStateManager}; that would desynchronize Minecraft's
 * cache from the raw operations in the transaction.
 */
final class SixDofSceneDepthGlState {
    interface Driver {
        int program();
        void program(int value);
        int readFramebuffer();
        void readFramebuffer(int value);
        int drawFramebuffer();
        void drawFramebuffer(int value);
        int renderbuffer();
        void renderbuffer(int value);
        int activeTexture();
        void activeTexture(int value);
        int texture0();
        void texture0(int value);
        boolean hasSamplerObjects();
        int sampler0();
        void sampler0(int value);
        int unpackPbo();
        void unpackPbo(int value);
        int blendSourceRgb();
        int blendDestinationRgb();
        int blendSourceAlpha();
        int blendDestinationAlpha();
        void blendFactors(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);
        int blendEquationRgb();
        int blendEquationAlpha();
        void blendEquations(int rgb, int alpha);
        boolean blend();
        void blend(boolean value);
        boolean depthTest();
        void depthTest(boolean value);
        boolean depthMask();
        void depthMask(boolean value);
        int depthFunction();
        void depthFunction(int value);
        boolean stencilTest();
        void stencilTest(boolean value);
        boolean alphaTest();
        void alphaTest(boolean value);
        int alphaFunction();
        float alphaReference();
        void alphaFunction(int function, float reference);
        boolean cullFace();
        void cullFace(boolean value);
        int cullFaceMode();
        void cullFaceMode(int value);
        boolean colorLogic();
        void colorLogic(boolean value);
        int logicOperation();
        void logicOperation(int value);
        int frontPolygonMode();
        int backPolygonMode();
        void polygonModes(int front, int back);
        boolean scissorTest();
        void scissorTest(boolean value);
        IntRect scissorBox();
        void scissorBox(IntRect value);
        IntRect viewport();
        void viewport(IntRect value);
        ColorMask colorMask();
        void colorMask(ColorMask value);
        ClearColor clearColor();
        void clearColor(ClearColor value);
        double clearDepth();
        void clearDepth(double value);
        int clearStencil();
        void clearStencil(int value);
        boolean rasterizerDiscard();
        void rasterizerDiscard(boolean value);
        boolean framebufferSrgb();
        void framebufferSrgb(boolean value);
    }

    record IntRect(int x, int y, int width, int height) {
    }

    record ColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
    }

    record ClearColor(float red, float green, float blue, float alpha) {
    }

    private SixDofSceneDepthGlState() {
    }

    static Snapshot capture(Driver driver) {
        Objects.requireNonNull(driver, "driver");
        return new Snapshot(driver);
    }

    static final class Snapshot {
        private final int program;
        private final int readFramebuffer;
        private final int drawFramebuffer;
        private final int renderbuffer;
        private final int activeTexture;
        private final int texture0;
        private final boolean hasSamplerObjects;
        private final int sampler0;
        private final int unpackPbo;
        private final int blendSourceRgb;
        private final int blendDestinationRgb;
        private final int blendSourceAlpha;
        private final int blendDestinationAlpha;
        private final int blendEquationRgb;
        private final int blendEquationAlpha;
        private final boolean blend;
        private final boolean depthTest;
        private final boolean depthMask;
        private final int depthFunction;
        private final boolean stencilTest;
        private final boolean alphaTest;
        private final int alphaFunction;
        private final float alphaReference;
        private final boolean cullFace;
        private final int cullFaceMode;
        private final boolean colorLogic;
        private final int logicOperation;
        private final int frontPolygonMode;
        private final int backPolygonMode;
        private final boolean scissorTest;
        private final IntRect scissorBox;
        private final IntRect viewport;
        private final ColorMask colorMask;
        private final ClearColor clearColor;
        private final double clearDepth;
        private final int clearStencil;
        private final boolean rasterizerDiscard;
        private final boolean framebufferSrgb;

        private Snapshot(Driver driver) {
            program = driver.program();
            readFramebuffer = driver.readFramebuffer();
            drawFramebuffer = driver.drawFramebuffer();
            renderbuffer = driver.renderbuffer();
            activeTexture = driver.activeTexture();
            texture0 = driver.texture0();
            hasSamplerObjects = driver.hasSamplerObjects();
            sampler0 = hasSamplerObjects ? driver.sampler0() : 0;
            unpackPbo = driver.unpackPbo();
            blendSourceRgb = driver.blendSourceRgb();
            blendDestinationRgb = driver.blendDestinationRgb();
            blendSourceAlpha = driver.blendSourceAlpha();
            blendDestinationAlpha = driver.blendDestinationAlpha();
            blendEquationRgb = driver.blendEquationRgb();
            blendEquationAlpha = driver.blendEquationAlpha();
            blend = driver.blend();
            depthTest = driver.depthTest();
            depthMask = driver.depthMask();
            depthFunction = driver.depthFunction();
            stencilTest = driver.stencilTest();
            alphaTest = driver.alphaTest();
            alphaFunction = driver.alphaFunction();
            alphaReference = driver.alphaReference();
            cullFace = driver.cullFace();
            cullFaceMode = driver.cullFaceMode();
            colorLogic = driver.colorLogic();
            logicOperation = driver.logicOperation();
            frontPolygonMode = driver.frontPolygonMode();
            backPolygonMode = driver.backPolygonMode();
            scissorTest = driver.scissorTest();
            scissorBox = driver.scissorBox();
            viewport = driver.viewport();
            colorMask = driver.colorMask();
            clearColor = driver.clearColor();
            clearDepth = driver.clearDepth();
            clearStencil = driver.clearStencil();
            rasterizerDiscard = driver.rasterizerDiscard();
            framebufferSrgb = driver.framebufferSrgb();
        }

        int drawFramebuffer() {
            return drawFramebuffer;
        }

        boolean hasSamplerObjects() {
            return hasSamplerObjects;
        }

        boolean scissorTest() {
            return scissorTest;
        }

        IntRect scissorBox() {
            return scissorBox;
        }

        void restore(Driver driver) {
            Objects.requireNonNull(driver, "driver");
            driver.program(program);
            driver.readFramebuffer(readFramebuffer);
            driver.drawFramebuffer(drawFramebuffer);
            driver.renderbuffer(renderbuffer);
            driver.unpackPbo(unpackPbo);
            driver.activeTexture(0);
            driver.texture0(texture0);
            if (hasSamplerObjects) {
                driver.sampler0(sampler0);
            }
            driver.activeTexture(activeTexture);
            driver.blendEquations(blendEquationRgb, blendEquationAlpha);
            driver.blendFactors(blendSourceRgb, blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha);
            driver.blend(blend);
            driver.depthTest(depthTest);
            driver.depthMask(depthMask);
            driver.depthFunction(depthFunction);
            driver.stencilTest(stencilTest);
            driver.alphaTest(alphaTest);
            driver.alphaFunction(alphaFunction, alphaReference);
            driver.cullFace(cullFace);
            driver.cullFaceMode(cullFaceMode);
            driver.colorLogic(colorLogic);
            driver.logicOperation(logicOperation);
            driver.rasterizerDiscard(rasterizerDiscard);
            driver.framebufferSrgb(framebufferSrgb);
            driver.polygonModes(frontPolygonMode, backPolygonMode);
            driver.scissorTest(scissorTest);
            driver.scissorBox(scissorBox);
            driver.viewport(viewport);
            driver.colorMask(colorMask);
            driver.clearColor(clearColor);
            driver.clearDepth(clearDepth);
            driver.clearStencil(clearStencil);
        }
    }
}
