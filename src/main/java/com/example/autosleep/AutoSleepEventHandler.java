package com.example.autosleep;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.state.properties.BedPart;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class AutoSleepEventHandler {
    private static final int SEARCH_RADIUS = 20;
    private static final int INTERACT_RADIUS = 5;
    private static final int SLEEP_COOLDOWN_TICKS = 50;

    private boolean isEnabled = false;
    private BlockPos targetBedPos;
    private BlockPos highlightedBedPos;
    private long lastSleepAttemptTick = -SLEEP_COOLDOWN_TICKS;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (ClientSetup.getToggleKey() != null && ClientSetup.getToggleKey().consumeClick()) {
            isEnabled = !isEnabled;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                TextFormatting color = isEnabled ? TextFormatting.GREEN : TextFormatting.RED;
                String state = isEnabled ? "включен" : "выключен";
                mc.player.displayClientMessage(new StringTextComponent("[AutoSleep] Режим " + state).withStyle(color), true);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getWorld().isClientSide() || event.getHand() != Hand.MAIN_HAND || !event.getPlayer().isShiftKeyDown()) {
            return;
        }

        BlockState state = event.getWorld().getBlockState(event.getPos());
        if (state.getBlock() instanceof BedBlock) {
            targetBedPos = normalizeBedPos(state, event.getPos());
            highlightedBedPos = targetBedPos;
            event.getPlayer().displayClientMessage(new StringTextComponent("Кровать привязана!").withStyle(TextFormatting.GREEN), true);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        World world = mc.level;

        if (player == null || world == null || mc.gameMode == null) {
            return;
        }

        BlockPos candidateBed = resolveCandidateBed(player, world).orElse(null);
        highlightedBedPos = candidateBed;

        if (!isValidDimension(world) || !world.isNight() || !canPlayerAutoSleep(mc, player)) {
            return;
        }

        if (candidateBed == null || !isInReach(player, candidateBed) || !isCooldownReady(player)) {
            return;
        }

        attemptSleep(mc, player, world, candidateBed);
    }

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity player = mc.player;
        World world = mc.level;
        if (player == null || world == null || !isEnabled) {
            return;
        }

        Optional<BlockPos> bed = resolveCandidateBed(player, world).filter(pos -> isInReach(player, pos));
        boolean ready = isValidDimension(world) && bed.isPresent();
        boolean noBedAtNight = world.isNight() && !ready;

        String text = noBedAtNight ? "Auto-Sleep: NO BED" : "Auto-Sleep: READY";
        int color = noBedAtNight ? 0xFF3333 : 0x00FF00;
        int x = (event.getWindow().getGuiScaledWidth() - mc.font.width(text)) / 2;
        int y = event.getWindow().getGuiScaledHeight() - 36;
        AbstractGui.fill(event.getMatrixStack(), x - 3, y - 2, x + mc.font.width(text) + 3, y + 10, 0x66000000);
        mc.font.draw(event.getMatrixStack(), text, x, y, color);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled || highlightedBedPos == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }

        BlockState state = mc.level.getBlockState(highlightedBedPos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }

        MatrixStack matrixStack = event.getMatrixStack();
        Vector3d camPos = mc.gameRenderer.getMainCamera().getPosition();
        AxisAlignedBB box = new AxisAlignedBB(highlightedBedPos)
                .inflate(0.01D)
                .move(-camPos.x, -camPos.y, -camPos.z);

        matrixStack.pushPose();
        IRenderTypeBuffer.Impl buffer = mc.renderBuffers().bufferSource();
        WorldRenderer.renderLineBox(matrixStack, buffer.getBuffer(RenderType.lines()), box, 0.0F, 1.0F, 1.0F, 1.0F);
        buffer.endBatch(RenderType.lines());
        matrixStack.popPose();
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        isEnabled = false;
        targetBedPos = null;
        highlightedBedPos = null;
        lastSleepAttemptTick = -SLEEP_COOLDOWN_TICKS;
    }

    private boolean canPlayerAutoSleep(Minecraft mc, ClientPlayerEntity player) {
        return !player.isSleeping() && player.isAlive() && !player.isSpectator() && mc.screen == null;
    }

    private boolean isValidDimension(World world) {
        ResourceLocation dimension = world.dimension().location();
        return World.OVERWORLD.location().equals(dimension);
    }

    private boolean isCooldownReady(ClientPlayerEntity player) {
        long gameTime = player.level.getGameTime();
        return gameTime - lastSleepAttemptTick >= SLEEP_COOLDOWN_TICKS;
    }

    private void attemptSleep(Minecraft mc, ClientPlayerEntity player, World world, BlockPos bedPos) {
        BlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }

        lastSleepAttemptTick = world.getGameTime();
        BlockRayTraceResult hit = new BlockRayTraceResult(
                player.position(),
                state.getValue(BedBlock.FACING).getOpposite(),
                bedPos,
                false
        );
        ActionResultType result = mc.gameMode.useItemOn(player, world, Hand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            lastSleepAttemptTick = world.getGameTime();
        }
    }

    private Optional<BlockPos> resolveCandidateBed(ClientPlayerEntity player, World world) {
        if (targetBedPos != null && isValidBed(world, targetBedPos)) {
            return Optional.of(targetBedPos);
        }

        BlockPos center = player.blockPosition();
        return scanNearbyBeds(center, world)
                .stream()
                .filter(pos -> isValidBed(world, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    private Set<BlockPos> scanNearbyBeds(BlockPos center, World world) {
        int minX = center.getX() - SEARCH_RADIUS;
        int maxX = center.getX() + SEARCH_RADIUS;
        int minY = MathHelper.clamp(center.getY() - 4, world.getMinBuildHeight(), world.getMaxBuildHeight() - 1);
        int maxY = MathHelper.clamp(center.getY() + 4, world.getMinBuildHeight(), world.getMaxBuildHeight() - 1);
        int minZ = center.getZ() - SEARCH_RADIUS;
        int maxZ = center.getZ() + SEARCH_RADIUS;

        Set<BlockPos> beds = new HashSet<BlockPos>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int x;
        int y;
        int z;

        for (x = minX; x <= maxX; x++) {
            for (y = minY; y <= maxY; y++) {
                for (z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    BlockState state = world.getBlockState(mutable);
                    if (state.getBlock() instanceof BedBlock) {
                        beds.add(normalizeBedPos(state, mutable));
                    }
                }
            }
        }

        return beds;
    }

    private boolean isValidBed(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }

        return !state.hasProperty(BlockStateProperties.OCCUPIED) || !state.getValue(BlockStateProperties.OCCUPIED);
    }

    private BlockPos normalizeBedPos(BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof BedBlock)) {
            return pos.immutable();
        }

        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos.immutable();
        }

        return pos.relative(state.getValue(BedBlock.FACING));
    }

    private boolean isInReach(ClientPlayerEntity player, BlockPos bedPos) {
        double maxDistanceSq = INTERACT_RADIUS * INTERACT_RADIUS;
        return player.distanceToSqr(
                bedPos.getX() + 0.5D,
                bedPos.getY() + 0.5D,
                bedPos.getZ() + 0.5D
        ) <= maxDistanceSq;
    }
}
