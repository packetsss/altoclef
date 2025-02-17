package adris.altoclef.mixins;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.utils.ToolSet;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ToolSet.class)
public class ToolSetMixin {


    @Shadow @Final private ClientPlayerEntity player;
    @Unique
    private static final Settings.Setting<Boolean> trueSetting = BaritoneAPI.getSettings().of(true);

    @Inject(method = "getBestSlot(Lnet/minecraft/block/Block;ZZ)I", at = @At("HEAD"), cancellable = true)
    public void inject(Block b, boolean preferSilkTouch, boolean pathingCalculation, CallbackInfoReturnable<Integer> cir) {
        if (b.getDefaultState().getHardness(null, null) == 0) cir.setReturnValue(this.player.inventory.selectedSlot);
    }


    @Redirect(method = "getBestSlot(Lnet/minecraft/block/Block;ZZ)I",at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;getDamage()I"))
    public int redirected(ItemStack stack,Block block) {
        if (StorageHelper.shouldSaveStack(AltoClef.INSTANCE,block,stack)) {
            return 100_000;
        }

        return stack.getDamage();
    }

    @Redirect(method = "getBestSlot(Lnet/minecraft/block/Block;ZZ)I",at = @At(value = "FIELD", target = "Lbaritone/api/Settings;itemSaver:Lbaritone/api/Settings$Setting;"), remap = false)
    public Settings.Setting<Boolean> redirected(Settings instance,Block block ,@Local ItemStack stack) {
        if (StorageHelper.shouldSaveStack(AltoClef.INSTANCE,block,stack)) {
            return trueSetting;
        }
        return instance.itemSaver;
    }

}
