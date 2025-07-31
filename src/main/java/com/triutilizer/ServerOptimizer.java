package com.triutilizer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("triutilizer")
public class ServerOptimizer {
    private static final Logger LOGGER = LogManager.getLogger();
    private final CPUManager cpuManager;

    public ServerOptimizer() {
        this.cpuManager = new CPUManager();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Tri Utilizer activating...");
        cpuManager.initialize(event);
        cpuManager.registerCommands(event.getServer().getCommands().getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Tri Utilizer shutting down...");
        cpuManager.shutdown();
    }
}
