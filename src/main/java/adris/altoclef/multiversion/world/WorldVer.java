package adris.altoclef.multiversion.world;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;



public class WorldVer {



    public static boolean isBiomeAtPos(World world, Biome biome, BlockPos pos) {
        return world.getBiome(pos) == biome;
    }



}
