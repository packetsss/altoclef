package adris.altoclef.mixins;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DrawableHelper.class)
public interface DrawableHelperInvoker {

    //#if MC <= 11904
    @Invoker("drawHorizontalLine")
    void invokeDrawHorizontalLine(MatrixStack matrices, int x1, int x2, int y, int color);

    @Invoker("drawVerticalLine")
    void invokeDrawVerticalLine(MatrixStack matrices, int x, int y1, int y2, int color);
    //#endif
}
