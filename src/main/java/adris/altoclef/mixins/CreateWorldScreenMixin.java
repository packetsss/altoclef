package adris.altoclef.mixins;

import adris.altoclef.newtaskcatalogue.dataparser.DataParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {


    @Shadow
    protected static void showMessage(MinecraftClient client, Text text) {
    }

    @Redirect(method = "create(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/gui/screen/Screen;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/world/CreateWorldScreen;showMessage(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/text/Text;)V"))
    private static void redir(MinecraftClient client, Text text) {
        if (DataParser.bypass) {
            // this should actually never show up and should be loaded inside the red mojang loading screen
            // adding this text just to be sure
            showMessage(client, Text.of("Initializing altoclef..."));
            return;
        }

        showMessage(client, text);
    }
}
