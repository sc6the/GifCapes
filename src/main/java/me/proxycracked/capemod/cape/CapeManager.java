package me.proxycracked.capemod.cape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.proxycracked.capemod.util.HttpUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// Client-side custom cape. Mojang has no API for custom cape upload, so we
// override the local player's NetworkPlayerInfo cape texture every tick (see
// Events.applyCustomCape). Cape is visible only locally — other players see
// whatever Mojang has on file.
//
// Supports both static PNG and animated APNG. Animated capes are uploaded as
// one DynamicTexture per frame; getCape() picks the current frame based on
// elapsed wall-clock time vs. per-frame delays.
public final class CapeManager {
  public static final String SOURCE_NONE = "NONE";
  public static final String SOURCE_URL  = "URL";
  public static final String SOURCE_FILE = "FILE";

  // HD cape body is 352x272. Vanilla 1.8.9 cape rendering samples
  // UV (0..22/64, 0..17/32), so the canvas must be 2:1 with the cape body in
  // the top-left: 352*64/22=1024, 272*32/17=512.
  public static final int CAPE_W   = 352;
  public static final int CAPE_H   = 272;
  public static final int CANVAS_W = 1024;
  public static final int CANVAS_H = 512;
  public static final int MAX_K    = 1;
  public static final int MAX_FRAMES = 60;

  private static final Minecraft mc = Minecraft.getMinecraft();
  private static final File metaFile  = new File(mc.mcDataDir, "capemod_cape.json");
  private static final File imageFile = new File(mc.mcDataDir, "capemod_cape.png");

  private static final AtomicReference<ResourceLocation> active = new AtomicReference<>();
  private static volatile ResourceLocation[] animFrames;
  private static volatile int[] animDelays;
  private static volatile int animTotalMs;
  private static volatile long animStartMs;

  private static volatile String source = SOURCE_NONE;
  private static volatile String origin = "";
  private static volatile int frameCount = 0;
  private static volatile boolean enabled = true;
  // Set when state changes such that NPI must be re-synced on the next tick
  // (e.g. cape cleared, or toggled off). Tick handler consumes it once so
  // the cape ResourceLocation in NetworkPlayerInfo is nulled out and the
  // renderer doesn't keep binding our just-deleted DynamicTexture
  // (which renders as MC's missing-texture purple/black).
  private static volatile boolean pendingStrip;

  private CapeManager() {}

  public static ResourceLocation getCape() {
    ResourceLocation[] frames = animFrames;
    if (frames != null && frames.length > 0) {
      int total = animTotalMs;
      if (total <= 0) return frames[0];
      long t = (System.currentTimeMillis() - animStartMs) % total;
      long acc = 0;
      int[] delays = animDelays;
      for (int i = 0; i < frames.length; i++) {
        acc += delays[i];
        if (t < acc) return frames[i];
      }
      return frames[frames.length - 1];
    }
    return active.get();
  }

  public static String  getSource()   { return source; }
  public static String  getOrigin()   { return origin; }
  public static boolean isAnimated()  { return animFrames != null && animFrames.length > 1; }
  public static boolean isActive()    { return active.get() != null || (animFrames != null && animFrames.length > 0); }
  public static int     getFrameCount() { return frameCount; }
  public static boolean isEnabled()   { return enabled; }

  public static boolean consumePendingStrip() {
    if (!pendingStrip) return false;
    pendingStrip = false;
    return true;
  }

  public static void setEnabled(boolean on) {
    if (enabled == on) return;
    enabled = on;
    if (!on) pendingStrip = true;
    saveMeta();
  }

  public static String describe() {
    if (!isActive()) return "&7No cape.&r";
    String from = truncate(origin, 42);
    if (from.length() > 0) return "&a" + from + "&r";
    return "&aApplied&r";
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  public static void clear() {
    source = SOURCE_NONE;
    origin = "";
    frameCount = 0;
    // Release any GL textures we own and null the active refs so render
    // picks "no cape" immediately instead of leaking the last upload.
    ResourceLocation current = active.getAndSet(null);
    if (current != null) {
      try { mc.getTextureManager().deleteTexture(current); } catch (Throwable ignored) {}
    }
    releaseAnimated();
    if (imageFile.exists()) imageFile.delete();
    if (metaFile.exists())  metaFile.delete();
    // NPI.locationCape still points at our now-deleted ResourceLocation;
    // the next bind would resolve to MC's missing-texture (purple/black).
    // Flag a tick-time strip so Events nulls it out before the next frame.
    pendingStrip = true;
  }

  public static void initFromDisk() {
    try {
      if (!metaFile.exists()) return;
      try (InputStream in = new FileInputStream(metaFile);
           java.io.Reader r = new java.io.InputStreamReader(in)) {
        JsonObject obj = new JsonParser().parse(r).getAsJsonObject();
        source  = obj.has("source")  ? obj.get("source").getAsString()  : SOURCE_NONE;
        origin  = obj.has("origin")  ? obj.get("origin").getAsString()  : "";
        enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
      }
      if (!imageFile.exists()) return;
      byte[] bytes = Files.readAllBytes(imageFile.toPath());
      apply(bytes, source, origin, false);
    } catch (Exception e) {
      System.err.println("[CapeMod] Failed to load saved cape: " + e.getMessage());
    }
  }

  public static void loadFromUrl(String url) throws Exception {
    if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("Empty URL");
    byte[] bytes = HttpUtils.getBytes(url.trim());
    apply(bytes, SOURCE_URL, url.trim(), true);
  }

  public static void loadFromFile(File file) throws Exception {
    if (file == null || !file.exists()) throw new IllegalArgumentException("File not found");
    String name = file.getName().toLowerCase();
    if (name.endsWith(".webp")) {
      throw new Exception("WebP isn't supported — convert to PNG/APNG first");
    }
    byte[] bytes = Files.readAllBytes(file.toPath());
    apply(bytes, SOURCE_FILE, file.getAbsolutePath(), true);
  }

  private static void apply(byte[] bytes, String src, String orig, boolean persist) throws Exception {
    List<ApngDecoder.Frame> frames;
    try {
      frames = ApngDecoder.decode(bytes);
    } catch (Exception e) {
      throw new Exception("Couldn't decode image: " + e.getMessage());
    }
    if (frames.isEmpty()) throw new Exception("No frames found");
    if (frames.size() > MAX_FRAMES) {
      throw new Exception("Too many frames (" + frames.size() + " > " + MAX_FRAMES + ")");
    }

    if (frames.size() == 1) {
      BufferedImage normalized = normalize(frames.get(0).image);
      if (persist) persistBytes(bytes, src, orig);
      else { source = src; origin = orig; }
      frameCount = 1;
      registerStaticOnMainThread(normalized);
    } else {
      BufferedImage[] imgs = new BufferedImage[frames.size()];
      int[] delays = new int[frames.size()];
      for (int i = 0; i < frames.size(); i++) {
        imgs[i] = normalize(frames.get(i).image);
        delays[i] = frames.get(i).delayMs;
      }
      if (persist) persistBytes(bytes, src, orig);
      else { source = src; origin = orig; }
      frameCount = frames.size();
      registerAnimatedOnMainThread(imgs, delays);
    }
  }

  private static void persistBytes(byte[] bytes, String src, String orig) {
    try {
      Files.write(imageFile.toPath(), bytes);
      source = src;
      origin = orig;
      saveMeta();
    } catch (Exception e) {
      System.err.println("[CapeMod] Failed to persist cape: " + e.getMessage());
    }
  }

  private static void saveMeta() {
    try (PrintWriter w = new PrintWriter(new FileWriter(metaFile))) {
      JsonObject o = new JsonObject();
      o.addProperty("source", source);
      o.addProperty("origin", origin);
      o.addProperty("enabled", enabled);
      w.println(o.toString());
    } catch (Exception e) {
      System.err.println("[CapeMod] Failed to save cape meta: " + e.getMessage());
    }
  }

  private static void registerStaticOnMainThread(final BufferedImage img) {
    mc.addScheduledTask(() -> {
      try {
        releaseAnimated();
        DynamicTexture tex = new DynamicTexture(img);
        ResourceLocation rl = mc.getTextureManager().getDynamicTextureLocation(
          "capemod_cape_" + System.currentTimeMillis(), tex);
        active.set(rl);
      } catch (Exception e) {
        System.err.println("[CapeMod] Cape upload failed: " + e.getMessage());
      }
    });
  }

  private static void registerAnimatedOnMainThread(final BufferedImage[] imgs, final int[] delays) {
    mc.addScheduledTask(() -> {
      try {
        releaseAnimated();
        active.set(null);
        ResourceLocation[] rls = new ResourceLocation[imgs.length];
        long stamp = System.currentTimeMillis();
        int total = 0;
        for (int i = 0; i < imgs.length; i++) {
          DynamicTexture tex = new DynamicTexture(imgs[i]);
          rls[i] = mc.getTextureManager().getDynamicTextureLocation(
            "capemod_cape_anim_" + stamp + "_" + i, tex);
          total += delays[i];
        }
        animFrames = rls;
        animDelays = delays;
        animTotalMs = total;
        animStartMs = System.currentTimeMillis();
      } catch (Exception e) {
        System.err.println("[CapeMod] Animated cape upload failed: " + e.getMessage());
      }
    });
  }

  private static void releaseAnimated() {
    ResourceLocation[] frames = animFrames;
    animFrames = null;
    animDelays = null;
    animTotalMs = 0;
    if (frames != null) {
      for (ResourceLocation rl : frames) {
        try { mc.getTextureManager().deleteTexture(rl); } catch (Throwable ignored) {}
      }
    }
  }

  private static BufferedImage normalize(BufferedImage src) {
    int sw = src.getWidth();
    int sh = src.getHeight();
    if (sw <= 0 || sh <= 0) throw new IllegalArgumentException("Empty image");

    boolean is22x17 = (sw * 17 == sh * 22);
    boolean is2x1   = (sw == sh * 2);

    int k;
    if (is22x17)      k = clampK(Math.round((float) sw / CAPE_W));
    else if (is2x1)   k = clampK(Math.round((float) sw / CANVAS_W));
    else              k = 1;

    int canvasW = CANVAS_W * k;
    int canvasH = CANVAS_H * k;
    int bodyW   = CAPE_W   * k;
    int bodyH   = CAPE_H   * k;

    BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

    if (is22x17) {
      g.drawImage(src, 0, 0, bodyW, bodyH, null);
    } else if (is2x1) {
      g.drawImage(src, 0, 0, canvasW, canvasH, null);
    } else {
      double scale = Math.min((double) bodyW / sw, (double) bodyH / sh);
      int drawW = Math.max(1, (int) Math.round(sw * scale));
      int drawH = Math.max(1, (int) Math.round(sh * scale));
      g.drawImage(src, 0, 0, drawW, drawH, null);
    }
    g.dispose();
    return out;
  }

  private static int clampK(int k) {
    if (k < 1) return 1;
    if (k > MAX_K) return MAX_K;
    return k;
  }
}
