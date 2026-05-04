package me.proxycracked.capemod;

import me.proxycracked.capemod.cape.CapeManager;
import me.proxycracked.capemod.gui.GuiChangeCape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent;
import net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;

public class Events {
  private static final Minecraft mc = Minecraft.getMinecraft();
  private static final int CAPE_BTN_ID    = 1792;
  private static final int CAPE_TOGGLE_ID = 1793;

  // NetworkPlayerInfo.locationCape reflection. SRG names differ between dev
  // (MCP) and prod (SRG). Try named lookup first, then walk declared
  // ResourceLocation fields by source order — locationSkin is the 1st RL,
  // locationCape is the 2nd.
  private static volatile Field npiCapeField;
  private static volatile boolean npiResolved;

  // OptiFine adds a locationOfCape field to AbstractClientPlayer that
  // short-circuits getLocationCape(). Resolved lazily; absent on
  // vanilla/Forge installs.
  private static Field optifineCapeField;
  private static boolean optifineCapeFieldChecked;

  @SubscribeEvent
  public void initGuiEvent(InitGuiEvent.Post event) {
    if (event.gui instanceof GuiIngameMenu) {
      event.buttonList.add(new GuiButton(CAPE_BTN_ID,
        event.gui.width - 106, 6, 100, 20, "Cape"));
      event.buttonList.add(new GuiButton(CAPE_TOGGLE_ID,
        6, 6, 100, 20, toggleLabel()));
    }
  }

  @SubscribeEvent
  public void onClick(ActionPerformedEvent.Post event) {
    if (!(event.gui instanceof GuiIngameMenu)) return;
    if (event.button.id == CAPE_BTN_ID) {
      mc.displayGuiScreen(new GuiChangeCape(event.gui));
    } else if (event.button.id == CAPE_TOGGLE_ID) {
      CapeManager.setEnabled(!CapeManager.isEnabled());
      event.button.displayString = toggleLabel();
      // When disabling, proactively strip the cape from NPI so the render
      // picks up on the very next frame without waiting for a respawn.
      if (!CapeManager.isEnabled()) stripCape();
    }
  }

  private static String toggleLabel() {
    return CapeManager.isEnabled() ? "Cape: ON" : "Cape: OFF";
  }

  @SubscribeEvent
  public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END) return;
    if (CapeManager.consumePendingStrip()) stripCape();
    if (CapeManager.isEnabled()) applyCustomCape();
  }

  // Re-apply on every render frame too. Without Mixin we can't intercept
  // getLocationCape directly, so running on render-tick is the next best
  // thing — beats the SkinManager async callback that can land between
  // client-tick and render and stomp our entry for a frame.
  @SubscribeEvent
  public void onRenderTickCape(TickEvent.RenderTickEvent event) {
    if (event.phase != TickEvent.Phase.START) return;
    if (CapeManager.consumePendingStrip()) stripCape();
    if (CapeManager.isEnabled()) applyCustomCape();
  }

  private static void applyCustomCape() {
    ResourceLocation cape = CapeManager.getCape();
    if (cape == null) return;
    if (mc.thePlayer == null || mc.getNetHandler() == null) return;

    // LayerCape checks isWearing(CAPE) before rendering. The bit comes from
    // the player's DataWatcher byte 10. Force it on client-side so the layer
    // always renders our custom cape even if "Cape" is disabled in skin
    // customization.
    try {
      byte mask = mc.thePlayer.getDataWatcher().getWatchableObjectByte(10);
      byte capeBit = (byte) EnumPlayerModelParts.CAPE.getPartMask();
      if ((mask & capeBit) == 0) {
        mc.thePlayer.getDataWatcher().updateObject(10, (byte) (mask | capeBit));
      }
    } catch (Throwable ignored) {}

    NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
    if (info == null) info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getName());
    if (info == null) {
      String myName = mc.thePlayer.getName();
      for (NetworkPlayerInfo cand : mc.getNetHandler().getPlayerInfoMap()) {
        if (cand.getGameProfile() != null
            && myName.equalsIgnoreCase(cand.getGameProfile().getName())) {
          info = cand;
          break;
        }
      }
    }
    if (info != null) setNpiCape(info, cape);

    overrideOptifineCape(mc.thePlayer, cape);
  }

  private static void stripCape() {
    if (mc.getNetHandler() == null || mc.thePlayer == null) return;
    NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
    if (info != null) setNpiCape(info, null);
    overrideOptifineCape(mc.thePlayer, null);
  }

  private static void resolveNpiCape() {
    if (npiResolved) return;
    synchronized (Events.class) {
      if (npiResolved) return;
      try {
        for (String name : new String[]{"locationCape", "field_178865_d", "field_178864_c"}) {
          try {
            Field f = NetworkPlayerInfo.class.getDeclaredField(name);
            if (f.getType() == ResourceLocation.class && !"locationSkin".equals(name)) {
              f.setAccessible(true);
              npiCapeField = f;
              break;
            }
          } catch (NoSuchFieldException ignored) {}
        }
        if (npiCapeField == null) {
          int rlIdx = 0;
          for (Field f : NetworkPlayerInfo.class.getDeclaredFields()) {
            if (f.getType() == ResourceLocation.class) {
              f.setAccessible(true);
              if (rlIdx == 1) { npiCapeField = f; break; }
              rlIdx++;
            }
          }
        }
      } catch (Throwable t) {
        System.err.println("[CapeMod] NPI field resolution failed: " + t.getMessage());
      }
      npiResolved = true;
    }
  }

  private static void setNpiCape(NetworkPlayerInfo info, ResourceLocation cape) {
    resolveNpiCape();
    if (npiCapeField == null) return;
    try {
      Object cur = npiCapeField.get(info);
      if (cur != cape) npiCapeField.set(info, cape);
    } catch (Throwable ignored) {}
  }

  private static void overrideOptifineCape(net.minecraft.entity.player.EntityPlayer player, ResourceLocation cape) {
    if (!optifineCapeFieldChecked) {
      optifineCapeFieldChecked = true;
      try {
        for (String name : new String[]{"locationOfCape", "locationCape", "capeLocation"}) {
          try {
            Field f = player.getClass().getSuperclass().getDeclaredField(name);
            if (f.getType() == ResourceLocation.class) {
              f.setAccessible(true);
              optifineCapeField = f;
              break;
            }
          } catch (NoSuchFieldException ignored) {}
        }
        if (optifineCapeField == null) {
          Class<?> c = player.getClass();
          outer:
          while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
              if (f.getType() == ResourceLocation.class
                  && f.getName().toLowerCase().contains("cape")) {
                f.setAccessible(true);
                optifineCapeField = f;
                break outer;
              }
            }
            c = c.getSuperclass();
          }
        }
      } catch (Throwable ignored) {}
    }
    if (optifineCapeField == null) return;
    try {
      Object current = optifineCapeField.get(player);
      if (current != cape) optifineCapeField.set(player, cape);
    } catch (Throwable ignored) {}
  }
}
