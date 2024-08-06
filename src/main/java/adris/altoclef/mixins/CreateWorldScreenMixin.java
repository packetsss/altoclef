package adris.altoclef.mixins;

import adris.altoclef.NewTaskCatalogue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.SaveLoading;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.WorldGenSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {


    @Shadow
    protected static void showMessage(MinecraftClient client, Text text) {
    }

    @Redirect(method = "create(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/gui/screen/Screen;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/world/CreateWorldScreen;showMessage(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/text/Text;)V"))
    private static void redir(MinecraftClient client, Text text) {
        if (NewTaskCatalogue.bypass) {
            // this should actually never show up and should be loaded inside the red mojang loading screen
            // adding this text just to be sure
            showMessage(client, Text.of("Initializing altoclef..."));
            return;
        }

        showMessage(client, text);
    }
}
