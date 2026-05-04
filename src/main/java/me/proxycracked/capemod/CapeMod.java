package me.proxycracked.capemod;

import me.proxycracked.capemod.cape.CapeManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = "gifcapes", name = "GifCapes", version = "@VERSION@", clientSideOnly = true, acceptedMinecraftVersions = "1.8.9")
public class CapeMod {

  @EventHandler
  public static void init(FMLInitializationEvent event) {
    MinecraftForge.EVENT_BUS.register(new Events());
    CapeManager.initFromDisk();
  }
}
