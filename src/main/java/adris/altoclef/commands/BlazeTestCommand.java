package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateStructureCommandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sets up a blaze hunting scenario by gearing the player, teleporting near a fortress, and launching blaze collection.
 */
public class BlazeTestCommand extends Command {

    private static final int DEFAULT_ROD_TARGET = 7;

    private record LoadoutEntry(Item item, int count) {}

    private static final List<LoadoutEntry> LOADOUT = List.of(
            new LoadoutEntry(Items.DIAMOND_SWORD, 1),
            new LoadoutEntry(Items.DIAMOND_PICKAXE, 1),
            new LoadoutEntry(Items.DIAMOND_AXE, 1),
            new LoadoutEntry(Items.SHIELD, 1),
            new LoadoutEntry(Items.DIAMOND_HELMET, 1),
            new LoadoutEntry(Items.DIAMOND_CHESTPLATE, 1),
            new LoadoutEntry(Items.DIAMOND_LEGGINGS, 1),
            new LoadoutEntry(Items.DIAMOND_BOOTS, 1),
            new LoadoutEntry(Items.BOW, 1),
            new LoadoutEntry(Items.ARROW, 64),
            new LoadoutEntry(Items.CROSSBOW, 1),
            new LoadoutEntry(Items.FLINT_AND_STEEL, 1),
            new LoadoutEntry(Items.LAVA_BUCKET, 1),
            new LoadoutEntry(Items.COBBLESTONE, 64),
            new LoadoutEntry(Items.OAK_PLANKS, 64),
            new LoadoutEntry(Items.GOLDEN_APPLE, 4),
            new LoadoutEntry(Items.ENDER_PEARL, 16),
            new LoadoutEntry(Items.COOKED_PORKCHOP, 64)
    );

    public BlazeTestCommand() {
        super("blaze_test", "Prepares blaze-rod testing scenario and starts the blaze task");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            Debug.logWarning("Cannot run @blaze_test: no client player present.");
            finish();
            return;
        }

        boolean hasCommandPrivileges = MinecraftClient.getInstance().isInSingleplayer() || player.hasPermissionLevel(2);

        giveLoadout(player, player.getGameProfile().getName(), hasCommandPrivileges);
        topOffPlayer(player);

        boolean canTeleport = hasCommandPrivileges;
        if (canTeleport) {
            mod.runUserTask(new BlazeTestSetupTask(DEFAULT_ROD_TARGET, player.getGameProfile().getName()));
        } else {
            Debug.logWarning("@blaze_test: Missing permission to teleport. Starting blaze collection in-place.");
            mod.runUserTask(new CollectBlazeRodsTask(DEFAULT_ROD_TARGET));
        }
        finish();
    }

    private void giveLoadout(ClientPlayerEntity player, String playerName, boolean canUseCommands) {
        for (LoadoutEntry entry : LOADOUT) {
            giveItem(player, playerName, entry.item, entry.count, canUseCommands);
        }
        ensureEquipment(player, playerName, canUseCommands);
    }

    private void topOffPlayer(ClientPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().add(20, 20);
    }

    private void giveItem(ClientPlayerEntity player, String playerName, Item item, int count, boolean canUseCommands) {
        if (count <= 0) {
            return;
        }
        if (canUseCommands) {
            issueGiveCommands(player, playerName, item, count);
            return;
        }
        int remaining = count;
        while (remaining > 0) {
            int toGive = Math.min(item.getMaxCount(), remaining);
            ItemStack stack = new ItemStack(item, toGive);
            giveStack(player, stack);
            remaining -= toGive;
        }
    }

    private void giveStack(ClientPlayerEntity player, ItemStack stack) {
        ItemStack copy = stack.copy();
        boolean accepted = player.giveItemStack(copy);
        if (!accepted && !copy.isEmpty()) {
            player.dropItem(copy, false);
        }
    }

    private void ensureEquipment(ClientPlayerEntity player, String playerName, boolean canUseCommands) {
        if (canUseCommands) {
            issueReplaceCommand(player, playerName, "armor.head", Items.DIAMOND_HELMET, 1);
            issueReplaceCommand(player, playerName, "armor.chest", Items.DIAMOND_CHESTPLATE, 1);
            issueReplaceCommand(player, playerName, "armor.legs", Items.DIAMOND_LEGGINGS, 1);
            issueReplaceCommand(player, playerName, "armor.feet", Items.DIAMOND_BOOTS, 1);
            issueReplaceCommand(player, playerName, "weapon.mainhand", Items.DIAMOND_SWORD, 1);
            issueReplaceCommand(player, playerName, "weapon.offhand", Items.SHIELD, 1);
            return;
        }

        setArmorSlot(player, EquipmentSlot.HEAD, Items.DIAMOND_HELMET);
        setArmorSlot(player, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE);
        setArmorSlot(player, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS);
        setArmorSlot(player, EquipmentSlot.FEET, Items.DIAMOND_BOOTS);
        setMainHand(player, Items.DIAMOND_SWORD);
        setOffhand(player, Items.SHIELD);
    }

    private void issueGiveCommands(ClientPlayerEntity player, String playerName, Item item, int count) {
        String itemId = Registries.ITEM.getId(item).toString();
        int remaining = count;
        while (remaining > 0) {
            int toGive = Math.min(64, remaining);
            String command = String.format(Locale.ROOT, "give %s %s %d", playerName, itemId, toGive);
            PlayerVer.sendChatCommand(player, command);
            remaining -= toGive;
        }
    }

    private void issueReplaceCommand(ClientPlayerEntity player, String playerName, String slot, Item item, int count) {
        String itemId = Registries.ITEM.getId(item).toString();
        String command = String.format(Locale.ROOT, "item replace entity %s %s with %s %d", playerName, slot, itemId, Math.max(1, count));
        PlayerVer.sendChatCommand(player, command);
    }

    private void setArmorSlot(ClientPlayerEntity player, EquipmentSlot slot, Item item) {
        ItemStack current = player.getEquippedStack(slot);
        if (!current.isEmpty() && current.getItem() == item) {
            return;
        }
        ItemStack previous = current.isEmpty() ? ItemStack.EMPTY : current.copy();
        player.equipStack(slot, new ItemStack(item));
        if (!previous.isEmpty() && previous.getItem() != item) {
            player.getInventory().insertStack(previous);
        }
    }

    private void setMainHand(ClientPlayerEntity player, Item item) {
        ItemStack current = player.getMainHandStack();
        if (!current.isEmpty() && current.getItem() == item) {
            return;
        }
        int slot = findSlotWithItem(player, item);
        if (slot == -1) {
            player.getInventory().insertStack(new ItemStack(item));
            slot = findSlotWithItem(player, item);
        }
        if (slot != -1) {
            player.getInventory().selectedSlot = slot;
        }
    }

    private void setOffhand(ClientPlayerEntity player, Item item) {
        ItemStack current = player.getOffHandStack();
        if (!current.isEmpty() && current.getItem() == item) {
            return;
        }
        ItemStack previous = current.isEmpty() ? ItemStack.EMPTY : current.copy();
        player.getInventory().offHand.set(0, new ItemStack(item));
        if (!previous.isEmpty() && previous.getItem() != item) {
            player.getInventory().insertStack(previous);
        }
    }

    private int findSlotWithItem(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            ItemStack stack = player.getInventory().main.get(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static class BlazeTestSetupTask extends Task {

        private static final double TELEPORT_CONFIRM_DISTANCE = 96;
        private static final double TELEPORT_TIMEOUT_SECONDS = 12;
        private static final double ENTER_NETHER_TIMEOUT_SECONDS = 8;
        private static final int NETHER_STAGING_Y = 80;
        private static final int MIN_SAFE_Y = 40;
        private static final int MAX_SAFE_Y = 90;
        private static final int SPAWNER_SEARCH_RADIUS = 72;
    private static final int SPAWNER_SURFACE_RADIUS = 4;
    private static final double SPAWNER_DISABLE_RADIUS = 6.0;
        private static final double SPAWNER_SEARCH_TIMEOUT_SECONDS = 10;

        private final CollectBlazeRodsTask collectTask;
        private final String playerName;
        private LocateStructureCommandHelper fortressLocator;
        private boolean teleportIssued;
        private BlockPos teleportDestination;
        private boolean collecting;
        private boolean netherTeleportIssued;
        private BlockPos netherTeleportTarget;
        private BlockPos lastTeleportRequest;
    private BlockPos locatedBlazeSpawner;
    private BlockPos spawnerLandingTarget;
    private boolean spawnerSearchStarted;
    private boolean spawnerLandingComplete;
        private final TimerGame netherTeleportTimeout = new TimerGame(ENTER_NETHER_TIMEOUT_SECONDS);
        private final TimerGame fortressTeleportTimeout = new TimerGame(TELEPORT_TIMEOUT_SECONDS);
        private final TimerGame teleportCommandCooldown = new TimerGame(1);
    private final TimerGame spawnerSearchTimeout = new TimerGame(SPAWNER_SEARCH_TIMEOUT_SECONDS);

        private BlazeTestSetupTask(int rodCount, String playerName) {
            collectTask = new CollectBlazeRodsTask(rodCount);
            this.playerName = playerName;
        }

        @Override
        protected void onStart() {
            AltoClef mod = AltoClef.getInstance();
            fortressLocator = new LocateStructureCommandHelper(mod,
                    "minecraft:fortress",
                    "fortress",
                    Dimension.NETHER,
                    5,
                    8);
            teleportIssued = false;
            collecting = false;
            teleportDestination = null;
            netherTeleportIssued = false;
            netherTeleportTarget = null;
            lastTeleportRequest = null;
            locatedBlazeSpawner = null;
            spawnerLandingTarget = null;
            spawnerSearchStarted = false;
            spawnerLandingComplete = false;
            netherTeleportTimeout.forceElapse();
            fortressTeleportTimeout.forceElapse();
            teleportCommandCooldown.forceElapse();
            spawnerSearchTimeout.forceElapse();
        }

        @Override
        protected Task onTick() {
            AltoClef mod = AltoClef.getInstance();
            if (collecting) {
                setDebugState("Collecting blaze rods");
                return collectTask;
            }

            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
                if (!netherTeleportIssued) {
                    attemptNetherTeleport();
                    netherTeleportIssued = true;
                    netherTeleportTimeout.reset();
                } else if (netherTeleportTimeout.elapsed()) {
                    Debug.logWarning("@blaze_test: Nether teleport timed out, attempting again.");
                    netherTeleportIssued = false;
                }
                setDebugState("Teleporting to Nether for blaze test");
                return null;
            }

            if (!ensureSafeNetherSpawn(mod)) {
                setDebugState("Stabilizing Nether staging area");
                return null;
            }

            if (fortressLocator == null) {
                collecting = true;
                setDebugState("Collecting blaze rods");
                return collectTask;
            }

            if (!teleportIssued) {
                setDebugState("Locating blaze fortress for teleport");
                fortressLocator.tick();
                Optional<BlockPos> located = fortressLocator.getLocatedPosition();
                if (located.isPresent()) {
                    attemptTeleport(located.get());
                    teleportIssued = true;
                    fortressTeleportTimeout.reset();
                }
                return null;
            }

            if (teleportDestination != null) {
                if (fortressTeleportTimeout.elapsed()) {
                    Debug.logWarning("@blaze_test: Teleportation timed out, continuing with blaze task regardless.");
                    collecting = true;
                    closeLocator();
                    return collectTask;
                }

                ClientPlayerEntity player = mod.getPlayer();
                if (player == null) {
                    return null;
                }
                BlockPos playerPos = player.getBlockPos();
                if (!playerPos.isWithinDistance(teleportDestination, TELEPORT_CONFIRM_DISTANCE)) {
                    setDebugState("Waiting for fortress teleport to resolve");
                    return null;
                }

                if (!ensureSafeFortressLanding(mod)) {
                    setDebugState("Stabilizing fortress landing");
                    return null;
                }

                collecting = true;
                teleportDestination = null;
                closeLocator();
                setDebugState("Collecting blaze rods");
                return collectTask;
            }
            return null;
        }

        private void attemptTeleport(BlockPos locatedPos) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) {
                Debug.logWarning("@blaze_test: Cannot teleport, missing client player instance.");
                collecting = true;
                closeLocator();
                return;
            }
            BlockPos target = computeFortressTeleportTarget(locatedPos, player);
            teleportDestination = target;
            requestTeleport(target, "@blaze_test: Teleporting to fortress at ");
        }

        private void attemptNetherTeleport() {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) {
                Debug.logWarning("@blaze_test: Cannot issue Nether teleport, missing client player instance.");
                collecting = true;
                closeLocator();
                return;
            }
            netherTeleportTarget = new BlockPos(0, NETHER_STAGING_Y, 0);
            requestTeleport(netherTeleportTarget, "@blaze_test: Issued Nether staging teleport to ");
        }

        private BlockPos computeFortressTeleportTarget(BlockPos locatedPos, ClientPlayerEntity player) {
            ClientWorld world = (ClientWorld) player.getWorld();
            int topLimit = world != null ? world.getTopY() - 5 : 120;
            int bottomLimit = world != null ? world.getBottomY() + 5 : 10;
            int guessY = locatedPos.getY();
            if (guessY <= bottomLimit || guessY >= topLimit || guessY == player.getBlockY()) {
                guessY = Math.max(bottomLimit + 4, 68);
            }
            guessY = Math.min(topLimit, guessY + 3);
            return new BlockPos(locatedPos.getX(), guessY, locatedPos.getZ());
        }

        private boolean ensureSafeNetherSpawn(AltoClef mod) {
            if (!netherTeleportIssued) {
                return true;
            }
            ClientPlayerEntity player = mod.getPlayer();
            if (player == null) {
                return false;
            }
            Optional<BlockPos> currentStandable = getCurrentStandableFloor(mod, player);
            if (currentStandable.isPresent()) {
                netherTeleportIssued = false;
                netherTeleportTarget = null;
                return true;
            }

            BlockPos searchCenter = netherTeleportTarget != null ? netherTeleportTarget : player.getBlockPos();
            Optional<BlockPos> surfaceWithinRange = findStandableSurface(mod, searchCenter, 16, MIN_SAFE_Y, MAX_SAFE_Y);
            if (surfaceWithinRange.isPresent()) {
                BlockPos safeFeet = surfaceWithinRange.get().up();
                netherTeleportTarget = safeFeet.toImmutable();
                if (!safeFeet.equals(player.getBlockPos())) {
                    if (requestTeleport(safeFeet, "@blaze_test: Relocating Nether spawn inside safe range at ")) {
                        netherTeleportTimeout.reset();
                    }
                    return false;
                }
            }

            Optional<BlockPos> belowFromTarget = Optional.empty();
            if (netherTeleportTarget != null) {
                belowFromTarget = findStandableBelowColumn(mod, netherTeleportTarget, 128, MIN_SAFE_Y);
            }
            if (belowFromTarget.isPresent()) {
                BlockPos safeFeet = belowFromTarget.get().up();
                netherTeleportTarget = safeFeet.toImmutable();
                if (requestTeleport(safeFeet, "@blaze_test: Dropping below Nether roof to ")) {
                    netherTeleportTimeout.reset();
                }
                return false;
            }

            Optional<BlockPos> belowCurrent = findStandableBelowColumn(mod, player.getBlockPos(), 128, MIN_SAFE_Y);
            if (belowCurrent.isPresent()) {
                BlockPos safeFeet = belowCurrent.get().up();
                netherTeleportTarget = safeFeet.toImmutable();
                if (requestTeleport(safeFeet, "@blaze_test: Adjusting Nether spawn to ")) {
                    netherTeleportTimeout.reset();
                }
                return false;
            }

            Optional<BlockPos> safeFloor = findStandableSurface(mod, player.getBlockPos(), 12, MIN_SAFE_Y, MAX_SAFE_Y);
            if (safeFloor.isEmpty()) {
                return false;
            }
            BlockPos safeFeet = safeFloor.get().up();
            if (!safeFeet.equals(player.getBlockPos())) {
                if (requestTeleport(safeFeet, "@blaze_test: Sliding to safe Nether perch at ")) {
                    netherTeleportTimeout.reset();
                }
            }
            return false;
        }

        private boolean ensureSafeFortressLanding(AltoClef mod) {
            if (teleportDestination == null) {
                return true;
            }
            ClientPlayerEntity player = mod.getPlayer();
            if (player == null) {
                return false;
            }
            if (!mod.getChunkTracker().isChunkLoaded(teleportDestination)) {
                return false;
            }

            if (spawnerLandingComplete) {
                return true;
            }

            BlockPos playerFeet = player.getBlockPos();
            Optional<BlockPos> currentStandableFloor = getCurrentStandableFloor(mod, player);

            if (shouldFinalizeLanding(playerFeet)) {
                finalizeSpawnerLanding();
                return true;
            }

            if (currentStandableFloor.isEmpty()) {
                Optional<BlockPos> localSurface = findStandableSurface(mod, teleportDestination, 12, MIN_SAFE_Y, MAX_SAFE_Y);
                if (localSurface.isPresent()) {
                    BlockPos safeFeet = localSurface.get().up();
                    if (!safeFeet.equals(teleportDestination)) {
                        teleportDestination = safeFeet;
                        if (requestTeleport(safeFeet, "@blaze_test: Sliding fortress landing to ")) {
                            fortressTeleportTimeout.reset();
                        }
                    }
                    return false;
                }

                Optional<BlockPos> dropBelow = findStandableBelowColumn(mod, teleportDestination, 128, MIN_SAFE_Y);
                if (dropBelow.isPresent()) {
                    BlockPos safeFeet = dropBelow.get().up();
                    teleportDestination = safeFeet;
                    if (requestTeleport(safeFeet, "@blaze_test: Dropping fortress landing to ")) {
                        fortressTeleportTimeout.reset();
                    }
                    return false;
                }

                Optional<BlockPos> areaSearch = findStandableSurface(mod, playerFeet, 12, MIN_SAFE_Y, MAX_SAFE_Y);
                if (areaSearch.isPresent()) {
                    BlockPos safeFeet = areaSearch.get().up();
                    teleportDestination = safeFeet;
                    if (!safeFeet.equals(playerFeet)) {
                        if (requestTeleport(safeFeet, "@blaze_test: Repositioning fortress landing to ")) {
                            fortressTeleportTimeout.reset();
                        }
                    }
                    return false;
                }

                return false;
            }

            if (!spawnerSearchStarted) {
                spawnerSearchStarted = true;
                spawnerSearchTimeout.reset();
            }

            if (spawnerLandingTarget != null) {
                if (playerFeet.isWithinDistance(spawnerLandingTarget, SPAWNER_DISABLE_RADIUS)) {
                    finalizeSpawnerLanding();
                    return true;
                }
                if (!isLandingSpotValid(mod, spawnerLandingTarget)) {
                    spawnerLandingTarget = null;
                } else {
                    teleportDestination = spawnerLandingTarget;
                    if (requestTeleport(spawnerLandingTarget, "@blaze_test: Parking beside blaze spawner at ")) {
                        fortressTeleportTimeout.reset();
                    }
                    return false;
                }
            }

            if (locatedBlazeSpawner == null || !isBlazeSpawner(mod, locatedBlazeSpawner)) {
                Optional<BlockPos> potential = findBlazeSpawnerWithinRadius(mod, teleportDestination, SPAWNER_SEARCH_RADIUS);
                if (potential.isEmpty()) {
                    potential = findBlazeSpawnerWithinRadius(mod, playerFeet, SPAWNER_SEARCH_RADIUS);
                }
                potential.ifPresent(pos -> locatedBlazeSpawner = pos.toImmutable());
            }

            if (locatedBlazeSpawner != null) {
                Optional<BlockPos> landingFeet = findSpawnerLanding(mod, locatedBlazeSpawner);
                if (landingFeet.isPresent()) {
                    BlockPos targetFeet = landingFeet.get();
                    spawnerLandingTarget = targetFeet.toImmutable();
                    if (playerFeet.isWithinDistance(targetFeet, SPAWNER_DISABLE_RADIUS)) {
                        finalizeSpawnerLanding();
                        return true;
                    }
                    teleportDestination = spawnerLandingTarget;
                    if (requestTeleport(spawnerLandingTarget, "@blaze_test: Teleporting next to blaze spawner at ")) {
                        fortressTeleportTimeout.reset();
                    }
                    return false;
                }
            }

            if (spawnerSearchStarted && spawnerSearchTimeout.elapsed()) {
                Debug.logWarning("@blaze_test: Blaze spawner not found near fortress landing, proceeding anyway.");
                finalizeSpawnerLanding();
                return true;
            }

            return false;
        }

        private boolean shouldFinalizeLanding(BlockPos playerFeet) {
            if (spawnerLandingComplete) {
                return true;
            }
            if (spawnerLandingTarget != null && playerFeet.isWithinDistance(spawnerLandingTarget, SPAWNER_DISABLE_RADIUS)) {
                return true;
            }
            if (locatedBlazeSpawner != null && playerFeet.isWithinDistance(locatedBlazeSpawner, SPAWNER_DISABLE_RADIUS)) {
                return true;
            }
            return false;
        }

        private void finalizeSpawnerLanding() {
            spawnerLandingComplete = true;
            teleportDestination = null;
            spawnerLandingTarget = null;
        }

        private Optional<BlockPos> getCurrentStandableFloor(AltoClef mod, ClientPlayerEntity player) {
            BlockPos playerFeetBlock = player.getBlockPos();
            BlockPos belowFeet = playerFeetBlock.down();
            if (mod.getChunkTracker().isChunkLoaded(belowFeet) && isStandableSpot(mod, belowFeet)) {
                return Optional.of(belowFeet.toImmutable());
            }
            return Optional.empty();
        }

        private Optional<BlockPos> findStandableSurface(AltoClef mod, BlockPos origin, int horizontalRadius, int minY, int maxY) {
            ClientWorld world = mod.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            int top = Math.min(world.getTopY() - 3, maxY);
            int bottom = Math.max(world.getBottomY(), minY);
            BlockPos.Mutable floor = new BlockPos.Mutable();
            BlockPos.Mutable feet = new BlockPos.Mutable();
            BlockPos.Mutable head = new BlockPos.Mutable();
            for (int y = top; y >= bottom; y--) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                        int x = origin.getX() + dx;
                        int z = origin.getZ() + dz;
                        floor.set(x, y, z);
                        if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                            continue;
                        }
                        if (!WorldHelper.isSolidBlock(floor)) {
                            continue;
                        }
                        feet.set(x, y + 1, z);
                        head.set(x, y + 2, z);
                        if (!isSpaceOpen(world, feet) || !isSpaceOpen(world, head)) {
                            continue;
                        }
                        return Optional.of(floor.toImmutable());
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<BlockPos> findStandableBelowColumn(AltoClef mod, BlockPos start, int maxDepth, int minY) {
            ClientWorld world = mod.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            BlockPos.Mutable floor = new BlockPos.Mutable();
            BlockPos.Mutable feet = new BlockPos.Mutable();
            BlockPos.Mutable head = new BlockPos.Mutable();
            int startY = Math.min(world.getTopY() - 3, start.getY());
            int bottom = Math.max(world.getBottomY(), Math.max(minY, startY - maxDepth));
            for (int y = startY; y >= bottom; y--) {
                floor.set(start.getX(), y - 1, start.getZ());
                if (floor.getY() < world.getBottomY()) {
                    break;
                }
                if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                    continue;
                }
                if (!isStandableSpot(mod, floor)) {
                    continue;
                }
                feet.set(start.getX(), y, start.getZ());
                head.set(start.getX(), y + 1, start.getZ());
                if (isSpaceOpen(world, feet) && isSpaceOpen(world, head)) {
                    return Optional.of(floor.toImmutable());
                }
            }
            return Optional.empty();
        }

        private Optional<BlockPos> findBlazeSpawnerWithinRadius(AltoClef mod, BlockPos origin, int radius) {
            if (mod.getBlockScanner() == null) {
                return Optional.empty();
            }
            Optional<BlockPos> within = mod.getBlockScanner().getNearestWithinRange(origin, radius, Blocks.SPAWNER);
            if (within.isPresent() && isBlazeSpawner(mod, within.get())) {
                return Optional.of(within.get().toImmutable());
            }
            return mod.getBlockScanner().getNearestBlock(pos -> isBlazeSpawner(mod, pos), Blocks.SPAWNER);
        }

        private Optional<BlockPos> findSpawnerLanding(AltoClef mod, BlockPos spawnerPos) {
            if (mod.getWorld() == null) {
                return Optional.empty();
            }
            int[][] offsets = {
                    {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                    {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                    {2, 0}, {-2, 0}, {0, 2}, {0, -2}
            };
            for (int[] offset : offsets) {
                BlockPos feet = spawnerPos.add(offset[0], 0, offset[1]);
                BlockPos floor = feet.down();
                if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                    continue;
                }
                if (!isStandableSpot(mod, floor)) {
                    continue;
                }
                if (isLavaDirectlyBelow(mod, floor)) {
                    continue;
                }
                return Optional.of(feet.toImmutable());
            }
            Optional<BlockPos> fallbackFloor = findStandableSurface(mod, spawnerPos, SPAWNER_SURFACE_RADIUS, MIN_SAFE_Y, MAX_SAFE_Y);
            if (fallbackFloor.isPresent() && !isLavaDirectlyBelow(mod, fallbackFloor.get())) {
                return Optional.of(fallbackFloor.get().up().toImmutable());
            }
            return Optional.empty();
        }

        private boolean isLandingSpotValid(AltoClef mod, BlockPos landingFeet) {
            BlockPos floor = landingFeet.down();
            if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                return false;
            }
            if (!isStandableSpot(mod, floor)) {
                return false;
            }
            return !isLavaDirectlyBelow(mod, floor);
        }

        private boolean isBlazeSpawner(AltoClef mod, BlockPos pos) {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) {
                return false;
            }
            return WorldHelper.getSpawnerEntity(pos) instanceof BlazeEntity;
        }

        private boolean isLavaDirectlyBelow(AltoClef mod, BlockPos floor) {
            ClientWorld world = mod.getWorld();
            if (world == null) {
                return false;
            }
            BlockPos below = floor.down();
            if (!mod.getChunkTracker().isChunkLoaded(below)) {
                return false;
            }
            return world.getFluidState(below).isIn(FluidTags.LAVA);
        }

        private boolean isStandableSpot(AltoClef mod, BlockPos floor) {
            ClientWorld world = mod.getWorld();
            if (world == null) {
                return false;
            }
            if (!mod.getChunkTracker().isChunkLoaded(floor)) {
                return false;
            }
            if (!WorldHelper.isSolidBlock(floor)) {
                return false;
            }
            BlockPos feet = floor.up();
            BlockPos head = feet.up();
            return isSpaceOpen(world, feet) && isSpaceOpen(world, head);
        }

        private boolean isSpaceOpen(ClientWorld world, BlockPos pos) {
            if (!world.getWorldBorder().contains(pos)) {
                return false;
            }
            var state = world.getBlockState(pos);
            if (!state.getCollisionShape(world, pos).isEmpty()) {
                return false;
            }
            return world.getFluidState(pos).isEmpty();
        }

        private boolean requestTeleport(BlockPos target, String messagePrefix) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) {
                return false;
            }
            if (lastTeleportRequest != null && lastTeleportRequest.equals(target) && !teleportCommandCooldown.elapsed()) {
                return false;
            }
            String command = String.format(Locale.ROOT,
                    "execute in minecraft:the_nether run tp %s %d %d %d",
                    playerName,
                    target.getX(),
                    target.getY(),
                    target.getZ());
            PlayerVer.sendChatCommand(player, command);
            Debug.logMessage(messagePrefix + target.toShortString());
            lastTeleportRequest = target.toImmutable();
            teleportCommandCooldown.reset();
            return true;
        }

        @Override
        protected void onStop(Task interruptTask) {
            closeLocator();
        }

        private void closeLocator() {
            if (fortressLocator != null) {
                fortressLocator.close();
                fortressLocator = null;
            }
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof BlazeTestSetupTask;
        }

        @Override
        protected String toDebugString() {
            return "Blaze Test Setup";
        }
    }
}
