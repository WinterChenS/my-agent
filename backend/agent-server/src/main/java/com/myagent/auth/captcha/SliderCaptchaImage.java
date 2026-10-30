package com.myagent.auth.captcha;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * 滑动验证的图片生成：一张背景图 + 一块从背景上"抠下来"的拼图。
 *
 * <p>为什么自己画而不引第三方组件：内网 http 非安全上下文里 canvas 指纹/加密类组件全都不可用，
 * 而且多数开源的滑动验证是前端算答案（等于没保护）。这里的答案只存在后端，前端只看得到图。
 *
 * <p>图是程序生成的（渐变 + 随机色块 + 随机线条 + 噪点），不依赖任何网络图片，
 * 也就不会因为内网无外网而"验证图加载不出来"。
 */
public final class SliderCaptchaImage {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 160;
    public static final int PIECE = 48;
    /** 拼图左边界取值范围：别太靠左（滑轨起点会挡住），也别贴右边 */
    public static final int MIN_X = 96;
    public static final int MAX_X = WIDTH - PIECE - 6;
    public static final int MIN_Y = 8;
    public static final int MAX_Y = HEIGHT - PIECE - 6;

    private SliderCaptchaImage() {}

    /** 一次生成的结果：两张图的 data URL + 真实位置（真实位置只留在后端）。 */
    public record Render(String background, String piece, int answerX, int pieceY) {}

    public static Render render(SecureRandom random) {
        int answerX = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
        int pieceY = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);

        BufferedImage background = paintBackground(random);
        BufferedImage piece = cutPiece(background, answerX, pieceY);
        punchHole(background, answerX, pieceY);

        return new Render(toDataUrl(background), toDataUrl(piece), answerX, pieceY);
    }

    /** 背景：随机色相的斜向渐变 + 一堆半透明色块 + 干扰线 + 噪点。 */
    private static BufferedImage paintBackground(SecureRandom random) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hue = random.nextFloat();
            Color from = Color.getHSBColor(hue, 0.45f + random.nextFloat() * 0.3f, 0.85f);
            Color to = Color.getHSBColor((hue + 0.18f + random.nextFloat() * 0.2f) % 1f,
                    0.35f + random.nextFloat() * 0.35f, 0.55f + random.nextFloat() * 0.3f);
            g.setPaint(new GradientPaint(0, 0, from, WIDTH, HEIGHT, to));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 半透明色块：让"哪块是拼图缺口"不好靠像素统计猜出来
            for (int i = 0; i < 26; i++) {
                int w = 24 + random.nextInt(90);
                int h = 18 + random.nextInt(70);
                g.setColor(new Color(random.nextInt(256), random.nextInt(256),
                        random.nextInt(256), 40 + random.nextInt(50)));
                g.fill(new Ellipse2D.Double(random.nextInt(WIDTH) - w / 2.0,
                        random.nextInt(HEIGHT) - h / 2.0, w, h));
            }
            // 干扰线
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < 18; i++) {
                g.setColor(new Color(random.nextInt(256), random.nextInt(256),
                        random.nextInt(256), 60 + random.nextInt(60)));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            // 噪点
            for (int i = 0; i < WIDTH * HEIGHT / 12; i++) {
                int x = random.nextInt(WIDTH);
                int y = random.nextInt(HEIGHT);
                image.setRGB(x, y, image.getRGB(x, y) ^ (random.nextInt(0x101010)));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 从背景上抠下对应区域，圆角裁切 + 描边，做成"拼图块"。 */
    private static BufferedImage cutPiece(BufferedImage background, int x, int y) {
        BufferedImage piece = new BufferedImage(PIECE, PIECE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = piece.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, PIECE - 1, PIECE - 1, 14, 14);
            g.setClip(shape);
            g.drawImage(background.getSubimage(x, y, PIECE, PIECE), 0, 0, null);
            g.setClip(null);
            // 白色描边 + 内阴影，看起来像一块实体拼图
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(255, 255, 255, 230));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new RoundRectangle2D.Double(0.8, 0.8, PIECE - 2.6, PIECE - 2.6, 14, 14));
            g.setColor(new Color(0, 0, 0, 60));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(1.8, 1.8, PIECE - 4.6, PIECE - 4.6, 12, 12));
        } finally {
            g.dispose();
        }
        return piece;
    }

    /** 在背景上挖出缺口（压暗 + 白边），让人一眼看出拼图该放哪一行。 */
    private static void punchHole(BufferedImage background, int x, int y) {
        Graphics2D g = background.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, PIECE - 1, PIECE - 1, 14, 14);
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(shape);
            g.setColor(new Color(255, 255, 255, 150));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(shape);
        } finally {
            g.dispose();
        }
    }

    private static String toDataUrl(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("滑动验证图片生成失败", e);
        }
    }
}
