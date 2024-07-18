package adris.altoclef.multiversion;

import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.MobSpawnerLogic;
import net.minecraft.world.World;

//#if MC >= 11701
//$$ import net.minecraft.world.gen.SimpleRandom;
//$$ import net.minecraft.world.gen.WorldGenRandom;
//#endif

public class MethodWrapper {



    public static Entity getRenderedEntity(MobSpawnerLogic logic, World world, BlockPos pos) {
        //#if MC>12002
        //$$ return logic.getRenderedEntity(world, pos);
        //#elseif MC >= 11904
        //$$ return logic.getRenderedEntity(world,Random.create() ,pos);
        //#elseif MC >= 11701
        //$$ return logic.getRenderedEntity(world);
        //#else
        return logic.getRenderedEntity();
        //#endif
    }

    public static float getDamageLeft(double damage, DamageSource source, double armor, double armorToughness) {
        return getDamageLeft((float)damage,source,(float)armor,(float)armorToughness);
    }

    public static float getDamageLeft(float damage, DamageSource source, float armor, float armorToughness) {
        //#if MC>=12005
        //$$ return DamageUtil.getDamageLeft(damage, source, armor, armorToughness);
        //#else
        return DamageUtil.getDamageLeft(damage,armor,armorToughness);
        //#endif
    }



}
