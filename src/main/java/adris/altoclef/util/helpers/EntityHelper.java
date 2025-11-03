package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.DamageSourceWrapper;
import adris.altoclef.multiversion.MethodWrapper;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.UUID;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {
    public static final double ENTITY_GRAVITY = 0.08; // per second

    public static boolean isAngryAtPlayer(AltoClef mod, Entity mob) {
        if (mob instanceof EndermanEntity enderman) {
            return isEndermanTargetingPlayer(mod, enderman);
        }

        boolean hostile = isProbablyHostileToPlayer(mod, mob);
        if (mob instanceof LivingEntity entity) {
            return hostile && entity.canSee(mod.getPlayer());
        }
        return hostile;
    }

    public static boolean isProbablyHostileToPlayer(AltoClef mod, Entity entity) {
        if (entity instanceof MobEntity mob) {
            if (mob instanceof SlimeEntity slime) {
                return slime.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) > 0;
            }
            if (mob instanceof PiglinEntity piglin) {
                return piglin.isAttacking() && !isTradingPiglin(mob) && piglin.isAdult();
            }
            if (mob instanceof EndermanEntity enderman) {
                return isEndermanTargetingPlayer(mod, enderman);
            }
            if (mob instanceof ZombifiedPiglinEntity zombifiedPiglin) {
                return zombifiedPiglin.isAttacking();
            }

            return mob.isAttacking() || mob instanceof HostileEntity;
        }

        return false;
    }

    private static boolean isEndermanTargetingPlayer(AltoClef mod, EndermanEntity enderman) {
        if (!enderman.isAlive() || !enderman.isAngry()) {
            return false;
        }

        LivingEntity target = enderman.getTarget();
        if (target != null && target == mod.getPlayer()) {
            return true;
        }

        if (enderman instanceof Angerable angerable) {
            UUID angryAt = angerable.getAngryAt();
            if (angryAt != null && angryAt.equals(mod.getPlayer().getUuid())) {
                return true;
            }
        }

        return mod.getEntityTracker().isCollidingWithPlayer(enderman);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof PiglinEntity pig) {
            if (pig.getHandItems() != null) {
                for (ItemStack stack : pig.getHandItems()) {
                    if (stack.getItem().equals(Items.GOLD_INGOT)) {
                        // We're trading with this one, ignore it.
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(PlayerEntity player, DamageSource src, double damageAmount) {
        // Copied logic from `PlayerEntity.applyDamage`
        DamageSourceWrapper source = DamageSourceWrapper.of(src);

        if (player.isInvulnerableTo(src))
            return 0;

        // Armor Base
        if (!source.bypassesArmor()) {
            damageAmount = MethodWrapper.getDamageLeft(player, damageAmount,src,player.getArmor(),player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS));
        }

        // Enchantments & Potions
        if (!source.bypassesShield()) {
            float k;
            if (player.hasStatusEffect(StatusEffects.RESISTANCE) && source.isOutOfWorld()) {
                //noinspection ConstantConditions
                k = (player.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1) * 5;
                float j = 25 - k;
                double f = damageAmount * (double) j;
                double g = damageAmount;
                damageAmount = Math.max(f / 25.0F, 0.0F);
            }

            if (damageAmount <= 0.0) {
                damageAmount = 0.0;
            } else {
                //#if MC >= 12100
                k = EnchantmentHelper.getProtectionAmount(null, player, src);
                //#else
                //$$ k = EnchantmentHelper.getProtectionAmount(player.getArmorItems(), src);
                //#endif
                if (k > 0) {
                    damageAmount = DamageUtil.getInflictedDamage((float) damageAmount, (float) k);
                }
            }
        }

        // Absorption
        damageAmount = Math.max(damageAmount - player.getAbsorptionAmount(), 0.0F);
        return damageAmount;
    }
}
