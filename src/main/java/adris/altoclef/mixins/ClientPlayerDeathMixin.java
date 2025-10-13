package adris.altoclef.mixins;

import adris.altoclef.AltoClef;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class ClientPlayerDeathMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void altoclef$onDeath(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ClientPlayerEntity clientPlayer)) {
            return;
        }
        if (clientPlayer != MinecraftClient.getInstance().player) {
            return;
        }
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getDeathMenuChain() == null) {
            return;
        }
        mod.getDeathMenuChain().notifyDeathFromClient(damageSource);
    }
}
