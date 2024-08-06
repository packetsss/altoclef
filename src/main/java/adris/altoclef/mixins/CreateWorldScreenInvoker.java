package adris.altoclef.mixins;

import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoading;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {

    @Invoker("createLevelInfo")
    LevelInfo invokeCreateLevelInfo(boolean debugWorld);

    @Invoker("createSession")
    Optional<LevelStorage.Session> invokeCreateSession();

}
