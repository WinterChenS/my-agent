package com.myagent.auth.captcha;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
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
 * <p>安全设计（人类容易判断、机器难匹配）：
 * <ul>
 *   <li><b>每次全随机</b>：背面用 {@link SecureRandom}，不存在固定模板/固定坐标/固定种子
 *   <li><b>形状不固定</b>：拼图轮廓 = 圆角矩形 + 随机数量/位置/大小的凸起或凹陷（随机边、随机半径），
 *       并且整体随机旋转 ±4°，所以"固定轮廓模板匹配"和"按坐标推算缺口"都失效
 *   <li><b>背景不留大片纯色</b>：渐变 + 随机曲线 + 不规则多边形 + 几何纹理 + 色块，位置/大小/颜色/透明度全随机，
 *       大幅降低"简单像素差分 / 直方图比对"的可用性
 *   <li><b>干扰分层</b>：低频纹理、半透明干扰线、曲线、局部纹理覆盖、低强度色彩扰动、噪点 —— 强度都从配置来，
 *       不靠"越花越安全"（花到人看不清反而降低安全：用户会反复重试或放弃）
 *   <li><b>不对程序暴露完整原始背景</b>：缺口在背景上是压暗挖空的，且落盘前对背景单独做一次
 *       随机噪声/色彩扰动，拼图块也各自加噪 —— 想靠"像素完全相等"做模板匹配会被噪声打穿
 * </ul>
 *
 * <p>几何上拼图块与缺口严格一致：两者由**同一个 {@link Area}** 变换得到，只是渲染位置不同。
 */
public final class SliderCaptchaImage {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 160;
    /** 拼图核心边长 */
    public static final int PIECE = 44;
    /** 拼图块向外留的边距（容纳凸起/旋转，避免被裁掉） */
    public static final int PAD = 14;
    /** 拼图块图片边长 */
    public static final int CANVAS = PIECE + PAD * 2;

    /** 缺口左边界范围：别太靠左（滑轨起点会挡住），也别贴右边 */
    public static final int MIN_X = 88;
    public static final int MAX_X = WIDTH - PIECE - PAD - 4;
    public static final int MIN_Y = PAD + 2;
    public static final int MAX_Y = HEIGHT - PIECE - PAD - 4;

    private SliderCaptchaImage() {}

    /**
     * 一次生成的结果。
     *
     * @param answerX 缺口左上角 x（原图像素，**只留在后端**）
     * @param pieceY  缺口左上角 y（本来就可见，发给前端省得它猜）
     * @param pad     拼图块图片相对缺口的向外边距（前端据此对齐）
     */
    public record Render(
            String background, String piece, int answerX, int pieceY, int pieceSize, int pad) {}

    /** 干扰强度：集中配置，便于调「安全 vs 可辨认」的平衡。 */
    public record Style(int blobs, int curves, int polygons, int lines, int noisePixels,
            int colorJitter, boolean texture) {}

    public static Style defaultStyle() {
        // 参数含义：色块 / 曲线 / 多边形 / 干扰线 / 噪点像素数 / 色彩扰动 / 几何纹理
        // 默认值刻意克制：干扰是为了打散"简单像素匹配"，不是为了让人看不清缺口
        return new Style(16, 5, 2, 6, WIDTH * HEIGHT / 22, 10, true);
    }

    public static Render render(SecureRandom random) {
        return render(random, defaultStyle());
    }

    public static Render render(SecureRandom random, Style style) {
        int answerX = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
        int answerY = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
        // ±4°：人几乎看不出来，但足以让"固定角度模板匹配"失效
        double angle = Math.toRadians(random.nextDouble() * 8 - 4);

        Area localShape = buildShape(random);
        Area globalShape = transformed(localShape, answerX, answerY, angle);

        BufferedImage background = paintBackground(random, style);
        // 先抠图（此时背景还是完整的），再在背景上挖缺口
        BufferedImage piece = cutPiece(background, globalShape, answerX, answerY, random);
        punchHole(background, globalShape, random);
        // 最后对背景单独加噪/轻微色彩扰动：让"拼图块 vs 背景像素完全相等"这种匹配失效
        finishBackground(background, random, style);

        return new Render(toDataUrl(background), toDataUrl(piece), answerX, answerY, CANVAS, PAD);
    }

    // ===================== 形状 =====================

    /**
     * 拼图轮廓：圆角矩形 + 随机个凸起/凹陷。
     *
     * <p>凸起长在哪条边、离边中点多远、半径多大、是凸还是凹，全部随机 →
     * 每次形状都不一样，无法用固定轮廓做模板。
     */
    static Area buildShape(SecureRandom random) {
        double inset = PAD * 0.6; // 让凸起留在画布内
        double size = PIECE;
        double radius = 6 + random.nextInt(8);
        Area area = new Area(new RoundRectangle2D.Double(inset, inset, size, size, radius, radius));

        int bumps = 1 + random.nextInt(3); // 1~3 个
        for (int i = 0; i < bumps; i++) {
            double side = random.nextInt(4);           // 0上 1右 2下 3左
            double knob = size * (0.18 + random.nextDouble() * 0.10); // 半径
            double along = 0.22 + random.nextDouble() * 0.56;         // 沿边位置比例
            double cx;
            double cy;
            if (side == 0) {
                cx = inset + size * along;
                cy = inset;
            } else if (side == 1) {
                cx = inset + size;
                cy = inset + size * along;
            } else if (side == 2) {
                cx = inset + size * along;
                cy = inset + size;
            } else {
                cx = inset;
                cy = inset + size * along;
            }
            Area knobArea = new Area(
                    new Ellipse2D.Double(cx - knob, cy - knob, knob * 2, knob * 2));
            if (random.nextBoolean()) {
                area.add(knobArea);          // 凸起
            } else {
                area.subtract(knobArea);     // 凹陷
            }
        }
        return area;
    }

    /** 局部坐标 → 缺口处（含旋转）的全局坐标。 */
    static Area transformed(Area local, int x, int y, double angle) {
        AffineTransform transform = new AffineTransform();
        transform.translate(x, y);
        transform.rotate(angle, (PIECE + PAD * 0.6) / 2.0, (PIECE + PAD * 0.6) / 2.0);
        return local.createTransformedArea(transform);
    }

    // ===================== 画背景 =====================

    private static BufferedImage paintBackground(SecureRandom random, Style style) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 1) 渐变打底（色相/明度随机，避免大片纯色）
            float hue = random.nextFloat();
            Color from = Color.getHSBColor(hue, 0.40f + random.nextFloat() * 0.35f,
                    0.80f + random.nextFloat() * 0.18f);
            Color to = Color.getHSBColor((hue + 0.15f + random.nextFloat() * 0.3f) % 1f,
                    0.35f + random.nextFloat() * 0.35f, 0.45f + random.nextFloat() * 0.35f);
            boolean diagonal = random.nextBoolean();
            g.setPaint(diagonal
                    ? new GradientPaint(0, 0, from, WIDTH, HEIGHT, to)
                    : new GradientPaint(WIDTH, 0, from, 0, HEIGHT, to));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 2) 不规则多边形色块
            for (int i = 0; i < style.polygons(); i++) {
                g.setColor(randomColor(random, 26 + random.nextInt(38)));
                g.fill(randomPolygon(random));
            }

            // 3) 半透明椭圆色块
            for (int i = 0; i < style.blobs(); i++) {
                int w = 24 + random.nextInt(96);
                int h = 18 + random.nextInt(76);
                g.setColor(randomColor(random, 34 + random.nextInt(46)));
                g.fill(new Ellipse2D.Double(random.nextInt(WIDTH) - w / 2.0,
                        random.nextInt(HEIGHT) - h / 2.0, w, h));
            }

            // 4) 几何纹理（条纹/网格/圆点 三选一，低透明度，打散大色块）
            if (style.texture()) {
                paintTexture(random, g);
            }

            // 5) 随机曲线（贝塞尔）
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < style.curves(); i++) {
                g.setColor(randomColor(random, 60 + random.nextInt(70)));
                g.setStroke(new BasicStroke(0.8f + random.nextFloat() * 2.2f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new CubicCurve2D.Double(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT)));
            }

            // 6) 半透明干扰直线
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < style.lines(); i++) {
                g.setColor(randomColor(random, 55 + random.nextInt(60)));
                g.setStroke(new BasicStroke(0.6f + random.nextFloat() * 1.6f));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            // 7) 噪点
            for (int i = 0; i < style.noisePixels(); i++) {
                int x = random.nextInt(WIDTH);
                int y = random.nextInt(HEIGHT);
                image.setRGB(x, y, image.getRGB(x, y) ^ (random.nextInt(0x141414)));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void paintTexture(SecureRandom random, Graphics2D g) {
        int kind = random.nextInt(3);
        int gap = 7 + random.nextInt(12);
        g.setColor(randomColor(random, 20 + random.nextInt(30)));
        g.setStroke(new BasicStroke(1f));
        if (kind == 0) { // 斜条纹
            for (int x = -HEIGHT; x < WIDTH; x += gap) {
                g.drawLine(x, 0, x + HEIGHT, HEIGHT);
            }
        } else if (kind == 1) { // 网格
            for (int x = 0; x < WIDTH; x += gap) {
                g.drawLine(x, 0, x, HEIGHT);
            }
            for (int y = 0; y < HEIGHT; y += gap) {
                g.drawLine(0, y, WIDTH, y);
            }
        } else { // 圆点
            for (int x = 0; x < WIDTH; x += gap) {
                for (int y = 0; y < HEIGHT; y += gap) {
                    g.fill(new Ellipse2D.Double(x, y, 1.6, 1.6));
                }
            }
        }
    }

    private static Path2D randomPolygon(SecureRandom random) {
        double cx = random.nextInt(WIDTH);
        double cy = random.nextInt(HEIGHT);
        int points = 3 + random.nextInt(4);
        double radius = 18 + random.nextInt(70);
        Path2D.Double path = new Path2D.Double();
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < points; i++) {
            double angle = start + i * (Math.PI * 2 / points) + (random.nextDouble() - 0.5) * 0.7;
            double r = radius * (0.55 + random.nextDouble() * 0.75);
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r * 0.7;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }

    // ===================== 拼图块 / 缺口 =====================

    /** 按全局形状把背景对应区域抠到画布上（几何上严格一致）。 */
    private static BufferedImage cutPiece(
            BufferedImage background, Area globalShape, int answerX, int answerY, SecureRandom random) {
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double dx = -answerX + PAD;
            double dy = -answerY + PAD;

            AffineTransform shift = AffineTransform.getTranslateInstance(dx, dy);
            Area localArea = globalShape.createTransformedArea(shift);

            g.setClip(localArea);
            g.drawImage(background, (int) Math.round(dx), (int) Math.round(dy), null);
            g.setClip(null);

            // 自身纹理：轻微噪点（让"像素完全相等"的模板匹配失效，人不敏感）
            g.setComposite(AlphaComposite.SrcAtop);
            for (int i = 0; i < CANVAS * CANVAS / 6; i++) {
                int x = random.nextInt(CANVAS);
                int y = random.nextInt(CANVAS);
                g.setColor(new Color(255, 255, 255, random.nextInt(26)));
                g.fillRect(x, y, 1, 1);
            }
            g.setComposite(AlphaComposite.SrcOver);

            // 边缘：亮描边 + 内侧暗描边 + 外发光，像一块实体拼图
            g.setColor(new Color(255, 255, 255, 235));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(localArea);
            g.setColor(new Color(0, 0, 0, 70));
            g.setStroke(new BasicStroke(1f));
            g.draw(localArea.createTransformedArea(
                    AffineTransform.getTranslateInstance(1, 1)));
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** 在背景上挖出缺口：压暗 + 白边 + 随机内侧阴影，让人一眼看出该放哪一行。 */
    private static void punchHole(BufferedImage background, Area globalShape, SecureRandom random) {
        Graphics2D g = background.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 115 + random.nextInt(35)));
            g.fill(globalShape);
            g.setColor(new Color(255, 255, 255, 195 + random.nextInt(50)));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(globalShape);
            // 内侧阴影：让缺口有深度，同时增加边缘纹理差异
            g.setColor(new Color(0, 0, 0, 45));
            g.setStroke(new BasicStroke(2.4f));
            g.draw(globalShape.createTransformedArea(
                    AffineTransform.getTranslateInstance(-1.2, -1.2)));
        } finally {
            g.dispose();
        }
    }

    /**
     * 背景收尾：低强度色彩扰动 + 随机噪点。
     *
     * <p>目的不是"更花"，而是破坏"拼图块像素和背景像素可以精确比对"这个前提。
     */
    private static void finishBackground(BufferedImage image, SecureRandom random, Style style) {
        int jitter = style.colorJitter();
        for (int i = 0; i < style.noisePixels() / 2; i++) {
            int x = random.nextInt(WIDTH);
            int y = random.nextInt(HEIGHT);
            int rgb = image.getRGB(x, y);
            int r = Math.min(255, Math.max(0, ((rgb >> 16) & 0xFF) + random.nextInt(jitter * 2 + 1) - jitter));
            int gg = Math.min(255, Math.max(0, ((rgb >> 8) & 0xFF) + random.nextInt(jitter * 2 + 1) - jitter));
            int b = Math.min(255, Math.max(0, (rgb & 0xFF) + random.nextInt(jitter * 2 + 1) - jitter));
            image.setRGB(x, y, (r << 16) | (gg << 8) | b);
        }
    }

    private static Color randomColor(SecureRandom random, int alpha) {
        return new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256),
                Math.min(255, alpha));
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
