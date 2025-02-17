package adris.altoclef.multiversion;

import adris.altoclef.mixins.DrawableHelperInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import org.jetbrains.annotations.Nullable;

public class DrawContextHelper {



    public static DrawContextHelper of(MatrixStack matrices) {
       if (matrices == null) return null;
       return new DrawContextHelper(matrices);
    }

    private final MatrixStack matrices;
    private final DrawableHelper helper;

    private DrawContextHelper(MatrixStack matrices) {
           this.matrices = matrices;
           this.helper = new DrawableHelper(){};
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
         DrawableHelper.fill(matrices, x1, y1, x2, y2, color);
    }

    public void drawHorizontalLine(int x1, int x2, int y, int color) {
        ((DrawableHelperInvoker) helper).invokeDrawHorizontalLine(matrices, x1, x2, y, color);
    }

    public void drawVerticalLine(int x, int y1, int y2, int color) {
        ((DrawableHelperInvoker) helper).invokeDrawVerticalLine(matrices, x, y1, y2, color);
    }

    public void drawText(TextRenderer textRenderer, @Nullable String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
           textRenderer.drawWithShadow(matrices, text,x,y,color);
        } else {
           textRenderer.draw(matrices, text,x,y,color);
        }
    }


    public MatrixStack getMatrices() {
        return matrices;
    }

    public int getScaledWindowWidth() {
        return MinecraftClient.getInstance().getWindow().getScaledWidth();
    }

    public int getScaledWindowHeight() {
        return MinecraftClient.getInstance().getWindow().getScaledHeight();
    }


}
