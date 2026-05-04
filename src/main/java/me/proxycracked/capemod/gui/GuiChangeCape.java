package me.proxycracked.capemod.gui;

import me.proxycracked.capemod.cape.CapeManager;
import me.proxycracked.capemod.util.TextFormatting;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;

public class GuiChangeCape extends GuiScreen {
  private final GuiScreen previousScreen;
  private GuiTextField urlField;
  private GuiButton applyUrlButton;
  private GuiButton selectFileButton;
  private GuiButton clearButton;
  private GuiButton backButton;
  private volatile String status = "&7Paste a cape URL or pick a PNG file.&r";

  public GuiChangeCape(GuiScreen previousScreen) {
    this.previousScreen = previousScreen;
  }

  @Override
  public void initGui() {
    Keyboard.enableRepeatEvents(true);
    buttonList.clear();
    int cx = width / 2;
    int cy = height / 2;

    urlField = new GuiTextField(0, fontRendererObj, cx - 150, cy - 20, 300, 20);
    urlField.setMaxStringLength(32767);
    urlField.setFocused(true);

    buttonList.add(applyUrlButton   = new GuiButton(0, cx - 150, cy + 10,  145, 20, "Apply URL"));
    buttonList.add(selectFileButton = new GuiButton(1, cx + 5,   cy + 10,  145, 20, "Pick PNG/APNG..."));
    buttonList.add(clearButton      = new GuiButton(2, cx - 150, cy + 34,  145, 20, "Clear Cape"));
    buttonList.add(backButton       = new GuiButton(3, cx + 5,   cy + 34,  145, 20, "Back"));
  }

  @Override
  public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }

  @Override
  public void drawScreen(int mouseX, int mouseY, float partialTicks) {
    drawDefaultBackground();
    drawCenteredString(fontRendererObj, "Change Cape (client-side only)",
      width / 2, height / 2 - 60, 0xFFFFFF);
    drawCenteredString(fontRendererObj, TextFormatting.translate(CapeManager.describe()),
      width / 2, height / 2 - 46, -1);
    drawString(fontRendererObj, "Cape URL (PNG/APNG):", width / 2 - 150, height / 2 - 34, 0xAAAAAA);
    if (status != null) {
      drawCenteredString(fontRendererObj, TextFormatting.translate(status),
        width / 2, height / 2 + 64, -1);
    }
    drawCenteredString(fontRendererObj,
      TextFormatting.translate("&8Cape sheet: 22:17 (e.g. 352x272) or 2:1 (e.g. 1024x512). PNG/APNG only.&r"),
      width / 2, height / 2 + 78, -1);
    drawCenteredString(fontRendererObj,
      TextFormatting.translate("&8Need a cape? Use the cape creator at &fhttps://sc6the.github.io/cape-creator/&r"),
      width / 2, height / 2 + 90, -1);
    urlField.drawTextBox();
    super.drawScreen(mouseX, mouseY, partialTicks);

    drawPreview(mouseX, mouseY);
  }

  // Renders the active cape as a flat 22:17 swatch from the cape texture
  // sheet. Simpler and more reliable than a 3D player preview — no lighting,
  // no entity state, no lightmap interactions to fight.
  private void drawPreview(int mouseX, int mouseY) {
    int pX = width / 2 + 180;
    int pY = height / 2 + 60;

    int boxX = pX - 35;
    int boxY = pY - 100;
    int boxW = 70;
    int boxH = 110;
    net.minecraft.client.gui.Gui.drawRect(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, 0x80000000);

    if (CapeManager.isActive()) {
      net.minecraft.util.ResourceLocation rl = CapeManager.getCape();
      if (rl != null) {
        mc.getTextureManager().bindTexture(rl);
        // Front of cape on the 64x32 sheet is at pixels (1,1)..(11,17):
        // 10 wide, 16 tall. Back lives at (12,1)..(22,17) and top strip
        // sits above. Sample only the front quad.
        float u1 = 1F  / 64F, v1 = 1F  / 32F;
        float u2 = 11F / 64F, v2 = 17F / 32F;
        int sw = boxW - 8;
        int sh = (int) (sw * 16.0 / 10.0);
        if (sh > boxH - 8) {
          sh = boxH - 8;
          sw = (int) (sh * 10.0 / 16.0);
        }
        int sx = boxX + (boxW - sw) / 2;
        int sy = boxY + (boxH - sh) / 2;
        drawTexturedRect(sx, sy, sx + sw, sy + sh, u1, v1, u2, v2);
      }
    } else {
      drawCenteredString(fontRendererObj, TextFormatting.translate("&8(no cape)"),
        pX, boxY + boxH / 2 - 4, -1);
    }
  }

  private void drawTexturedRect(int x1, int y1, int x2, int y2, float u1, float v1, float u2, float v2) {
    net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
    net.minecraft.client.renderer.WorldRenderer wr = t.getWorldRenderer();
    net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
    wr.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
    wr.pos(x1, y2, 0).tex(u1, v2).endVertex();
    wr.pos(x2, y2, 0).tex(u2, v2).endVertex();
    wr.pos(x2, y1, 0).tex(u2, v1).endVertex();
    wr.pos(x1, y1, 0).tex(u1, v1).endVertex();
    t.draw();
  }

  @Override
  protected void keyTyped(char typedChar, int keyCode) {
    urlField.textboxKeyTyped(typedChar, keyCode);
    if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(previousScreen);
  }

  @Override
  protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
    try { super.mouseClicked(mouseX, mouseY, mouseButton); } catch (Exception ignored) {}
    urlField.mouseClicked(mouseX, mouseY, mouseButton);
  }

  @Override
  protected void actionPerformed(GuiButton button) {
    if (button == null || !button.enabled) return;
    switch (button.id) {
      case 0: applyUrl();    break;
      case 1: pickFile();    break;
      case 2: doClear();     break;
      case 3: mc.displayGuiScreen(previousScreen); break;
    }
  }

  private void applyUrl() {
    final String url = urlField.getText().trim();
    if (StringUtils.isBlank(url)) { status = "&cEnter a cape URL.&r"; return; }
    if (!(url.regionMatches(true, 0, "http://", 0, 7)
       || url.regionMatches(true, 0, "https://", 0, 8))) {
      status = "&cURL must start with http:// or https://&r"; return;
    }
    status = "&7Downloading cape...&r";
    new Thread(() -> {
      try {
        CapeManager.loadFromUrl(url);
        status = "&aCape applied!&r";
      } catch (Exception e) {
        status = "&cFailed: " + e.getMessage() + "&r";
      }
    }, "CapeMod-Cape").start();
  }

  private void pickFile() {
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
    SwingUtilities.invokeLater(() -> {
      FileDialog dialog = new FileDialog((Frame) null, "Select Cape (PNG / APNG)", FileDialog.LOAD);
      dialog.setDirectory(System.getProperty("user.home") + File.separator + "Downloads");
      dialog.setFile("*.png;*.apng");
      dialog.setModal(true);
      status = "&7File picker opened in background.&r";
      dialog.setVisible(true);
      String name = dialog.getFile();
      if (name == null) { status = "&eFile selection canceled.&r"; return; }
      File file = new File(dialog.getDirectory(), name);
      if (!file.exists()) { status = "&cSelected file does not exist.&r"; return; }
      status = "&7Loading cape...&r";
      new Thread(() -> {
        try {
          CapeManager.loadFromFile(file);
          status = "&aCape applied!&r";
        } catch (Exception e) {
          status = "&cFailed: " + e.getMessage() + "&r";
        }
      }, "CapeMod-Cape").start();
    });
  }

  private void doClear() {
    CapeManager.clear();
    status = "&aCustom cape cleared.&r";
  }
}
