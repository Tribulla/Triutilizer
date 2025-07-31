package com.triutilizer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BlockEventHandler {
    private static final CPUManager cpuManager = CPUManager.getInstance();

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockPos pos = event.getPos();
            BlockState state = event.getState();
            
            // Submit task to check and update neighbors after block is broken
            cpuManager.submitTask(new BlockDestroyedWorkItem(level, pos, state));
        }
    }
}
