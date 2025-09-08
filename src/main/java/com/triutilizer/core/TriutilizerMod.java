package com.triutilizer.core;

import com.triutilizer.core.commands.TriutilizerRootCommand;
import com.triutilizer.core.compat.TriutilizerAPI;
import com.triutilizer.core.concurrency.TaskManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TriutilizerMod.MODID)
public class TriutilizerMod {
    public static final String MODID = "triutilizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public TriutilizerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        // Client setup remains optional
        modBus.addListener(this::onClientSetup);

        // Config
        TriutilizerConfig.init();

        // Register ourselves to the forge event bus for server events like command registration
        MinecraftForge.EVENT_BUS.register(this);

        // Initialize worker pool
        TriutilizerAPI.initAndAutoDetect();
        TaskManager.init();
    // Compat: informational only; triutilizer remains multicore but avoids off-thread world access.
        LOGGER.info("Triutilizer initialized");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // no-op
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // No-op for now
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TriutilizerRootCommand.register(event.getDispatcher());
    }
    
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerAboutToStart(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
        com.triutilizer.core.util.MainThread.bind(event.getServer());
    TriutilizerAPI.onServerAboutToStart(event.getServer());
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        LOGGER.info("Triutilizer shutting down worker pool...");
        TaskManager.shutdown();
        com.triutilizer.core.util.MainThread.clear();
    TriutilizerAPI.onServerStopping(event.getServer());
    }
}
