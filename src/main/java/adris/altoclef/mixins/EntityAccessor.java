package adris.altoclef.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("inNetherPortal")
    boolean isInNetherPortal();

    @Accessor("netherPortalCooldown")
    int getPortalCooldown();

    //#if MC <= 11605
    @Invoker("getLandingPos")
    BlockPos invokeGetLandingPos();
    //#endif

}
