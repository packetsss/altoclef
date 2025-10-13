package adris.altoclef.telemetry;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Collects rich diagnostic snapshots whenever the player dies and emits them as JSON lines.
 */
public final class DeathLogManager {

    private static final int MAX_HOSTILES = 12;
    private static final int MAX_DROPS = 16;
    private static final int MAX_STATUS_EFFECTS = 24;
    private static final int MAX_TASKS_PER_CHAIN = 16;

    private final AltoClef mod;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sessionSequence = new AtomicInteger(0);
    private final String sessionId;
    private final Path sessionDir;

    public DeathLogManager(AltoClef mod) {
        this.mod = mod;
        this.sessionId = mod.getTelemetrySessionId();
        this.sessionDir = mod.getTelemetrySessionDir();
    }

    public void recordDeath(int deathNumber, String deathMessage) {
        if (mod == null) {
            return;
        }

        if (!isDeathLoggingEnabled()) {
            return;
        }

        Path activeSessionDir = getActiveSessionDir();
        if (activeSessionDir == null) {
            Debug.logWarning("Failed to capture death log: telemetry session directory unavailable.");
            return;
        }

        Instant timestamp = Instant.now();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp_utc", timestamp.toString());
        root.put("timestamp_epoch_ms", System.currentTimeMillis());
        root.put("session_id", sessionId);
        int deathIndex = sessionSequence.incrementAndGet();
        root.put("session_death_index", deathIndex);
        root.put("death_counter", deathNumber);
        root.put("death_message", deathMessage);
        root.put("game_tick", WorldHelper.getTicks());
        root.put("paused", mod.isPaused());

        ClientPlayerEntity player = mod.getPlayer();
        ClientWorld world = mod.getWorld();

        root.put("player", collectPlayerContext(player, world));
        root.put("world", collectWorldContext(player, world));
        root.put("inventory", collectInventoryContext(player));
        root.put("tasks", collectTaskContext());
        root.put("threats", collectThreatContext(player));
        root.put("drops_nearby", collectNearbyDrops(player));

        try {
            Path target = resolveDeathFile(activeSessionDir, deathIndex, deathMessage);
            writeSnapshot(root, activeSessionDir, target, deathIndex);
        } catch (IOException ex) {
            Debug.logWarning("Failed to write death log entry: " + ex.getMessage());
        }
    }

    private Map<String, Object> collectPlayerContext(ClientPlayerEntity player, ClientWorld world) {
        Map<String, Object> playerMap = new LinkedHashMap<>();
        if (player == null) {
            playerMap.put("present", false);
            return playerMap;
        }
        playerMap.put("present", true);
        playerMap.put("uuid", player.getUuidAsString());
        playerMap.put("name", player.getName().getString());
        playerMap.put("dimension", world != null ? world.getRegistryKey().getValue().toString() : "<unknown>");
        playerMap.put("alto_dimension", WorldHelper.getCurrentDimension().name());

        Vec3d pos = player.getPos();
        BlockPos blockPos = player.getBlockPos();
        playerMap.put("pos", vectorMap(pos));
        playerMap.put("block_pos", blockPosMap(blockPos));
        playerMap.put("velocity", vectorMap(player.getVelocity()));
        playerMap.put("rotation", Map.of(
                "yaw", round(player.getYaw(), 3),
                "pitch", round(player.getPitch(), 3)
        ));

        playerMap.put("health", round(player.getHealth(), 2));
        playerMap.put("max_health", round(player.getMaxHealth(), 2));
        playerMap.put("absorption", round(player.getAbsorptionAmount(), 2));
        playerMap.put("hunger", player.getHungerManager().getFoodLevel());
        playerMap.put("saturation", round(player.getHungerManager().getSaturationLevel(), 2));
        playerMap.put("exhaustion", round(player.getHungerManager().getExhaustion(), 3));
        playerMap.put("armor", player.getArmor());
        playerMap.put("experience_level", player.experienceLevel);
        playerMap.put("experience_progress", round(player.experienceProgress, 3));
        playerMap.put("total_experience", player.totalExperience);
        playerMap.put("air", player.getAir());
        playerMap.put("max_air", player.getMaxAir());
        playerMap.put("fire_ticks", player.getFireTicks());
        playerMap.put("fall_distance", round(player.fallDistance, 3));
        playerMap.put("in_lava", player.isInLava());
        playerMap.put("in_water", player.isTouchingWater());
        playerMap.put("on_ground", player.isOnGround());
        playerMap.put("sneaking", player.isSneaking());
        playerMap.put("sprinting", player.isSprinting());
        playerMap.put("gliding", player.isFallFlying());
        playerMap.put("using_item", player.isUsingItem());
        playerMap.put("main_hand", describeStack(player.getMainHandStack()));
        playerMap.put("off_hand", describeStack(player.getOffHandStack()));

        if (world != null) {
            RegistryEntry<Biome> biomeEntry = world.getBiome(blockPos);
            playerMap.put("biome", biomeEntry.getKey().map(key -> key.getValue().toString()).orElse("<unknown>"));
        }

        List<Map<String, Object>> statusList = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            if (statusList.size() >= MAX_STATUS_EFFECTS) break;
            Map<String, Object> entry = new LinkedHashMap<>();
            RegistryEntry<?> typeEntry = effect.getEffectType();
            entry.put("id", typeEntry.getKey().map(key -> key.getValue().toString()).orElse(effect.getTranslationKey()));
            entry.put("name", Text.translatable(effect.getTranslationKey()).getString());
            entry.put("amplifier", effect.getAmplifier());
            entry.put("duration", effect.getDuration());
            entry.put("ambient", effect.isAmbient());
            entry.put("show_particles", effect.shouldShowParticles());
            entry.put("show_icon", effect.shouldShowIcon());
            statusList.add(entry);
        }
        playerMap.put("status_effects", statusList);

        DamageSource source = player.getRecentDamageSource();
        if (source != null) {
            Map<String, Object> damageMap = new LinkedHashMap<>();
            damageMap.put("message", source.getDeathMessage(player).getString());
            damageMap.put("type", source.getTypeRegistryEntry().getKey().map(key -> key.getValue().toString()).orElse(source.toString()));
            if (source.getAttacker() != null) {
                damageMap.put("attacker", describeEntity(source.getAttacker()));
            }
            playerMap.put("recent_damage", damageMap);
        }

        Map<String, Object> surroundings = new LinkedHashMap<>();
        surroundings.put("block_feet", blockStateId(world != null ? world.getBlockState(blockPos) : null));
        surroundings.put("block_below", blockStateId(world != null ? world.getBlockState(blockPos.down()) : null));
        surroundings.put("block_head", blockStateId(world != null ? world.getBlockState(blockPos.up()) : null));
        surroundings.put("light", Map.of(
                "sky", world != null ? world.getLightLevel(LightType.SKY, blockPos) : -1,
                "block", world != null ? world.getLightLevel(LightType.BLOCK, blockPos) : -1
        ));
        playerMap.put("blocks", surroundings);

        Map<String, Object> equipment = new LinkedHashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.put(slot.getName(), describeStack(player.getEquippedStack(slot)));
        }
        playerMap.put("equipment", equipment);

        return playerMap;
    }

    private Map<String, Object> collectWorldContext(ClientPlayerEntity player, ClientWorld world) {
        Map<String, Object> worldMap = new LinkedHashMap<>();
        if (world == null) {
            worldMap.put("present", false);
            return worldMap;
        }
        worldMap.put("present", true);
        worldMap.put("dimension", world.getRegistryKey().getValue().toString());
        worldMap.put("alto_dimension", WorldHelper.getCurrentDimension().name());
        worldMap.put("time", world.getTime());
        worldMap.put("time_of_day", world.getTimeOfDay());
        worldMap.put("difficulty", world.getDifficulty().getName());
        worldMap.put("is_raining", world.isRaining());
        worldMap.put("is_thundering", world.isThundering());
        worldMap.put("weather_gradient", world.getRainGradient(1.0f));
        worldMap.put("thunder_gradient", world.getThunderGradient(1.0f));
        worldMap.put("moon_phase", world.getMoonPhase());
        worldMap.put("sea_level", world.getSeaLevel());
        worldMap.put("bottom_y", world.getBottomY());
        worldMap.put("top_y", world.getTopY());

        BlockPos anchor = player != null ? player.getBlockPos() : BlockPos.ORIGIN;
        ChunkPos chunk = new ChunkPos(anchor);
        worldMap.put("chunk_pos", Map.of("x", chunk.x, "z", chunk.z));
        worldMap.put("region_pos", Map.of("x", chunk.x >> 5, "z", chunk.z >> 5));

        return worldMap;
    }

    private Map<String, Object> collectInventoryContext(ClientPlayerEntity player) {
        Map<String, Object> inventory = new LinkedHashMap<>();
        if (player == null) {
            inventory.put("present", false);
            return inventory;
        }
        inventory.put("present", true);
        PlayerInventory inv = player.getInventory();
        inventory.put("selected_slot", inv.selectedSlot);

        List<Map<String, Object>> main = new ArrayList<>();
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack stack = inv.main.get(i);
            if (stack.isEmpty()) continue;
            main.add(describeStackWithSlot(stack, i));
        }
        inventory.put("main", main);

        List<Map<String, Object>> armor = new ArrayList<>();
        for (int i = 0; i < inv.armor.size(); i++) {
            ItemStack stack = inv.armor.get(i);
            if (stack.isEmpty()) continue;
            armor.add(describeStackWithSlot(stack, 100 + i));
        }
        inventory.put("armor", armor);

        List<Map<String, Object>> offhand = new ArrayList<>();
        for (int i = 0; i < inv.offHand.size(); i++) {
            ItemStack stack = inv.offHand.get(i);
            if (stack.isEmpty()) continue;
            offhand.add(describeStackWithSlot(stack, 200 + i));
        }
        inventory.put("offhand", offhand);

        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack stack : inv.main) {
            if (stack.isEmpty()) continue;
            totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
        for (ItemStack stack : inv.armor) {
            if (stack.isEmpty()) continue;
            totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
        for (ItemStack stack : inv.offHand) {
            if (stack.isEmpty()) continue;
            totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
        inventory.put("item_totals", totals);
        inventory.put("throwaway_block_count", StorageHelper.getNumberOfThrowawayBlocks(mod));

        return inventory;
    }

    private Map<String, Object> collectTaskContext() {
        Map<String, Object> tasks = new LinkedHashMap<>();
        TaskRunner runner = mod.getTaskRunner();
        if (runner == null) {
            tasks.put("runner_present", false);
            return tasks;
        }
        tasks.put("runner_present", true);
        tasks.put("runner_active", runner.isActive());
        tasks.put("runner_status", runner.statusReport.trim());

        TaskChain current = runner.getCurrentTaskChain();
        if (current != null) {
            tasks.put("current_chain", Map.of(
                    "name", current.getName(),
                    "active", current.isActive(),
                    "debug", current.getDebugContext(),
                    "tasks", current.getTasks().stream().limit(MAX_TASKS_PER_CHAIN).map(Task::toString).collect(Collectors.toList())
            ));
        }

        if (mod.getUserTaskChain() != null) {
            Task userTask = mod.getUserTaskChain().getCurrentTask();
            if (userTask != null) {
                tasks.put("current_user_task", Map.of(
                        "class", userTask.getClass().getName(),
                        "summary", userTask.toString(),
                        "finished", userTask.isFinished(),
                        "stopped", userTask.stopped()
                ));
            }
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (TaskRunner.ChainDiagnostics diag : runner.getChainDiagnostics()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", diag.name());
            entry.put("active", diag.active());
            entry.put("is_current", diag.current());
            entry.put("debug_context", diag.debugContext());
            entry.put("task_count", diag.tasks().size());
            entry.put("tasks", diag.tasks().stream().limit(MAX_TASKS_PER_CHAIN).map(TaskRunner.TaskDiagnostics::asMap).collect(Collectors.toList()));
            chains.add(entry);
        }
        tasks.put("chains", chains);
        tasks.put("stored_task", mod.getStoredTask() != null ? mod.getStoredTask().toString() : null);
        tasks.put("recent_transitions", runner.getRecentTaskTransitions());
        tasks.put("recent_completed_tasks", runner.getRecentCompletedTasks());

        return tasks;
    }

    private List<Map<String, Object>> collectThreatContext(ClientPlayerEntity player) {
        List<Map<String, Object>> threats = new ArrayList<>();
        EntityTracker tracker = mod.getEntityTracker();
        if (player == null || tracker == null) {
            return threats;
        }
        Vec3d playerPos = player.getPos();
        List<LivingEntity> hostiles = new ArrayList<>(tracker.getHostiles());
        hostiles.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(playerPos)));
        for (LivingEntity hostile : hostiles) {
            if (threats.size() >= MAX_HOSTILES) break;
            Map<String, Object> entry = describeEntity(hostile);
            entry.put("distance_sq", round(hostile.squaredDistanceTo(playerPos), 3));
            entry.put("distance", round(Math.sqrt(hostile.squaredDistanceTo(playerPos)), 3));
            entry.put("health", round(hostile.getHealth(), 2));
            entry.put("max_health", round(hostile.getMaxHealth(), 2));
            entry.put("on_fire", hostile.isOnFire());
            entry.put("is_burning", hostile.isOnFire());
            if (hostile instanceof MobEntity mob) {
                entry.put("targeting_player", mob.getTarget() != null && mob.getTarget().equals(player));
                entry.put("aggressive", mob.isAttacking());
            }
            threats.add(entry);
        }
        return threats;
    }

    private List<Map<String, Object>> collectNearbyDrops(ClientPlayerEntity player) {
        List<Map<String, Object>> drops = new ArrayList<>();
        EntityTracker tracker = mod.getEntityTracker();
        if (player == null || tracker == null) {
            return drops;
        }
        Vec3d playerPos = player.getPos();
        List<ItemEntity> allDrops = tracker.getDroppedItems();
        allDrops.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(playerPos)));
        for (ItemEntity drop : allDrops) {
            if (drops.size() >= MAX_DROPS) break;
            double distSq = drop.squaredDistanceTo(playerPos);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item", itemId(drop.getStack().getItem()));
            entry.put("display_name", drop.getStack().getName().getString());
            entry.put("count", drop.getStack().getCount());
            entry.put("distance_sq", round(distSq, 3));
            entry.put("distance", round(Math.sqrt(distSq), 3));
            entry.put("pos", vectorMap(drop.getPos()));
            drops.add(entry);
        }
        return drops;
    }

    private void writeSnapshot(Map<String, Object> snapshot, Path sessionDirectory, Path targetFile, int index) throws IOException {
        Files.createDirectories(sessionDirectory);
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json;
        try {
            json = mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            Debug.logWarning("Failed to serialize death log snapshot: " + ex.getMessage());
            return;
        }
        Files.writeString(targetFile, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Debug.logMessage(String.format(Locale.ROOT,
                "[DeathLog] Captured detailed death entry #%d -> %s",
                index,
                targetFile.toString()), false);
    }

    private Path resolveDeathFile(Path sessionDirectory, int index, String deathMessage) {
        String slug = deathMessage == null ? "unknown" : deathMessage.replaceAll("[^a-zA-Z0-9-_]+", "_").replaceAll("_+", "_").trim();
        slug = slug.replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "death";
        }
        String fileName = String.format(Locale.ROOT, "death-%04d-%s.json", index, slug);
        return sessionDirectory.resolve(fileName);
    }

    private boolean isDeathLoggingEnabled() {
        if (mod == null) {
            return false;
        }
        adris.altoclef.Settings modSettings = mod.getModSettings();
        if (modSettings == null) {
            return true;
        }
        return modSettings.isDeathLogEnabled();
    }

    private Path getActiveSessionDir() {
        if (sessionDir != null) {
            return sessionDir;
        }
        return mod != null ? mod.getTelemetrySessionDir() : null;
    }

    private static Map<String, Object> vectorMap(Vec3d vec) {
        if (vec == null) {
            return Map.of("x", 0.0, "y", 0.0, "z", 0.0);
        }
        return Map.of(
                "x", round(vec.x, 3),
                "y", round(vec.y, 3),
                "z", round(vec.z, 3)
        );
    }

    private static Map<String, Object> blockPosMap(BlockPos pos) {
        if (pos == null) {
            return Map.of("x", 0, "y", 0, "z", 0);
        }
        return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    private static Map<String, Object> describeStackWithSlot(ItemStack stack, int slot) {
        Map<String, Object> data = describeStack(stack);
        data.put("slot", slot);
        return data;
    }

    private static Map<String, Object> describeStack(ItemStack stack) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) {
            data.put("empty", true);
            return data;
        }
        data.put("empty", false);
        data.put("item", itemId(stack.getItem()));
        data.put("display_name", stack.getName().getString());
        data.put("count", stack.getCount());
        data.put("damage", stack.getDamage());
        data.put("max_damage", stack.getMaxDamage());
        data.put("is_damaged", stack.isDamaged());
        return data;
    }

    private static String blockStateId(BlockState state) {
        if (state == null) {
            return "<none>";
        }
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id != null ? id.toString() : state.getBlock().getTranslationKey();
    }

    private static Map<String, Object> describeEntity(net.minecraft.entity.Entity entity) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (entity == null) {
            data.put("present", false);
            return data;
        }
        data.put("present", true);
        Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
        data.put("type", typeId != null ? typeId.toString() : entity.getType().toString());
        data.put("uuid", entity.getUuidAsString());
        data.put("name", entity.getName().getString());
        data.put("pos", vectorMap(entity.getPos()));
        data.put("velocity", vectorMap(entity.getVelocity()));
        data.put("alive", entity.isAlive());
        data.put("on_fire", entity.isOnFire());
        data.put("submerged_in_water", entity.isSubmergedInWater());
        data.put("in_lava", entity.isInLava());
        return data;
    }

    private static String itemId(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id != null ? id.toString() : item.toString();
    }

    private static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    public record ChainDiagnostics(String name, boolean active, boolean current, String debugContext,
                                   List<TaskRunner.TaskDiagnostics> tasks) {
    }

    public record TaskDiagnostics(String className, String summary, boolean finished, boolean active, boolean stopped) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", className);
            map.put("summary", summary);
            map.put("finished", finished);
            map.put("active", active);
            map.put("stopped", stopped);
            return map;
        }
    }
}
