package crewx.clickgui.clone.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.InputStream;

/** Tahoma from Adjust, rasterized at framebuffer resolution for sharp HUD text. */
public final class TargetHudFont {
    private static Font family;
    private static TtfFont name;
    private static TtfFont number;
    private static TtfFont sign;
    private static TtfFont arrayList;
    private static int rasterScale;
    private static final int ARRAY_SUPERSAMPLE = 2;

    private TargetHudFont() { }

    private static Font family() {
        if (family != null) return family;
        try (InputStream stream = TargetHudFont.class.getResourceAsStream("/assets/crewx/font/Tahoma.ttf")) {
            if (stream != null) return family = Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (Exception ignored) {
        }
        return family = new Font("Tahoma", Font.PLAIN, 12);
    }

    private static TtfFont font(int size) {
        int scale = Math.max(1, new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor());
        if (rasterScale != scale) {
            rasterScale = scale;
            name = number = sign = arrayList = null;
        }
        if (size == 12) {
            if (name == null) name = new TtfFont(family().deriveFont(6.0F * scale));
            return name;
        }
        if (size == 10) {
            if (number == null) number = new TtfFont(family().deriveFont(5.0F * scale));
            return number;
        }
        if (sign == null) sign = new TtfFont(family().deriveFont(4.0F * scale));
        return sign;
    }

    public static float width(int size, String text) {
        return font(size).width(text) / rasterScale;
    }

    /** Text width without creating a GL atlas; safe to use while sorting modules on tick. */
    public static float measure(int size, String text) {
        float pointSize = size == 12 ? 6.0F : size == 10 ? 5.0F : 4.0F;
        return (float) family().deriveFont(pointSize)
                .getStringBounds(text, new FontRenderContext(null, true, true)).getWidth();
    }

    public static void drawPlain(int size, String text, float x, float y, int color) {
        TtfFont renderer = font(size);
        float scale = rasterScale;
        float baseline = y + size * 0.5F - 2.5F;
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPushMatrix();
        GL11.glScalef(1.0F / scale, 1.0F / scale, 1.0F);
        renderer.draw(text, x * scale, baseline * scale, color);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
    }

    /** The Adjust list uses the same Tahoma at a higher atlas resolution to retain sharp small glyphs. */
    public static float arrayWidth(String text) {
        return arrayFont().width(text) / (rasterScale * ARRAY_SUPERSAMPLE);
    }

    private static TtfFont arrayFont() {
        font(12); // refresh rasterScale if Minecraft's GUI scale changed
        if (arrayList == null) {
            arrayList = new TtfFont(family().deriveFont(6.0F * rasterScale * ARRAY_SUPERSAMPLE));
        }
        return arrayList;
    }

    public static void drawArrayPlain(String text, float x, float y, int color) {
        TtfFont renderer = arrayFont();
        float scale = rasterScale * ARRAY_SUPERSAMPLE;
        float baseline = y + 3.5F;
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPushMatrix();
        GL11.glScalef(1.0F / scale, 1.0F / scale, 1.0F);
        renderer.draw(text, x * scale, baseline * scale, color);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
    }

    public static void draw(int size, String text, float x, float y, int color) {
        TtfFont renderer = font(size);
        float scale = rasterScale;
        float baseline = y + size * 0.5F - 2.5F;
        int shadow = (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2);
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPushMatrix();
        GL11.glScalef(1.0F / scale, 1.0F / scale, 1.0F);
        renderer.draw(text, (x + 0.5F) * scale, (baseline + 0.5F) * scale, shadow);
        renderer.draw(text, x * scale, baseline * scale, color);
        GL11.glPopMatrix();
        // TtfFont uses raw OpenGL calls; restore the texture state expected by Minecraft's item renderer.
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
    }
}
