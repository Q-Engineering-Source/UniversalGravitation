package wi.unigrav.core.client.camera;

import java.nio.FloatBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightHudCanvas;

/** Client-only bridge to UIE's public drawing canvas; never owns input or vanilla overlays. */
final class RevoUiSixDofHud {
    private RevoUiSixDofHud() {
    }

    static void render(Minecraft minecraft, SixDofHudFrame frame) {
        FlightHudCanvas canvas = FlightApi.getHudCanvas();
        if (canvas == null) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(minecraft);
        DrawingState state = new DrawingState();
        try {
            state.prepare();
            SixDofHudRenderer.draw(canvas, frame,
                    resolution.getScaledWidth(), resolution.getScaledHeight());
        } finally {
            state.restore();
        }
    }

    /**
     * Standalone canvas calls are outside UIE's built-in component state boundary.
     * Restore every state touched by its line/fill/text primitives through Minecraft's
     * cache-aware setters (including blend factors and the font texture binding).
     */
    private static final class DrawingState {
        private final boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        private final int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        private final FloatBuffer color = BufferUtils.createFloatBuffer(4);

        private DrawingState() {
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        }

        private void prepare() {
            GlStateManager.disableTexture2D();
            GlStateManager.disableDepth();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        private void restore() {
            GL11.glLineWidth(lineWidth);
            GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GlStateManager.bindTexture(textureBinding);
            if (texture) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
        }
    }
}
