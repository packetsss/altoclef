package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.multiversion.versionedfields.Entities;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.construction.ProjectileProtectionWallTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.defense.DefenseFailsafeTask;
import adris.altoclef.tasks.defense.DefenseFailsafeTask.Reason;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.RunAwayFromCreepersTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.movement.GetOutOfWaterTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.Baritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;


import java.util.*;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;


// TODO: Optimise shielding against spiders and skeletons

public class MobDefenseChain extends SingleTaskChain {
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;
    private static final double SAFE_KEEP_DISTANCE = 8;
    private static final double REACHABILITY_DISTANCE_MAX = 32;
    private static final int REACHABILITY_MAX_EXPANSIONS = 96;
    private static final int REACHABILITY_CACHE_TTL_TICKS = 40;
    private static final double MELEE_VERTICAL_THRESHOLD = 5.5;
    private static final double RANGED_VERTICAL_THRESHOLD = 28;
    private static final double LOS_CHECK_RANGE = 40;
    private static final double WATER_STUCK_SPEED_THRESHOLD = 0.05;
    private static final double WATER_STUCK_TIMEOUT_SECONDS = 10;
    private static final double NO_DAMAGE_TIMEOUT_SECONDS = 25;
    private static final double DEFENSE_FAILSAFE_TIMEOUT_SECONDS = 140;
    private static final double STALE_TARGET_TIMEOUT_SECONDS = 90;
    private static final double PERSISTENT_THREAT_TIMEOUT_SECONDS = 150;
    private static final double DIAGNOSTIC_INTERVAL_SECONDS = 1.25;
    private static final double CLOSE_FORCE_DISTANCE = 2.75;
    private static final double PANIC_CLOSE_DISTANCE = 4.5;
    private static final double RETREAT_END_DISTANCE = 10;
    private static final double RETREAT_RESUME_DISTANCE = 18;
    private static final float CRITICAL_HEALTH_THRESHOLD = 8f;
    private static final double PANIC_RETREAT_MIN_HOLD_SECONDS = 2.5;
    private static final double PANIC_RETREAT_LOG_COOLDOWN_SECONDS = 0.5;
    private static final double IMMEDIATE_THREAT_HOLD_SECONDS = 3.0;
    private static final double SHIELD_STALL_WINDOW_SECONDS = 6.0;
    private static final double SHIELD_STALL_MOVEMENT_THRESHOLD = 1.1;
    private static final double PROJECTILE_HOLD_MAX_SECONDS = 3.5;
    private static final double DEFENSE_IDLE_STALL_SECONDS = 8.0;
    private static final double DEFENSE_IDLE_MOVEMENT_THRESHOLD = 2.0;
    private static final int FAILSAFE_PILLAR_EXTRA_HEIGHT = 4;
    private static final float DEFENSE_BASE_PRIORITY = 60f;
    private static final long IGNORED_LOG_COOLDOWN_TICKS = 100;
    private static final Item[] AXE_WEAPONS = new Item[]{Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE};
    private static final List<Class<? extends Entity>> ignoredMobs = List.of(Entities.WARDEN, WitherEntity.class,
            WitherSkeletonEntity.class, HoglinEntity.class, ZoglinEntity.class, PiglinBruteEntity.class, VindicatorEntity.class, MagmaCubeEntity.class);

    private static boolean shielding = false;
    private final DragonBreathTracker dragonBreathTracker = new DragonBreathTracker();
    private final KillAura killAura = new KillAura();
    private Entity targetEntity;
    private boolean doingFunkyStuff = false;
    private boolean wasPuttingOutFire = false;
    private CustomBaritoneGoalTask runAwayTask;
    private float prevHealth = 20;
    private boolean needsChangeOnAttack = false;
    private Entity lockedOnEntity = null;

    private float cachedLastPriority;
    private float prevAbsorption = 0;

    private final TimerGame defenseActiveTimer = new TimerGame(DEFENSE_FAILSAFE_TIMEOUT_SECONDS);
    private final TimerGame noDamageTimer = new TimerGame(NO_DAMAGE_TIMEOUT_SECONDS);
    private final TimerGame waterStuckTimer = new TimerGame(WATER_STUCK_TIMEOUT_SECONDS);
    private final TimerGame staleTargetTimer = new TimerGame(STALE_TARGET_TIMEOUT_SECONDS);
    private final TimerGame persistentThreatTimer = new TimerGame(PERSISTENT_THREAT_TIMEOUT_SECONDS);
    private final TimerGame diagnosticsTimer = new TimerGame(DIAGNOSTIC_INTERVAL_SECONDS);
    private final TimerGame panicRetreatHoldTimer = new TimerGame(PANIC_RETREAT_MIN_HOLD_SECONDS);
    private final TimerGame panicRetreatLogTimer = new TimerGame(PANIC_RETREAT_LOG_COOLDOWN_SECONDS);
    private final TimerGame immediateThreatHoldTimer = new TimerGame(IMMEDIATE_THREAT_HOLD_SECONDS);

    private DefenseState defenseState = DefenseState.IDLE;
    private DefenseFailsafeTask failsafeTask;
    private List<ThreatSnapshot> latestThreats = Collections.emptyList();
    private final Map<UUID, ReachabilityCacheEntry> reachabilityCache = new HashMap<>();
    private final Map<UUID, Long> ignoredLogTimestamps = new HashMap<>();
    private Entity lastPrimaryThreat;
    private boolean playerTookDamageThisTick;
    private boolean absorptionLostThisTick;
    private Vec3d lastPlayerPos;
    private double lastWaterStuckLogSeconds = 0;
    private boolean panicRetreatActive = false;
    private final TimerGame shieldHoldTimer = new TimerGame(SHIELD_STALL_WINDOW_SECONDS);
    private Vec3d shieldHoldAnchor = null;
    private boolean shieldHoldTriggered = false;
    private final TimerGame projectileHoldTimer = new TimerGame(PROJECTILE_HOLD_MAX_SECONDS);
    private boolean projectileHoldActive = false;
    private Vec3d defenseIdleAnchor = null;
    private final TimerGame defenseIdleTimer = new TimerGame(DEFENSE_IDLE_STALL_SECONDS);

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
        panicRetreatHoldTimer.forceElapse();
        panicRetreatLogTimer.forceElapse();
        immediateThreatHoldTimer.forceElapse();
    }

    private enum DefenseState {
        IDLE,
        ACTIVE,
        RETREAT,
        FAILSAFE
    }

    private static class ThreatSnapshot {
        final LivingEntity entity;
        final Vec3d position;
        final double distanceSq;
        final double deltaY;
        final boolean projectile;
        final boolean reachable;
        final boolean pathBudgetHit;
        final boolean hasLineOfSight;
        final boolean acrossWater;
        final boolean immediate;

        ThreatSnapshot(LivingEntity entity, Vec3d position, double distanceSq, double deltaY,
                       boolean projectile, boolean reachable, boolean pathBudgetHit,
                       boolean hasLineOfSight, boolean acrossWater, boolean immediate) {
            this.entity = entity;
            this.position = position;
            this.distanceSq = distanceSq;
            this.deltaY = deltaY;
            this.projectile = projectile;
            this.reachable = reachable;
            this.pathBudgetHit = pathBudgetHit;
            this.hasLineOfSight = hasLineOfSight;
            this.acrossWater = acrossWater;
            this.immediate = immediate;
        }
    }

    private static class CombatAssessment {
        final boolean hasShield;
        final boolean hasMeleeWeapon;
        final int armorValue;
        final float totalHealth;
        final boolean lowGear;

        CombatAssessment(boolean hasShield, boolean hasMeleeWeapon, int armorValue, float totalHealth, boolean lowGear) {
            this.hasShield = hasShield;
            this.hasMeleeWeapon = hasMeleeWeapon;
            this.armorValue = armorValue;
            this.totalHealth = totalHealth;
            this.lowGear = lowGear;
        }
    }

    private static class ReachabilityCacheEntry {
        final BlockPos targetPos;
        final boolean reachable;
        final boolean budgetHit;
        final long tickUpdated;

        ReachabilityCacheEntry(BlockPos targetPos, boolean reachable, boolean budgetHit, long tickUpdated) {
            this.targetPos = targetPos;
            this.reachable = reachable;
            this.budgetHit = budgetHit;
            this.tickUpdated = tickUpdated;
        }
    }

    public static double getCreeperSafety(Vec3d pos, CreeperEntity creeper) {
        double distance = creeper.squaredDistanceTo(pos);
        float fuse = creeper.getClientFuseTime(1);

        // Not fusing.
        if (fuse <= 0.001f) return distance;
        return distance * 0.2; // less is WORSE
    }

    private static void startShielding(AltoClef mod) {
        shielding = true;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        if (!mod.getPlayer().isBlocking()) {
            ItemStack handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
            if (ItemVer.isFood(handItem)) {
                List<ItemStack> spaceSlots = mod.getItemStorage().getItemStacksPlayerInventory(false);
                for (ItemStack spaceSlot : spaceSlots) {
                    if (spaceSlot.isEmpty()) {
                        mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, SlotActionType.QUICK_MOVE);
                        return;
                    }
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                garbage.ifPresent(slot -> mod.getSlotHandler().forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
            }
        }
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
    }

    private static int getDangerousnessScore(List<LivingEntity> toDealWithList) {
        int numberOfProblematicEntities = toDealWithList.size();
        for (LivingEntity toDealWith : toDealWithList) {
            if (toDealWith instanceof EndermanEntity || toDealWith instanceof SlimeEntity || toDealWith instanceof BlazeEntity) {

                numberOfProblematicEntities += 1;
            } else if (toDealWith instanceof DrownedEntity && toDealWith.getEquippedItems() == Items.TRIDENT) {
                // Drowned with tridents are also REALLY dangerous, maybe we should increase this??
                numberOfProblematicEntities += 5;
            }
        }
        return numberOfProblematicEntities;
    }

    @Override
    public float getPriority() {
        cachedLastPriority = getPriorityInner();
        prevHealth = AltoClef.getInstance().getPlayer().getHealth();
        prevAbsorption = AltoClef.getInstance().getPlayer().getAbsorptionAmount();
        return cachedLastPriority;
    }

    private void stopShielding(AltoClef mod) {
        if (shielding) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (ItemVer.isFood(cursor)) {
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                if (toMoveTo.isPresent()) {
                    Slot garbageSlot = toMoveTo.get();
                    mod.getSlotHandler().clickSlot(garbageSlot, 0, SlotActionType.PICKUP);
                }
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            shielding = false;
        }
    }

    public boolean isShielding() {
        return shielding || killAura.isShielding();
    }

    private boolean escapeDragonBreath(AltoClef mod) {
        dragonBreathTracker.updateBreath(mod);
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer()) {
            if (dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                return true;
            }
        }
        return false;
    }

    private float getPriorityInner() {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }
        AltoClef mod = AltoClef.getInstance();

        if (!mod.getModSettings().isMobDefense()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (mod.getWorld().getDifficulty() == Difficulty.PEACEFUL) return Float.NEGATIVE_INFINITY;

        updateDamageTracking(mod);
        latestThreats = evaluateThreats(mod);
        boolean targetIsNonHostile = targetEntity != null && !EntityHelper.isProbablyHostileToPlayer(mod, targetEntity);

        if (diagnosticsTimer.elapsed()) {
            emitDiagnostics(mod);
            diagnosticsTimer.reset();
        }

        boolean panicTriggeredThisTick = shouldPanicRetreat(mod);
        boolean panicJustActivated = false;
        if (panicTriggeredThisTick) {
            if (!panicRetreatActive) {
                panicRetreatActive = true;
                panicJustActivated = true;
                panicRetreatLogTimer.forceElapse();
            }
            panicRetreatHoldTimer.reset();
        } else if (panicRetreatActive && panicRetreatHoldTimer.elapsed()) {
            panicRetreatActive = false;
        }

        boolean hasImmediateThreat = latestThreats.stream().anyMatch(snapshot -> snapshot.immediate);
        if (hasImmediateThreat) {
            immediateThreatHoldTimer.reset();
            if (defenseState == DefenseState.IDLE) {
                defenseState = DefenseState.ACTIVE;
                defenseActiveTimer.reset();
                noDamageTimer.reset();
                staleTargetTimer.reset();
                persistentThreatTimer.reset();
            }
        } else {
            String dropReason = getDropDefenseReason(mod);
            if (dropReason != null) {
                clearDefenseState(dropReason);
                return Float.NEGATIVE_INFINITY;
            }
        }

        updateWaterProgress(mod);
        maybeTriggerFailsafe(mod);
        float baselinePriority = defenseState == DefenseState.IDLE ? 0f : DEFENSE_BASE_PRIORITY;

        if (failsafeTask != null) {
            if (failsafeTask.isFinished()) {
                failsafeTask = null;
                defenseState = DefenseState.ACTIVE;
                staleTargetTimer.reset();
                persistentThreatTimer.reset();
            } else {
                defenseState = DefenseState.FAILSAFE;
                setTask(failsafeTask);
                return 90;
            }
        }

        if (needsChangeOnAttack && (mod.getPlayer().getHealth() < prevHealth || killAura.attackedLastTick)) {
            needsChangeOnAttack = false;
        }

        // Put out fire if we're standing on one like an idiot
        BlockPos fireBlock = isInsideFireAndOnFire(mod);
        if (fireBlock != null) {
            putOutFire(mod, fireBlock);
            wasPuttingOutFire = true;
        } else {
            // Stop putting stuff out if we no longer need to put out a fire.
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            wasPuttingOutFire = false;
        }

        // Run away if a weird mob is close by.
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent() && mod.getPlayer().getHealth() <= 10) {
            runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            defenseState = DefenseState.RETREAT;
            staleTargetTimer.reset();
            setTask(runAwayTask);
            return 70;
        }

        doingFunkyStuff = false;
        PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
        Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
        // Run away from creepers
    CreeperEntity blowingUp = getClosestFusingCreeper(mod);
        if (blowingUp != null) {
            if ((!mod.getFoodChain().needsToEat() || mod.getPlayer().getHealth() < 9)
                    && hasShield(mod)
                    && !mod.getEntityTracker().entityFound(PotionEntity.class)
                    && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()
                    && blowingUp.getClientFuseTime(blowingUp.getFuseSpeed()) > 0.5) {
                LookHelper.lookAt(mod, blowingUp.getEyePos());
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            } else {
                doingFunkyStuff = true;
                runAwayTask = new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
                defenseState = DefenseState.RETREAT;
                staleTargetTimer.reset();
                setTask(runAwayTask);
                return 50 + blowingUp.getClientFuseTime(1) * 50;
            }
        }
        boolean projectileIncoming = false;
        boolean projectileTimeoutTriggered = false;
        Vec3d projectileTimeoutAnchor = null;
        double projectileTimeoutDuration = 0;
        double projectileTimeoutDisplacement = 0;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            projectileIncoming = isProjectileClose(mod);
            // Block projectiles with shield
            if (mod.getModSettings().isDodgeProjectiles()
                    && hasShield(mod)
                    && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()
                    && !mod.getEntityTracker().entityFound(PotionEntity.class) && projectileIncoming) {
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                    resetShieldHoldState();
                    projectileHoldActive = false;
                } else {
                    if (handleShieldHoldStall(mod, "ProjectileShieldHold")) {
                        projectileHoldActive = false;
                        return 80;
                    }
                    if (!projectileHoldActive) {
                        projectileHoldTimer.reset();
                        projectileHoldActive = true;
                    }
                    if (projectileHoldTimer.elapsed()) {
                        Vec3d anchor = shieldHoldAnchor != null ? shieldHoldAnchor : mod.getPlayer().getPos();
                        double displacement = anchor != null ? Math.sqrt(mod.getPlayer().getPos().squaredDistanceTo(anchor)) : 0;
                        projectileTimeoutAnchor = anchor;
                        projectileTimeoutDuration = projectileHoldTimer.getDuration();
                        projectileTimeoutDisplacement = displacement;
                        projectileTimeoutTriggered = true;
                        projectileHoldActive = false;
                        resetShieldHoldState();
                    } else {
                        startShielding(mod);
                        return 60;
                    }
                }
            }
            if (blowingUp == null && !projectileIncoming) {
                stopShielding(mod);
            }
            if (!projectileIncoming) {
                projectileHoldActive = false;
                resetShieldHoldState();
            }
        }

        if (projectileTimeoutTriggered) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("stall_type", "projectile_timeout");
            respondToDefenseStall(mod, "ProjectileShieldTimeout", projectileTimeoutAnchor, projectileTimeoutDuration, projectileTimeoutDisplacement, extras);
            projectileHoldTimer.reset();
            return 85;
        }

        boolean projectileThreatPresent = projectileIncoming || latestThreats.stream().anyMatch(snapshot -> snapshot.projectile && snapshot.hasLineOfSight);
        if (!hasShield(mod) && projectileThreatPresent && mod.getModSettings().isDodgeProjectiles() && !panicRetreatActive) {
            doingFunkyStuff = true;
            defenseState = DefenseState.RETREAT;
            staleTargetTimer.reset();
            if (StorageHelper.getNumberOfThrowawayBlocks(mod) > 0) {
                if (!(mainTask instanceof ProjectileProtectionWallTask)) {
                    setTask(new ProjectileProtectionWallTask(mod));
                }
                return 70;
            }
            runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
            setTask(runAwayTask);
            return 65;
        }

        if (targetIsNonHostile
                && !hasImmediateThreat
                && blowingUp == null
                && !projectileIncoming
                && defenseState != DefenseState.RETREAT
                && defenseState != DefenseState.FAILSAFE
                && !panicRetreatActive) {
            killAura.stopShielding(mod);
            stopShielding(mod);
        }

        boolean mustPauseForUtility = mod.getFoodChain().needsToEat() || mod.getMLGBucketChain().isFalling(mod)
                || !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting();
        if (mustPauseForUtility) {
            killAura.stopShielding(mod);
            stopShielding(mod);
            if (!panicRetreatActive) {
                return Float.NEGATIVE_INFINITY;
            }
        }

        if (panicRetreatActive) {
            doingFunkyStuff = true;
            if (panicJustActivated || !(runAwayTask instanceof RunAwayFromHostilesTask) || runAwayTask.isFinished()) {
                runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            }
            defenseState = DefenseState.RETREAT;
            staleTargetTimer.reset();
            if (panicRetreatLogTimer.elapsed()) {
                float health = mod.getPlayer().getHealth();
                float absorption = mod.getPlayer().getAbsorptionAmount();
                double closest = latestThreats.stream()
                        .mapToDouble(snapshot -> Math.sqrt(snapshot.distanceSq))
                        .min()
                        .orElse(Double.NaN);
                Debug.logMessage(String.format(Locale.ROOT,
                        "[MobDefense] Critical health panic retreat %s hp=%.1f+%.1f threats=%d closest=%s",
                        panicJustActivated ? "start" : "hold",
                        health,
                        absorption,
                        latestThreats.size(),
                        Double.isNaN(closest) ? "n/a" : String.format(Locale.ROOT, "%.1f", closest)), false);
                panicRetreatLogTimer.reset();
            }
            setTask(runAwayTask);
            return 85;
        }

        // Force field
        doForceField(mod);

        // Dodge projectiles
        if (mod.getPlayer().getHealth() <= 10 && !hasShield(mod) && projectileThreatPresent) {
            runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
            defenseState = DefenseState.RETREAT;
            staleTargetTimer.reset();
            setTask(runAwayTask);
            return 65;
        }
        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod) && !escapeDragonBreath(mod) && !mod.getFoodChain().isShouldStop()) {
            if (targetEntity == null || WorldHelper.isSurroundedByHostiles()) {
                runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                defenseState = DefenseState.RETREAT;
                staleTargetTimer.reset();
                setTask(runAwayTask);
                return 70;
            }
        }

    if (mod.getModSettings().shouldDealWithAnnoyingHostiles()) {
            List<ThreatSnapshot> annoyanceCandidates = latestThreats.stream()
                    .filter(snapshot -> snapshot.hasLineOfSight)
                    .filter(snapshot -> snapshot.distanceSq < (hasShield(mod) ? 35 * 35 : 25 * 25))
                    .collect(Collectors.toList());

            List<LivingEntity> toDealWithList = new ArrayList<>();

            for (ThreatSnapshot threat : annoyanceCandidates) {
                LivingEntity hostile = threat.entity;
                boolean isRangedOrPoisonous = (hostile instanceof SkeletonEntity
                        || hostile instanceof WitchEntity || hostile instanceof PillagerEntity
                        || hostile instanceof PiglinEntity || hostile instanceof StrayEntity
                        || hostile instanceof CaveSpiderEntity);

                double annoyingRange = isRangedOrPoisonous ? (hasShield(mod) ? 20 : 35) : 10;

                if (Math.sqrt(threat.distanceSq) > annoyingRange) continue;
                if (threat.acrossWater && !threat.reachable) continue;

                boolean isIgnored = false;
                for (Class<? extends Entity> ignored : ignoredMobs) {
                    if (ignored.isInstance(hostile)) {
                        isIgnored = true;
                        break;
                    }
                }

                if (isIgnored) {
                    if (mod.getPlayer().getHealth() <= 10) {
                        toDealWithList.add(hostile);
                    } else {
                        maybeLogIgnored(mod, hostile, "ignore-list");
                    }
                } else {
                    toDealWithList.add(hostile);
                }
            }

            toDealWithList.sort(Comparator.comparingDouble(mod.getPlayer()::distanceTo));

            if (!toDealWithList.isEmpty()) {

                // Depending on our weapons/armor, we may choose to straight up kill hostiles if we're not dodging their arrows.
                SwordItem bestSword = getBestSword(mod);

                int armor = mod.getPlayer().getArmor();
                float damage = bestSword == null ? 0 : (bestSword.getMaterial().getAttackDamage()) + 1;

                int shield = hasShield(mod) && bestSword != null ? 3 : 0;

                int canDealWith = (int) Math.ceil((armor * 3.6 / 20.0) + (damage * 0.8) + (shield));

                if (canDealWith >= getDangerousnessScore(toDealWithList) || needsChangeOnAttack) {
                    // we just decided to attack, so we should either get it, or hit something before running away again
                    if (!(mainTask instanceof KillEntitiesTask)) {
                        needsChangeOnAttack = true;
                    }

                    // We can deal with it.
                    runAwayTask = null;
                    Entity toKill = toDealWithList.get(0);
                    lockedOnEntity = toKill;

                    setTask(new KillEntitiesTask(toKill.getClass()));
                    defenseState = DefenseState.ACTIVE;
                    staleTargetTimer.reset();
                    return 65;
                } else {
                    // We can't deal with it
                    runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                    defenseState = DefenseState.RETREAT;
                    staleTargetTimer.reset();
                    setTask(runAwayTask);
                    return 80;
                }
            }
        }
        Float engagePriority = tryEngagePrimaryThreat(mod);
        if (engagePriority != null) {
            return engagePriority;
        }
        if (handleDefenseIdleStall(mod)) {
            return 80;
        }
        // By default, if we aren't "immediately" in danger but were running away, keep
        // running away until we're good.
        if (runAwayTask != null && !runAwayTask.isFinished()) {
            defenseState = DefenseState.RETREAT;
            setTask(runAwayTask);
            return cachedLastPriority;
        } else {
            runAwayTask = null;
        }

        if (needsChangeOnAttack && lockedOnEntity != null && lockedOnEntity.isAlive()) {
            setTask(new KillEntitiesTask(lockedOnEntity.getClass()));
            defenseState = DefenseState.ACTIVE;
            staleTargetTimer.reset();
            return 65;
        } else {
            needsChangeOnAttack = false;
            lockedOnEntity = null;
        }

        if (defenseState == DefenseState.RETREAT && !latestThreats.isEmpty()) {
            double closestSq = latestThreats.stream()
                    .mapToDouble(snapshot -> snapshot.distanceSq)
                    .min()
                    .orElse(Double.POSITIVE_INFINITY);
            if (closestSq <= RETREAT_END_DISTANCE * RETREAT_END_DISTANCE) {
                Debug.logMessage(String.format(Locale.ROOT,
                        "[MobDefense] Hold retreat, closest threat %.1f blocks",
                        Math.sqrt(closestSq)), false);
                setTask(runAwayTask != null ? runAwayTask : new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true));
                staleTargetTimer.reset();
                return 70;
            }
        }

        return baselinePriority;
    }

    private void updateDamageTracking(AltoClef mod) {
        float health = mod.getPlayer().getHealth();
        float absorption = mod.getPlayer().getAbsorptionAmount();

        playerTookDamageThisTick = health + 0.01f < prevHealth;
        absorptionLostThisTick = absorption + 0.01f < prevAbsorption;

        if (playerTookDamageThisTick || absorptionLostThisTick || killAura.attackedLastTick) {
            noDamageTimer.reset();
            if (playerTookDamageThisTick) {
                staleTargetTimer.reset();
            }
        }
    }

    private List<ThreatSnapshot> evaluateThreats(AltoClef mod) {
        List<ThreatSnapshot> snapshots = new ArrayList<>();
        Vec3d playerPos = mod.getPlayer().getPos();

        List<LivingEntity> hostiles = mod.getEntityTracker().getHostiles();

        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            for (LivingEntity hostile : hostiles) {
                if (hostile == null || !hostile.isAlive()) continue;
                if (mod.getBehaviour().shouldExcludeFromForcefield(hostile)) continue;
                if (!EntityHelper.isProbablyHostileToPlayer(mod, hostile)) continue;

                Vec3d position = hostile.getPos();
                double distanceSq = position.squaredDistanceTo(playerPos);
                double deltaY = position.y - playerPos.y;

                boolean projectile = isProjectileMob(hostile);
                boolean hasLineOfSight = LookHelper.seesPlayer(hostile, mod.getPlayer(), LOS_CHECK_RANGE);
                boolean acrossWater = isAcrossWater(mod, hostile);

                ReachabilityCacheEntry reachEntry = getReachabilityEntry(mod, hostile);

                boolean immediate = isImmediateThreat(hostile, distanceSq, deltaY, projectile, hasLineOfSight, reachEntry.reachable, acrossWater);

                ThreatSnapshot snapshot = new ThreatSnapshot(hostile, position, distanceSq, deltaY, projectile,
                        reachEntry.reachable, reachEntry.budgetHit, hasLineOfSight, acrossWater, immediate);
                snapshots.add(snapshot);
            }
        }

        snapshots.sort(Comparator.comparingDouble(threat -> threat.distanceSq));

    Entity newPrimary = snapshots.stream()
        .filter(threat -> threat.immediate)
        .filter(threat -> canDirectlyEngage(mod, threat))
        .map(threat -> threat.entity)
        .findFirst()
        .orElse(null);

        if (newPrimary != null) {
            if (lastPrimaryThreat == null || !lastPrimaryThreat.getUuid().equals(newPrimary.getUuid())) {
                staleTargetTimer.reset();
            }
            lastPrimaryThreat = newPrimary;
        } else if (lastPrimaryThreat != null) {
            staleTargetTimer.reset();
            lastPrimaryThreat = null;
        }

        if (snapshots.isEmpty()) {
            persistentThreatTimer.reset();
        }

        return snapshots;
    }

    private String getDropDefenseReason(AltoClef mod) {
        if (defenseState == DefenseState.IDLE) {
            return "idle";
        }
        if (panicRetreatActive && !panicRetreatHoldTimer.elapsed()) {
            return null;
        }
        if (!immediateThreatHoldTimer.elapsed()) {
            return null;
        }
        if (latestThreats.isEmpty()) {
            return "no-threats";
        }
        boolean lingeringThreat = latestThreats.stream().anyMatch(snapshot -> snapshot.hasLineOfSight && snapshot.distanceSq < DANGER_KEEP_DISTANCE * DANGER_KEEP_DISTANCE);
        if (lingeringThreat) {
            return null;
        }
        if (defenseState == DefenseState.RETREAT) {
            boolean threatsInsideEnd = latestThreats.stream().anyMatch(snapshot -> snapshot.distanceSq < RETREAT_END_DISTANCE * RETREAT_END_DISTANCE);
            if (threatsInsideEnd) {
                return null;
            }
        }
        boolean closeThreat = latestThreats.stream().anyMatch(snapshot -> snapshot.distanceSq < RETREAT_RESUME_DISTANCE * RETREAT_RESUME_DISTANCE);
        if (closeThreat) {
            return null;
        }
        if (playerTookDamageThisTick || absorptionLostThisTick) {
            return null;
        }
        if (!noDamageTimer.elapsed()) {
            return null;
        }
        if (mod.getPlayer().isTouchingWater()) {
            return null;
        }
        return "timers-clear";
    }

    private void clearDefenseState() {
        clearDefenseState(null);
    }

    private void clearDefenseState(@Nullable String reason) {
        boolean wasEngaged = defenseState != DefenseState.IDLE
                || runAwayTask != null
                || failsafeTask != null
                || mainTask != null
                || !latestThreats.isEmpty()
                || needsChangeOnAttack
                || lockedOnEntity != null;
        int threatCount = latestThreats.size();
        DefenseState previousState = defenseState;
        if (mainTask != null) {
            setTask(null);
        }
        defenseState = DefenseState.IDLE;
        runAwayTask = null;
        failsafeTask = null;
        lastPrimaryThreat = null;
        latestThreats = Collections.emptyList();
        staleTargetTimer.reset();
        persistentThreatTimer.reset();
        defenseActiveTimer.reset();
        waterStuckTimer.reset();
        lastWaterStuckLogSeconds = 0;
        noDamageTimer.reset();
        needsChangeOnAttack = false;
        lockedOnEntity = null;
    ignoredLogTimestamps.clear();
        panicRetreatActive = false;
        panicRetreatHoldTimer.forceElapse();
        panicRetreatLogTimer.forceElapse();
        immediateThreatHoldTimer.forceElapse();
        resetShieldHoldState();
    projectileHoldActive = false;
    projectileHoldTimer.forceElapse();
    defenseIdleAnchor = null;
    defenseIdleTimer.forceElapse();
        AltoClef mod = AltoClef.inGame() ? AltoClef.getInstance() : null;
        if (mod != null) {
            killAura.stopShielding(mod);
            stopShielding(mod);
        }
        if (wasEngaged) {
            String dim = mod != null && mod.getWorld() != null
                    ? mod.getWorld().getRegistryKey().getValue().toString()
                    : "<unknown>";
            Vec3d pos = mod != null && mod.getPlayer() != null ? mod.getPlayer().getPos() : Vec3d.ZERO;
            String posShort = mod != null && mod.getPlayer() != null ? mod.getPlayer().getBlockPos().toShortString() : "<unknown>";
            Debug.logMessage(String.format(Locale.ROOT,
                    "[MobDefense] Clearing state reason=%s prevState=%s dim=%s pos=%s (%.1f, %.1f, %.1f) threats=%d",
                    reason != null ? reason : "<unspecified>",
                    previousState,
                    dim,
                    posShort,
                    pos.x,
                    pos.y,
                    pos.z,
                    threatCount), false);
        }
    }

    private void updateWaterProgress(AltoClef mod) {
        Vec3d currentPos = mod.getPlayer().getPos();
        if (lastPlayerPos == null) {
            lastPlayerPos = currentPos;
            waterStuckTimer.reset();
            lastWaterStuckLogSeconds = 0;
            return;
        }
        double movementSq = currentPos.squaredDistanceTo(lastPlayerPos);
        lastPlayerPos = currentPos;

        if (!mod.getPlayer().isTouchingWater()) {
            waterStuckTimer.reset();
            lastWaterStuckLogSeconds = 0;
            return;
        }

        double thresholdSq = WATER_STUCK_SPEED_THRESHOLD * WATER_STUCK_SPEED_THRESHOLD;
        if (movementSq > thresholdSq) {
            waterStuckTimer.reset();
            lastWaterStuckLogSeconds = 0;
            return;
        }

        double elapsed = waterStuckTimer.getDuration();
        if (elapsed > 2 && elapsed - lastWaterStuckLogSeconds >= 2) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[MobDefense] Water stall timer %.1fs (movementSq=%.5f)",
                    elapsed,
                    movementSq), false);
            lastWaterStuckLogSeconds = elapsed;
        }
    }

    private boolean handleShieldHoldStall(AltoClef mod, String reason) {
        if (mod.getPlayer() == null || defenseState == DefenseState.FAILSAFE) {
            resetShieldHoldState();
            return false;
        }
        if (latestThreats.isEmpty()) {
            shieldHoldAnchor = null;
            shieldHoldTriggered = false;
            return false;
        }
        Vec3d currentPos = mod.getPlayer().getPos();
        if (shieldHoldAnchor == null) {
            shieldHoldAnchor = currentPos;
            shieldHoldTimer.reset();
            shieldHoldTriggered = false;
            return false;
        }
        double displacementSq = currentPos.squaredDistanceTo(shieldHoldAnchor);
        double thresholdSq = SHIELD_STALL_MOVEMENT_THRESHOLD * SHIELD_STALL_MOVEMENT_THRESHOLD;
        if (displacementSq > thresholdSq) {
            shieldHoldAnchor = currentPos;
            shieldHoldTimer.reset();
            shieldHoldTriggered = false;
            return false;
        }
        if (!shieldHoldTriggered && shieldHoldTimer.elapsed()) {
            shieldHoldTriggered = true;
            Vec3d anchor = shieldHoldAnchor;
            double duration = shieldHoldTimer.getDuration();
            double displacement = Math.sqrt(displacementSq);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("stall_type", "projectile_shield");
            respondToDefenseStall(mod, reason, anchor, duration, displacement, extras);
            shieldHoldAnchor = currentPos;
            shieldHoldTimer.reset();
            shieldHoldTriggered = false;
            return true;
        }
        return false;
    }

    private boolean handleDefenseIdleStall(AltoClef mod) {
        if (mod.getPlayer() == null || defenseState == DefenseState.FAILSAFE) {
            defenseIdleAnchor = null;
            return false;
        }
        if (mainTask != null || (runAwayTask != null && !runAwayTask.isFinished()) || failsafeTask != null || panicRetreatActive) {
            defenseIdleAnchor = null;
            return false;
        }
        if (latestThreats.isEmpty() || latestThreats.stream().noneMatch(snapshot -> snapshot.immediate)) {
            defenseIdleAnchor = null;
            return false;
        }
        Vec3d currentPos = mod.getPlayer().getPos();
        if (defenseIdleAnchor == null) {
            defenseIdleAnchor = currentPos;
            defenseIdleTimer.reset();
            return false;
        }
        double displacementSq = currentPos.squaredDistanceTo(defenseIdleAnchor);
        double thresholdSq = DEFENSE_IDLE_MOVEMENT_THRESHOLD * DEFENSE_IDLE_MOVEMENT_THRESHOLD;
        if (displacementSq > thresholdSq) {
            defenseIdleAnchor = currentPos;
            defenseIdleTimer.reset();
            return false;
        }
        if (defenseIdleTimer.elapsed()) {
            Vec3d anchor = defenseIdleAnchor;
            double displacement = Math.sqrt(displacementSq);
            double duration = defenseIdleTimer.getDuration();
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("stall_type", "idle_no_task");
            respondToDefenseStall(mod, "IdleNoTask", anchor, duration, displacement, extras);
            defenseIdleAnchor = currentPos;
            defenseIdleTimer.reset();
            return true;
        }
        return false;
    }

    private void resetShieldHoldState() {
        shieldHoldAnchor = null;
        shieldHoldTriggered = false;
        shieldHoldTimer.forceElapse();
    }

    private void respondToDefenseStall(AltoClef mod, String reason, Vec3d anchor, double duration, double displacement, Map<String, Object> extras) {
        stopShielding(mod);
        killAura.stopShielding(mod);
        double playerHealth = mod.getPlayer() != null ? mod.getPlayer().getHealth() : Double.NaN;
        double absorption = mod.getPlayer() != null ? mod.getPlayer().getAbsorptionAmount() : Double.NaN;
        Vec3d currentPos = mod.getPlayer() != null ? mod.getPlayer().getPos() : Vec3d.ZERO;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("duration_seconds", round(duration, 2));
        payload.put("displacement", round(displacement, 3));
        payload.put("state", defenseState.toString());
        payload.put("threat_count", latestThreats.size());
        payload.put("shielding", mod.getPlayer() != null && mod.getPlayer().isBlocking());
        payload.put("health", round(playerHealth, 2));
        payload.put("absorption", round(absorption, 2));
        payload.put("priority", round(cachedLastPriority, 2));
        payload.put("anchor_pos", anchor != null ? vectorMap(anchor) : null);
        payload.put("player_pos", vectorMap(currentPos));
        payload.put("dimension", mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>");
        payload.put("current_task", mainTask != null ? mainTask.getClass().getSimpleName() : "<none>");
        payload.put("run_task", runAwayTask != null ? runAwayTask.getClass().getSimpleName() : "<none>");
        payload.put("failsafe", failsafeTask != null ? failsafeTask.getReason().toString() : "<none>");
        payload.put("target", targetEntity != null ? targetEntity.getType().getTranslationKey() : "<none>");
        payload.put("locked", lockedOnEntity != null ? lockedOnEntity.getType().getTranslationKey() : "<none>");
        payload.put("threat_samples", latestThreats.stream().limit(4).map(this::summarizeThreat).collect(Collectors.toList()));
        if (extras != null) {
            payload.putAll(extras);
        }
        ItemStack offhand = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
        if (!offhand.isEmpty() && offhand.getItem() == Items.SHIELD) {
            payload.put("shield_durability", offhand.getMaxDamage() - offhand.getDamage());
        }
        if (mod.getStuckLogManager() != null) {
            mod.getStuckLogManager().recordEvent("MobDefenseStall", payload);
        }
        Debug.logMessage(String.format(Locale.ROOT,
                "[MobDefense] Stall detected reason=%s duration=%.1fs displacement=%.2f threats=%d",
                reason,
                duration,
                displacement,
                latestThreats.size()), false);
        forceAttackOrRetreat(mod);
    }

    private void forceAttackOrRetreat(AltoClef mod) {
        Optional<ThreatSnapshot> attackCandidate = latestThreats.stream()
                .filter(snapshot -> snapshot.immediate)
                .filter(snapshot -> canDirectlyEngage(mod, snapshot))
                .findFirst();
        CombatAssessment assessment = assessCombatReadiness(mod);
        defenseActiveTimer.reset();
        persistentThreatTimer.reset();
        staleTargetTimer.reset();
        if (attackCandidate.isPresent()) {
            ThreatSnapshot snapshot = attackCandidate.get();
            Entity target = snapshot.entity;
            if (shouldAvoidDirectFight(mod, snapshot, assessment)) {
                triggerEmergencyRetreat(mod, snapshot, assessment, "StallAvoid", 85f);
                return;
            }
            lockedOnEntity = target;
            needsChangeOnAttack = true;
            runAwayTask = null;
            doingFunkyStuff = false;
            setTask(new KillEntityTask(target));
            defenseState = DefenseState.ACTIVE;
            if (!snapshot.reachable) {
                Debug.logMessage(String.format(Locale.ROOT,
                        "[MobDefense] Forcing attack on close threat without path %s distance=%.1f",
                        target.getType().getTranslationKey(),
                        mod.getPlayer().distanceTo(target)), false);
            }
        } else {
            doingFunkyStuff = true;
            runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            defenseState = DefenseState.RETREAT;
            setTask(runAwayTask);
        }
    }

    private CombatAssessment assessCombatReadiness(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();
        if (player == null) {
            return new CombatAssessment(false, false, 0, 0, true);
        }
        boolean shield = hasShield(mod);
        boolean meleeWeapon = hasMeleeWeapon(mod);
        int armorValue = player.getArmor();
        float totalHealth = player.getHealth() + player.getAbsorptionAmount();
        boolean lowGear = (!shield && !meleeWeapon) || armorValue < 5;
        if (totalHealth <= CRITICAL_HEALTH_THRESHOLD && !shield) {
            lowGear = true;
        }
        return new CombatAssessment(shield, meleeWeapon, armorValue, totalHealth, lowGear);
    }

    private boolean hasMeleeWeapon(AltoClef mod) {
        if (getBestSword(mod) != null) {
            return true;
        }
        for (Item axe : AXE_WEAPONS) {
            if (mod.getItemStorage().hasItem(axe)) {
                return true;
            }
        }
        ItemStack mainHand = mod.getPlayer().getInventory().getMainHandStack();
        return mainHand.getItem() instanceof SwordItem
                || Arrays.stream(AXE_WEAPONS).anyMatch(item -> item == mainHand.getItem());
    }

    private boolean shouldAvoidDirectFight(AltoClef mod, ThreatSnapshot snapshot, CombatAssessment assessment) {
        if (!snapshot.entity.isAlive()) {
            return false;
        }
        if (!assessment.lowGear && assessment.hasShield && assessment.hasMeleeWeapon) {
            return false;
        }
        double distance = Math.sqrt(snapshot.distanceSq);
        long immediateCount = latestThreats.stream().filter(threat -> threat.immediate).count();
        boolean multipleThreats = immediateCount > 1;
        boolean lowHealth = assessment.totalHealth <= CRITICAL_HEALTH_THRESHOLD;
        boolean noWeapon = !assessment.hasMeleeWeapon;
        if (noWeapon && distance > 1.5) {
            return true;
        }
        if (assessment.lowGear && (snapshot.projectile || distance > CLOSE_FORCE_DISTANCE)) {
            return true;
        }
        if (multipleThreats && assessment.lowGear) {
            return true;
        }
        if (lowHealth && (snapshot.projectile || !assessment.hasShield)) {
            return true;
        }
        if (!snapshot.reachable && (assessment.lowGear || noWeapon)) {
            return true;
        }
        return false;
    }

    private Float triggerEmergencyRetreat(AltoClef mod, ThreatSnapshot snapshot, CombatAssessment assessment, String reason, float priority) {
        doingFunkyStuff = true;
        runAwayTask = null;
        stopShielding(mod);
        killAura.stopShielding(mod);
    boolean projectile = snapshot.projectile;
        boolean dodgeProjectiles = projectile && mod.getModSettings().isDodgeProjectiles();
        if (dodgeProjectiles && !assessment.hasShield) {
            if (StorageHelper.getNumberOfThrowawayBlocks(mod) > 0) {
                setTask(new ProjectileProtectionWallTask(mod));
            } else {
                runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                setTask(runAwayTask);
            }
        } else {
            runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            setTask(runAwayTask);
        }
        defenseState = DefenseState.RETREAT;
        staleTargetTimer.reset();
        Debug.logMessage(String.format(Locale.ROOT,
                "[MobDefense] Emergency retreat (%s) from %s distance=%.1f lowGear=%s shield=%s weapon=%s armor=%d health=%.1f",
                reason,
                snapshot.entity.getType().getTranslationKey(),
                mod.getPlayer().distanceTo(snapshot.entity),
                assessment.lowGear,
                assessment.hasShield,
                assessment.hasMeleeWeapon,
                assessment.armorValue,
                assessment.totalHealth), false);
        return priority;
    }

    private Float tryEngagePrimaryThreat(AltoClef mod) {
        if (defenseState == DefenseState.FAILSAFE || defenseState == DefenseState.RETREAT || panicRetreatActive) {
            return null;
        }
        if (mod.getPlayer() == null || latestThreats.isEmpty()) {
            return null;
        }

        Optional<ThreatSnapshot> primaryThreat = latestThreats.stream()
                .filter(snapshot -> snapshot.immediate)
                .filter(snapshot -> canDirectlyEngage(mod, snapshot))
                .findFirst();

        if (primaryThreat.isEmpty()) {
            return null;
        }

        ThreatSnapshot snapshot = primaryThreat.get();
        Entity threat = snapshot.entity;
        if (threat == null || !threat.isAlive()) {
            return null;
        }

        CombatAssessment assessment = assessCombatReadiness(mod);
        if (shouldAvoidDirectFight(mod, snapshot, assessment)) {
            return triggerEmergencyRetreat(mod, snapshot, assessment, "PrimaryAvoid", 82f);
        }

        boolean alreadyLocked = lockedOnEntity != null && lockedOnEntity.getUuid().equals(threat.getUuid());
        if (alreadyLocked && mainTask != null && !mainTask.isFinished()) {
            return null;
        }

        if (mainTask != null && !mainTask.isFinished()) {
            if (mainTask instanceof KillEntityTask killTask) {
                if (killTask.equals(new KillEntityTask(threat))) {
                    return null;
                }
            }
            if (mainTask instanceof KillEntitiesTask && alreadyLocked) {
                return null;
            }
        }

        stopShielding(mod);
        killAura.stopShielding(mod);
        lockedOnEntity = threat;
        needsChangeOnAttack = true;
        runAwayTask = null;
        doingFunkyStuff = false;
        defenseState = DefenseState.ACTIVE;
        staleTargetTimer.reset();
        setTask(new KillEntityTask(threat));
        if (!snapshot.reachable) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[MobDefense] Engaging close threat despite unreachable path %s distance=%.1f",
                    threat.getType().getTranslationKey(),
                    mod.getPlayer().distanceTo(threat)), false);
        }
        Debug.logMessage(String.format(Locale.ROOT,
                "[MobDefense] Engaging primary threat %s distance=%.1f",
                threat.getType().getTranslationKey(),
                mod.getPlayer().distanceTo(threat)), false);
        return 75f;
    }

    private Map<String, Object> summarizeThreat(ThreatSnapshot snapshot) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", snapshot.entity.getType().getTranslationKey());
        info.put("distance", round(Math.sqrt(snapshot.distanceSq), 2));
        info.put("reachable", snapshot.reachable);
        info.put("los", snapshot.hasLineOfSight);
        info.put("immediate", snapshot.immediate);
        info.put("projectile", snapshot.projectile);
        info.put("delta_y", round(snapshot.deltaY, 2));
        return info;
    }

    private static Map<String, Object> vectorMap(Vec3d vec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", round(vec.x, 3));
        map.put("y", round(vec.y, 3));
        map.put("z", round(vec.z, 3));
        return map;
    }

    private static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private void maybeTriggerFailsafe(AltoClef mod) {
        if (defenseState == DefenseState.IDLE || defenseState == DefenseState.FAILSAFE) {
            return;
        }
        if (failsafeTask != null) {
            return;
        }

        if (mod.getPlayer().isTouchingWater() && waterStuckTimer.elapsed()) {
            triggerFailsafe(mod, Reason.WATER, null);
            return;
        }

        if (staleTargetTimer.elapsed() && lastPrimaryThreat != null) {
            triggerFailsafe(mod, Reason.STALE_COMBAT, null);
            return;
        }

        if (!latestThreats.isEmpty() && persistentThreatTimer.elapsed()) {
            BlockPos spawner = findNearbySpawner(mod);
            if (spawner != null) {
                triggerFailsafe(mod, Reason.SPAWNER, spawner);
            } else {
                triggerFailsafe(mod, Reason.TIMEOUT, null);
            }
            return;
        }

        if (defenseActiveTimer.elapsed()) {
            triggerFailsafe(mod, Reason.TIMEOUT, null);
        }
    }

    private void triggerFailsafe(AltoClef mod, Reason reason, @Nullable BlockPos spawnerHint) {
        int targetY = mod.getPlayer().getBlockY() + FAILSAFE_PILLAR_EXTRA_HEIGHT;
        failsafeTask = new DefenseFailsafeTask(reason, spawnerHint, targetY);
        defenseState = DefenseState.FAILSAFE;
        defenseActiveTimer.reset();
        staleTargetTimer.reset();
        persistentThreatTimer.reset();
        waterStuckTimer.reset();
        Debug.logMessage(String.format(Locale.ROOT,
                "[MobDefense] Failsafe triggered reason=%s targetY=%d spawner=%s",
                reason,
                targetY,
                spawnerHint == null ? "<none>" : spawnerHint.toShortString()), false);
    }

    private void emitDiagnostics(AltoClef mod) {
        if (defenseState == DefenseState.IDLE && latestThreats.isEmpty()) {
            return;
        }
    AltoClef instance = AltoClef.inGame() ? AltoClef.getInstance() : null;
    String dimension = instance != null && instance.getWorld() != null
        ? instance.getWorld().getRegistryKey().getValue().toString()
        : "<unknown>";
    Vec3d playerPos = instance != null && instance.getPlayer() != null ? instance.getPlayer().getPos() : Vec3d.ZERO;
    BlockPos playerBlock = instance != null && instance.getPlayer() != null ? instance.getPlayer().getBlockPos() : null;
    String runTaskName = runAwayTask != null ? runAwayTask.getClass().getSimpleName() : "<none>";
    String failsafeInfo = failsafeTask != null ? failsafeTask.getReason() + ":" + failsafeTask.getStageName() : "<none>";
    String targetInfo = targetEntity != null ? targetEntity.getType().getTranslationKey() : "<none>";
    String lockedInfo = lockedOnEntity != null ? lockedOnEntity.getType().getTranslationKey() : "<none>";

    StringBuilder builder = new StringBuilder("[MobDefense] state=")
        .append(defenseState)
        .append(" dim=")
        .append(dimension)
        .append(" threats=")
        .append(latestThreats.size())
        .append(" priority=")
        .append(String.format(Locale.ROOT, "%.1f", cachedLastPriority))
        .append(" damageTimer=")
        .append(String.format(Locale.ROOT, "%.1f", noDamageTimer.getDuration()))
        .append(" activeTimer=")
        .append(String.format(Locale.ROOT, "%.1f", defenseActiveTimer.getDuration()))
        .append(" persistentTimer=")
        .append(String.format(Locale.ROOT, "%.1f", persistentThreatTimer.getDuration()))
        .append(" waterTimer=")
        .append(String.format(Locale.ROOT, "%.1f", waterStuckTimer.getDuration()))
        .append(" hold=")
        .append(String.format(Locale.ROOT, "%.1f", immediateThreatHoldTimer.getDuration()))
        .append(" panic=")
        .append(panicRetreatActive)
        .append(" run=")
        .append(runTaskName)
        .append(" failsafe=")
        .append(failsafeInfo)
        .append(" target=")
        .append(targetInfo)
        .append(" locked=")
        .append(lockedInfo);

    if (playerBlock != null) {
        builder.append(" pos=")
            .append(playerBlock.toShortString())
            .append(String.format(Locale.ROOT, " xyz=(%.1f, %.1f, %.1f)", playerPos.x, playerPos.y, playerPos.z));
    }

    latestThreats.stream().limit(3).forEach(snapshot -> builder
                .append(" | ")
                .append(snapshot.entity.getType().getTranslationKey())
                .append(" d=")
                .append(String.format(Locale.ROOT, "%.1f", Math.sqrt(snapshot.distanceSq)))
                .append(snapshot.reachable ? " R" : " !R")
        .append(snapshot.hasLineOfSight ? " LoS" : " !LoS")
        .append(snapshot.immediate ? " IMM" : "")
        .append(snapshot.pathBudgetHit ? " BUDGET" : "")
        .append(String.format(Locale.ROOT, " Δy=%.1f", snapshot.deltaY)));

        Debug.logMessage(builder.toString(), false);
    }

    private BlockPos findNearbySpawner(AltoClef mod) {
        if (latestThreats.isEmpty()) {
            return null;
        }
        Optional<BlockPos> spawner = mod.getBlockScanner().getNearestBlock(pos -> {
            for (ThreatSnapshot threat : latestThreats) {
                if (pos.isWithinDistance(threat.position, 6)) {
                    return true;
                }
            }
            return false;
        }, Blocks.SPAWNER);
        return spawner.orElse(null);
    }

    private void maybeLogIgnored(AltoClef mod, LivingEntity hostile, String reason) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            return;
        }
        long now = mod.getWorld().getTime();
        Long last = ignoredLogTimestamps.get(hostile.getUuid());
        if (last != null && now - last < IGNORED_LOG_COOLDOWN_TICKS) {
            return;
        }
        ThreatSnapshot snapshot = latestThreats.stream()
                .filter(s -> s.entity.getUuid().equals(hostile.getUuid()))
                .findFirst()
                .orElse(null);
        boolean immediate = snapshot != null && snapshot.immediate;
        boolean reachable = snapshot != null && snapshot.reachable;
        boolean los = snapshot != null && snapshot.hasLineOfSight;
        double distance = Math.sqrt(hostile.squaredDistanceTo(mod.getPlayer()));
        Debug.logMessage(String.format(Locale.ROOT,
                "[MobDefense] Ignoring %s reason=%s dist=%.1f immediate=%s reachable=%s los=%s",
                hostile.getType().getTranslationKey(),
                reason,
                distance,
                immediate,
                reachable,
                los), false);
        ignoredLogTimestamps.put(hostile.getUuid(), now);
    }

    private boolean isProjectileMob(LivingEntity entity) {
        return entity instanceof SkeletonEntity || entity instanceof StrayEntity || entity instanceof PillagerEntity
                || entity instanceof WitchEntity || entity instanceof BlazeEntity || entity instanceof GhastEntity
                || entity instanceof DrownedEntity || entity instanceof ShulkerEntity;
    }

    private boolean isImmediateThreat(LivingEntity entity, double distanceSq, double deltaY, boolean projectile,
                                       boolean hasLineOfSight, boolean reachable, boolean acrossWater) {
        if (!entity.isAlive()) return false;
        double distance = Math.sqrt(distanceSq);
        if (distance <= CLOSE_FORCE_DISTANCE) {
            return true;
        }
        if (entity instanceof CreeperEntity creeper && creeper.getClientFuseTime(1) > 0.1f) {
            return true;
        }
        if (acrossWater && !reachable) {
            return false;
        }
        boolean verticalOkay = Math.abs(deltaY) <= (projectile ? RANGED_VERTICAL_THRESHOLD : MELEE_VERTICAL_THRESHOLD);
        if (!verticalOkay) {
            return false;
        }
        if (projectile) {
            return hasLineOfSight && distanceSq <= DANGER_KEEP_DISTANCE * DANGER_KEEP_DISTANCE;
        }
        return reachable && distanceSq <= SAFE_KEEP_DISTANCE * SAFE_KEEP_DISTANCE;
    }

    private boolean shouldPanicRetreat(AltoClef mod) {
        float healthTotal = mod.getPlayer().getHealth() + mod.getPlayer().getAbsorptionAmount();
        if (healthTotal > CRITICAL_HEALTH_THRESHOLD) {
            return false;
        }
        if (latestThreats.isEmpty()) {
            return false;
        }
        boolean closeThreat = latestThreats.stream()
                .anyMatch(snapshot -> snapshot.immediate || snapshot.distanceSq < PANIC_CLOSE_DISTANCE * PANIC_CLOSE_DISTANCE);
        if (!closeThreat) {
            return false;
        }
        return !mod.getFoodChain().needsToEat();
    }

    private boolean isAcrossWater(AltoClef mod, LivingEntity hostile) {
        boolean playerWater = mod.getPlayer().isTouchingWater();
        boolean hostileWater = hostile.isTouchingWater();
        return playerWater != hostileWater;
    }

    private ReachabilityCacheEntry getReachabilityEntry(AltoClef mod, LivingEntity entity) {
        long currentTick = mod.getPlayer().age;
        ReachabilityCacheEntry cached = reachabilityCache.get(entity.getUuid());
        BlockPos targetPos = entity.getBlockPos();
        if (cached == null || !cached.targetPos.equals(targetPos) || currentTick - cached.tickUpdated > REACHABILITY_CACHE_TTL_TICKS) {
            boolean withinRange = entity.squaredDistanceTo(mod.getPlayer()) <= REACHABILITY_DISTANCE_MAX * REACHABILITY_DISTANCE_MAX;
            boolean reachable = withinRange && WorldHelper.canReach(targetPos);
            cached = new ReachabilityCacheEntry(targetPos, reachable, false, currentTick);
            reachabilityCache.put(entity.getUuid(), cached);
        }
        return cached;
    }

    private boolean canDirectlyEngage(AltoClef mod, ThreatSnapshot snapshot) {
        if (snapshot.reachable) {
            return true;
        }
        if (snapshot.distanceSq <= CLOSE_FORCE_DISTANCE * CLOSE_FORCE_DISTANCE) {
            return true;
        }
        if (!snapshot.projectile && snapshot.hasLineOfSight && snapshot.distanceSq <= SAFE_KEEP_DISTANCE * SAFE_KEEP_DISTANCE) {
            return true;
        }
        if (mod.getPlayer() != null && snapshot.entity.getBoundingBox().expand(0.6).intersects(mod.getPlayer().getBoundingBox())) {
            return true;
        }
        return false;
    }

    private static boolean hasShield(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
    }

    private static SwordItem getBestSword(AltoClef mod) {
        Item[] SWORDS = new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
                Items.STONE_SWORD, Items.WOODEN_SWORD};

        SwordItem bestSword = null;
        for (Item item : SWORDS) {
            if (mod.getItemStorage().hasItem(item)) {
                bestSword = (SwordItem) item;
                break;
            }
        }
        return bestSword;
    }

    private BlockPos isInsideFireAndOnFire(AltoClef mod) {
        boolean onFire = mod.getPlayer().isOnFire();
        if (!onFire) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        BlockPos[] toCheck = new BlockPos[]{
                p,
                p.add(1,0,0),
                p.add(1,0,-1),
                p.add(0,0,-1),
                p.add(-1,0,-1),
                p.add(-1,0,0),
                p.add(-1,0,1),
                p.add(0,0,1),
                p.add(1,0,1)
        };
        for (BlockPos check : toCheck) {
            Block b = mod.getWorld().getBlockState(check).getBlock();
            if (b instanceof AbstractFireBlock) {
                return check;
            }
        }
        return null;
    }

    private void putOutFire(AltoClef mod, BlockPos pos) {
        Optional<Rotation> reach = LookHelper.getReach(pos);
        if (reach.isPresent()) {
            Baritone b = mod.getClientBaritone();
            if (LookHelper.isLookingAt(mod, pos)) {
                b.getPathingBehavior().requestPause();
                b.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                return;
            }
            LookHelper.lookAt(reach.get());
        }
    }

    private void doForceField(AltoClef mod) {
        killAura.tickStart();

        // Hit all hostiles close to us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        try {
            for (Entity entity : entities) {
                boolean shouldForce = false;
                if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                if (entity instanceof MobEntity) {
                    if (EntityHelper.isProbablyHostileToPlayer(mod, entity)) {
                        if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10)) {
                            shouldForce = true;
                        }
                    }
                } else if (entity instanceof FireballEntity) {
                    // Ghast ball
                    shouldForce = true;
                }

                if (shouldForce) {
                    killAura.applyAura(entity);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        killAura.tickEnd(mod);
    }


    private CreeperEntity getClosestFusingCreeper(AltoClef mod) {
        double worstSafety = Float.POSITIVE_INFINITY;
        CreeperEntity target = null;
        try {
            List<CreeperEntity> creepers = mod.getEntityTracker().getTrackedEntities(CreeperEntity.class);
            for (CreeperEntity creeper : creepers) {
                if (creeper == null) continue;
                if (creeper.getClientFuseTime(1) < 0.001) continue;

                // We want to pick the closest creeper, but FIRST pick creepers about to blow
                // At max fuse, the cost goes to basically zero.
                double safety = getCreeperSafety(mod.getPlayer().getPos(), creeper);
                if (safety < worstSafety) {
                    target = creeper;
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // IDK why but these exceptions happen sometimes. It's extremely bizarre and I
            // have no idea why.
            Debug.logWarning("Weird Exception caught and ignored while scanning for creepers: " + e.getMessage());
            return target;
        }
        return target;
    }

    private boolean isProjectileClose(AltoClef mod) {
        List<CachedProjectile> projectiles = mod.getEntityTracker().getProjectiles();
        try {
            for (CachedProjectile projectile : projectiles) {
                if (projectile.position.squaredDistanceTo(mod.getPlayer().getPos()) < 150) {
                    boolean isGhastBall = projectile.projectileType == FireballEntity.class;
                    if (isGhastBall) {
                        Optional<Entity> ghastBall = mod.getEntityTracker().getClosestEntity(FireballEntity.class);
                        Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(GhastEntity.class);
                        if (ghastBall.isPresent() && ghast.isPresent() && runAwayTask == null
                                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                            mod.getClientBaritone().getPathingBehavior().requestPause();
                            LookHelper.lookAt(mod, ghast.get().getEyePos());
                        }
                        return false;
                        // Ignore ghast balls
                    }
                    if (projectile.projectileType == DragonFireballEntity.class) {
                        // Ignore dragon fireballs
                        continue;
                    }
                    if (projectile.projectileType == ArrowEntity.class || projectile.projectileType == SpectralArrowEntity.class || projectile.projectileType == SmallFireballEntity.class) {
                        // check if the projectile is going away from us
                        // not so fancy math... this should work better than the previous approach (I hope just adding the velocity doesn't cause any issues..)
                        PlayerEntity player = mod.getPlayer();
                        if (player.squaredDistanceTo(projectile.position) < player.squaredDistanceTo(projectile.position.add(projectile.velocity))) {
                            continue;
                        }
                    }

                    Vec3d expectedHit = ProjectileHelper.calculateArrowClosestApproach(projectile, mod.getPlayer());

                    Vec3d delta = mod.getPlayer().getPos().subtract(expectedHit);

                    double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                    double verticalDistance = Math.abs(delta.y);
                    if (horizontalDistanceSq < ARROW_KEEP_DISTANCE_HORIZONTAL * ARROW_KEEP_DISTANCE_HORIZONTAL
                            && verticalDistance < ARROW_KEEP_DISTANCE_VERTICAL) {
                        if (mod.getClientBaritone().getPathingBehavior().isSafeToCancel()
                                && hasShield(mod)) {
                            mod.getClientBaritone().getPathingBehavior().requestPause();
                            LookHelper.lookAt(mod, projectile.position.add(0, 0.3, 0));
                        }
                        return true;
                    }
                }
            }

        } catch (ConcurrentModificationException e) {
            Debug.logWarning(e.getMessage());
        }

        // TODO refactor this into something more reliable for all mobs
        for (SkeletonEntity skeleton : mod.getEntityTracker().getTrackedEntities(SkeletonEntity.class)) {
            if (skeleton.distanceTo(mod.getPlayer()) > 10 || !skeleton.canSee(mod.getPlayer())) continue;

            // when the skeleton is about to shoot (it takes 5 ticks to raise the shield)
            if (skeleton.getItemUseTime() > 15) {
                return true;
            }
        }

        return false;
    }

    private Optional<Entity> getUniversallyDangerousMob(AltoClef mod) {
        // Wither skeletons are dangerous because of the wither effect. Oof kinda obvious.
        // If we merely force field them, we will run into them and get the wither effect which will kill us.

        Class<?>[] dangerousMobs = new Class[]{Entities.WARDEN, WitherEntity.class, WitherSkeletonEntity.class,
                HoglinEntity.class, ZoglinEntity.class, PiglinBruteEntity.class, VindicatorEntity.class};

        double range = SAFE_KEEP_DISTANCE - 2;

        for (Class<?> dangerous : dangerousMobs) {
            Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(dangerous);

            if (entity.isPresent()) {
                if (entity.get().squaredDistanceTo(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, entity.get())) {
                    return entity;
                }
            }
        }

        return Optional.empty();
    }

    private boolean isInDanger(AltoClef mod) {
        boolean witchNearby = mod.getEntityTracker().entityFound(WitchEntity.class);

        float health = mod.getPlayer().getHealth();
        if (health <= 10 && !witchNearby) {
            return true;
        }
        if (mod.getPlayer().hasStatusEffect(StatusEffects.WITHER) ||
                (mod.getPlayer().hasStatusEffect(StatusEffects.POISON) && !witchNearby)) {
            return true;
        }
        if (WorldHelper.isVulnerable()) {
            // If hostile mobs are nearby...
            try {
                ClientPlayerEntity player = mod.getPlayer();
                List<LivingEntity> hostiles = mod.getEntityTracker().getHostiles();

                synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                    for (Entity entity : hostiles) {
                        if (entity.isInRange(player, SAFE_KEEP_DISTANCE)
                                && !mod.getBehaviour().shouldExcludeFromForcefield(entity)
                                && EntityHelper.isAngryAtPlayer(mod, entity)) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Weird multithread exception. Will fix later. " + e.getMessage());
            }
        }
        return false;
    }

    public void setTargetEntity(Entity entity) {
        targetEntity = entity;
    }

    public void resetTargetEntity() {
        targetEntity = null;
    }

    public void setForceFieldRange(double range) {
        killAura.setRange(range);
    }

    public void resetForceField() {
        killAura.setRange(Double.POSITIVE_INFINITY);
    }

    public boolean isDoingAcrobatics() {
        return doingFunkyStuff;
    }

    public boolean isPuttingOutFire() {
        return wasPuttingOutFire;
    }

    @Override
    public boolean isActive() {
        // We're always checking for mobs
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Task is done, so I guess we move on?
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }

    @Override
    public String getDebugContext() {
        Task current = getCurrentTask();
        String taskInfo = current == null ? "<none>" : current.toString();
        String targetInfo = targetEntity != null ? targetEntity.getType().getTranslationKey() : "<none>";
        String lockedInfo = lockedOnEntity != null ? lockedOnEntity.getType().getTranslationKey() : "<none>";
        String failsafeInfo = failsafeTask != null ? failsafeTask.getReason() + "@" + failsafeTask.getStageName() : "<none>";
        boolean immediateThreat = latestThreats.stream().anyMatch(snapshot -> snapshot.immediate);
        boolean runAwayActive = runAwayTask != null && !runAwayTask.isFinished();
        return String.format(Locale.ROOT,
                "state=%s, priority=%.1f, task=%s, threats=%d, immediate=%s, failsafe=%s, runAway=%s, target=%s, locked=%s, tookDamage=%s, noDamage=%.1fs, water=%.1fs, persistent=%.1fs",
                defenseState,
                cachedLastPriority,
                taskInfo,
                latestThreats.size(),
                immediateThreat,
                failsafeInfo,
                runAwayActive,
                targetInfo,
                lockedInfo,
                playerTookDamageThisTick,
                noDamageTimer.getDuration(),
                waterStuckTimer.getDuration(),
                persistentThreatTimer.getDuration());
    }
}