package wi.unigrav.core.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Exercises the production state snapshot contract, not a synthetic finally-only state. */
class SixDofSceneDepthGlTransactionTest {
    @Test
    void restoresEveryCapturedFieldAfterNormalWork() {
        FakeDriver driver = new FakeDriver();
        Map<String, Object> expected = driver.copy();
        SixDofSceneDepthGlTransaction.run(driver, ignored -> mutate(driver));
        assertEquals(expected, driver.copy());
    }

    @Test
    void restoresEveryCapturedFieldAfterThrowingWork() {
        FakeDriver driver = new FakeDriver();
        Map<String, Object> expected = driver.copy();
        assertThrows(IllegalStateException.class, () -> SixDofSceneDepthGlTransaction.run(driver, ignored -> {
            mutate(driver);
            throw new IllegalStateException("intentional composite failure");
        }));
        assertEquals(expected, driver.copy());
    }

    private static void mutate(FakeDriver driver) {
        driver.program(101); driver.readFramebuffer(102); driver.drawFramebuffer(103); driver.renderbuffer(104);
        driver.activeTexture(7); driver.activeTexture(0); driver.texture0(105); driver.sampler0(106); driver.activeTexture(7);
        driver.unpackPbo(107); driver.blendFactors(108, 109, 110, 111); driver.blendEquations(112, 113);
        driver.blend(false); driver.depthTest(false); driver.depthMask(true); driver.depthFunction(114);
        driver.stencilTest(true); driver.alphaTest(false); driver.alphaFunction(115, 0.75F); driver.cullFace(false);
        driver.cullFaceMode(116); driver.colorLogic(true); driver.logicOperation(117); driver.polygonModes(118, 119);
        driver.scissorTest(false); driver.scissorBox(new SixDofSceneDepthGlState.IntRect(12, 13, 14, 15));
        driver.viewport(new SixDofSceneDepthGlState.IntRect(16, 17, 18, 19));
        driver.colorMask(new SixDofSceneDepthGlState.ColorMask(false, true, false, true));
        driver.clearColor(new SixDofSceneDepthGlState.ClearColor(0.2F, 0.3F, 0.4F, 0.5F));
        driver.clearDepth(0.25D); driver.clearStencil(120); driver.rasterizerDiscard(true); driver.framebufferSrgb(true);
    }

    private static final class FakeDriver implements SixDofSceneDepthGlState.Driver {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private FakeDriver() {
            for (int index = 0; index < 40; index++) values.put("i" + index, index + 1);
            values.put("blend", true); values.put("depth", true); values.put("depthMask", false); values.put("stencil", false);
            values.put("alpha", true); values.put("cull", true); values.put("logic", false); values.put("scissor", true);
            values.put("discard", false); values.put("srgb", false); values.put("alphaRef", 0.125F); values.put("clearDepth", 0.875D);
            values.put("scissorBox", new SixDofSceneDepthGlState.IntRect(1, 2, 3, 4));
            values.put("viewport", new SixDofSceneDepthGlState.IntRect(5, 6, 7, 8));
            values.put("colorMask", new SixDofSceneDepthGlState.ColorMask(true, false, true, false));
            values.put("clearColor", new SixDofSceneDepthGlState.ClearColor(0.6F, 0.7F, 0.8F, 0.9F));
        }

        Map<String, Object> copy() { return Map.copyOf(values); }
        private int i(int index) { return (Integer) values.get("i" + index); }
        private void i(int index, int value) { values.put("i" + index, value); }
        private boolean b(String key) { return (Boolean) values.get(key); }
        private void b(String key, boolean value) { values.put(key, value); }

        @Override public int program() { return i(0); } @Override public void program(int v) { i(0, v); }
        @Override public int readFramebuffer() { return i(1); } @Override public void readFramebuffer(int v) { i(1, v); }
        @Override public int drawFramebuffer() { return i(2); } @Override public void drawFramebuffer(int v) { i(2, v); }
        @Override public int renderbuffer() { return i(3); } @Override public void renderbuffer(int v) { i(3, v); }
        @Override public int activeTexture() { return i(4); } @Override public void activeTexture(int v) { i(4, v); }
        @Override public int texture0() { return i(5); } @Override public void texture0(int v) { i(5, v); }
        @Override public boolean hasSamplerObjects() { return true; }
        @Override public int sampler0() { return i(6); } @Override public void sampler0(int v) { i(6, v); }
        @Override public int unpackPbo() { return i(7); } @Override public void unpackPbo(int v) { i(7, v); }
        @Override public int blendSourceRgb() { return i(8); } @Override public int blendDestinationRgb() { return i(9); }
        @Override public int blendSourceAlpha() { return i(10); } @Override public int blendDestinationAlpha() { return i(11); }
        @Override public void blendFactors(int sr, int dr, int sa, int da) { i(8, sr); i(9, dr); i(10, sa); i(11, da); }
        @Override public int blendEquationRgb() { return i(12); } @Override public int blendEquationAlpha() { return i(13); }
        @Override public void blendEquations(int rgb, int alpha) { i(12, rgb); i(13, alpha); }
        @Override public boolean blend() { return b("blend"); } @Override public void blend(boolean v) { b("blend", v); }
        @Override public boolean depthTest() { return b("depth"); } @Override public void depthTest(boolean v) { b("depth", v); }
        @Override public boolean depthMask() { return b("depthMask"); } @Override public void depthMask(boolean v) { b("depthMask", v); }
        @Override public int depthFunction() { return i(14); } @Override public void depthFunction(int v) { i(14, v); }
        @Override public boolean stencilTest() { return b("stencil"); } @Override public void stencilTest(boolean v) { b("stencil", v); }
        @Override public boolean alphaTest() { return b("alpha"); } @Override public void alphaTest(boolean v) { b("alpha", v); }
        @Override public int alphaFunction() { return i(15); } @Override public float alphaReference() { return (Float) values.get("alphaRef"); }
        @Override public void alphaFunction(int function, float reference) { i(15, function); values.put("alphaRef", reference); }
        @Override public boolean cullFace() { return b("cull"); } @Override public void cullFace(boolean v) { b("cull", v); }
        @Override public int cullFaceMode() { return i(16); } @Override public void cullFaceMode(int v) { i(16, v); }
        @Override public boolean colorLogic() { return b("logic"); } @Override public void colorLogic(boolean v) { b("logic", v); }
        @Override public int logicOperation() { return i(17); } @Override public void logicOperation(int v) { i(17, v); }
        @Override public int frontPolygonMode() { return i(18); } @Override public int backPolygonMode() { return i(19); }
        @Override public void polygonModes(int front, int back) { i(18, front); i(19, back); }
        @Override public boolean scissorTest() { return b("scissor"); } @Override public void scissorTest(boolean v) { b("scissor", v); }
        @Override public SixDofSceneDepthGlState.IntRect scissorBox() { return (SixDofSceneDepthGlState.IntRect) values.get("scissorBox"); }
        @Override public void scissorBox(SixDofSceneDepthGlState.IntRect v) { values.put("scissorBox", v); }
        @Override public SixDofSceneDepthGlState.IntRect viewport() { return (SixDofSceneDepthGlState.IntRect) values.get("viewport"); }
        @Override public void viewport(SixDofSceneDepthGlState.IntRect v) { values.put("viewport", v); }
        @Override public SixDofSceneDepthGlState.ColorMask colorMask() { return (SixDofSceneDepthGlState.ColorMask) values.get("colorMask"); }
        @Override public void colorMask(SixDofSceneDepthGlState.ColorMask v) { values.put("colorMask", v); }
        @Override public SixDofSceneDepthGlState.ClearColor clearColor() { return (SixDofSceneDepthGlState.ClearColor) values.get("clearColor"); }
        @Override public void clearColor(SixDofSceneDepthGlState.ClearColor v) { values.put("clearColor", v); }
        @Override public double clearDepth() { return (Double) values.get("clearDepth"); } @Override public void clearDepth(double v) { values.put("clearDepth", v); }
        @Override public int clearStencil() { return i(20); } @Override public void clearStencil(int v) { i(20, v); }
        @Override public boolean rasterizerDiscard() { return b("discard"); } @Override public void rasterizerDiscard(boolean v) { b("discard", v); }
        @Override public boolean framebufferSrgb() { return b("srgb"); } @Override public void framebufferSrgb(boolean v) { b("srgb", v); }
    }
}
