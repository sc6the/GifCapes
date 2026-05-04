package me.proxycracked.capemod.cape;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

// Tiny APNG decoder. Reads acTL/fcTL/fdAT chunks and rebuilds each animation
// frame as a self-contained synthetic PNG (IHDR-with-frame-dims + carried-over
// PLTE/tRNS + the frame's IDAT/fdAT data + IEND), then hands it to ImageIO.
//
// Composite logic implements the APNG dispose/blend state machine so frames
// using dispose=NONE/BACKGROUND/PREVIOUS and blend=SOURCE/OVER all render
// correctly. Sub-region frames (x_offset/y_offset != 0) are honored.
//
// Returns a single-frame list for plain (non-animated) PNGs.
public final class ApngDecoder {
  private ApngDecoder() {}

  public static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  public static final class Frame {
    public final BufferedImage image;
    public final int delayMs;
    public Frame(BufferedImage image, int delayMs) {
      this.image = image;
      this.delayMs = delayMs;
    }
  }

  private static final class Chunk {
    final String type;
    final byte[] data;
    Chunk(String type, byte[] data) { this.type = type; this.data = data; }
  }

  public static List<Frame> decode(byte[] bytes) throws IOException {
    if (bytes.length < 8) throw new IOException("File too short");
    for (int i = 0; i < 8; i++) {
      if (bytes[i] != PNG_SIG[i]) throw new IOException("Not a PNG");
    }

    List<Chunk> chunks = readChunks(bytes);
    Chunk ihdr = findFirst(chunks, "IHDR");
    if (ihdr == null) throw new IOException("Missing IHDR");
    int canvasW = readInt(ihdr.data, 0);
    int canvasH = readInt(ihdr.data, 4);

    Chunk plte = findFirst(chunks, "PLTE");
    Chunk trns = findFirst(chunks, "tRNS");
    Chunk actl = findFirst(chunks, "acTL");

    if (actl == null) {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
      if (img == null) throw new IOException("Couldn't decode PNG");
      List<Frame> out = new ArrayList<>();
      out.add(new Frame(img, 0));
      return out;
    }

    List<Frame> frames = new ArrayList<>();
    BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
    BufferedImage previousCanvas = null;

    FcTL pending = null;
    List<byte[]> pendingIdats = new ArrayList<>();
    boolean firstFrameUsesIdat = false;

    for (Chunk c : chunks) {
      if (c.type.equals("fcTL")) {
        if (pending != null) {
          frames.add(buildFrame(canvas, previousCanvas, pending, pendingIdats,
            ihdr, plte, trns, firstFrameUsesIdat));
          previousCanvas = snapshotForDispose(canvas, previousCanvas, pending);
          pendingIdats.clear();
        }
        pending = parseFcTL(c.data);
        firstFrameUsesIdat = frames.isEmpty();
      } else if (pending != null && (c.type.equals("IDAT") || c.type.equals("fdAT"))) {
        if (c.type.equals("fdAT")) {
          byte[] stripped = new byte[c.data.length - 4];
          System.arraycopy(c.data, 4, stripped, 0, stripped.length);
          pendingIdats.add(stripped);
        } else {
          pendingIdats.add(c.data);
        }
      } else if (c.type.equals("IEND")) {
        break;
      }
    }
    if (pending != null) {
      frames.add(buildFrame(canvas, previousCanvas, pending, pendingIdats,
        ihdr, plte, trns, firstFrameUsesIdat));
    }
    return frames;
  }

  private static Frame buildFrame(BufferedImage canvas, BufferedImage prev,
                                  FcTL fc, List<byte[]> idats,
                                  Chunk origIhdr, Chunk plte, Chunk trns,
                                  boolean isFirstFrameUsingIdat) throws IOException {
    BufferedImage frameImg = decodeSubFrame(fc, idats, origIhdr, plte, trns);

    Graphics2D g = canvas.createGraphics();
    if (fc.blendOp == 1) {
      g.setComposite(AlphaComposite.SrcOver);
    } else {
      g.setComposite(AlphaComposite.Src);
    }
    g.drawImage(frameImg, fc.xOffset, fc.yOffset, null);
    g.dispose();

    BufferedImage snapshot = deepCopy(canvas);

    if (fc.disposeOp == 1) {
      Graphics2D dg = canvas.createGraphics();
      dg.setComposite(AlphaComposite.Clear);
      dg.fillRect(fc.xOffset, fc.yOffset, fc.width, fc.height);
      dg.dispose();
    } else if (fc.disposeOp == 2) {
      if (prev != null) {
        Graphics2D dg = canvas.createGraphics();
        dg.setComposite(AlphaComposite.Src);
        dg.drawImage(prev, 0, 0, null);
        dg.dispose();
      }
    }

    int num = fc.delayNum;
    int den = fc.delayDen == 0 ? 100 : fc.delayDen;
    int delayMs = (int) ((long) num * 1000L / den);
    if (delayMs < 10) delayMs = 10;
    return new Frame(snapshot, delayMs);
  }

  private static BufferedImage snapshotForDispose(BufferedImage canvas, BufferedImage prev, FcTL fc) {
    if (fc.disposeOp == 2) return prev;
    return deepCopy(canvas);
  }

  private static BufferedImage decodeSubFrame(FcTL fc, List<byte[]> idats,
                                              Chunk origIhdr, Chunk plte, Chunk trns) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    bos.write(PNG_SIG, 0, PNG_SIG.length);

    byte[] frameIhdr = origIhdr.data.clone();
    writeInt(frameIhdr, 0, fc.width);
    writeInt(frameIhdr, 4, fc.height);
    writeChunk(bos, "IHDR", frameIhdr);

    if (plte != null) writeChunk(bos, "PLTE", plte.data);
    if (trns != null) writeChunk(bos, "tRNS", trns.data);

    for (byte[] d : idats) writeChunk(bos, "IDAT", d);
    writeChunk(bos, "IEND", new byte[0]);

    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
    if (img == null) throw new IOException("Couldn't decode APNG sub-frame");
    if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
      BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = argb.createGraphics();
      g.drawImage(img, 0, 0, null);
      g.dispose();
      img = argb;
    }
    return img;
  }

  private static List<Chunk> readChunks(byte[] bytes) throws IOException {
    List<Chunk> out = new ArrayList<>();
    int i = 8;
    while (i + 8 <= bytes.length) {
      int len = readInt(bytes, i); i += 4;
      if (len < 0 || i + 4 + len + 4 > bytes.length) break;
      String type = new String(bytes, i, 4, "ISO-8859-1");
      i += 4;
      byte[] data = new byte[len];
      System.arraycopy(bytes, i, data, 0, len);
      i += len;
      i += 4;
      out.add(new Chunk(type, data));
      if (type.equals("IEND")) break;
    }
    return out;
  }

  private static Chunk findFirst(List<Chunk> chunks, String type) {
    for (Chunk c : chunks) if (c.type.equals(type)) return c;
    return null;
  }

  private static int readInt(byte[] b, int off) {
    return ((b[off] & 0xFF) << 24)
         | ((b[off + 1] & 0xFF) << 16)
         | ((b[off + 2] & 0xFF) << 8)
         |  (b[off + 3] & 0xFF);
  }

  private static int readShort(byte[] b, int off) {
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }

  private static void writeInt(byte[] b, int off, int v) {
    b[off]     = (byte) ((v >>> 24) & 0xFF);
    b[off + 1] = (byte) ((v >>> 16) & 0xFF);
    b[off + 2] = (byte) ((v >>> 8)  & 0xFF);
    b[off + 3] = (byte) ( v         & 0xFF);
  }

  private static void writeChunk(ByteArrayOutputStream bos, String type, byte[] data) throws IOException {
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeInt(data.length);
    byte[] typeBytes = type.getBytes("ISO-8859-1");
    dos.write(typeBytes);
    dos.write(data);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    dos.writeInt((int) crc.getValue());
  }

  private static BufferedImage deepCopy(BufferedImage src) {
    BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setComposite(AlphaComposite.Src);
    g.drawImage(src, 0, 0, null);
    g.dispose();
    return out;
  }

  private static final class FcTL {
    int width, height, xOffset, yOffset;
    int delayNum, delayDen;
    int disposeOp, blendOp;
  }

  private static FcTL parseFcTL(byte[] d) {
    FcTL f = new FcTL();
    f.width    = readInt(d, 4);
    f.height   = readInt(d, 8);
    f.xOffset  = readInt(d, 12);
    f.yOffset  = readInt(d, 16);
    f.delayNum = readShort(d, 20);
    f.delayDen = readShort(d, 22);
    f.disposeOp = d[24] & 0xFF;
    f.blendOp   = d[25] & 0xFF;
    return f;
  }
}
