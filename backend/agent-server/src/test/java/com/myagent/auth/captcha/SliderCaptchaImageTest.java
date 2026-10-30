package com.myagent.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图片生成的随机性与几何一致性。
 *
 * <p>这些测试守的是"每次都不一样"这件事：固定坐标、固定轮廓、固定模板一旦出现就会被它抓到。
 */
class SliderCaptchaImageTest {

    private static final String PREFIX = "data:image/png;base64,";

    @Test
    @DisplayName("图片能解码，尺寸符合约定；拼图块带透明边距")
    void rendersDecodableImages() throws Exception {
        SliderCaptchaImage.Render render = SliderCaptchaImage.render(new SecureRandom());

        assertThat(render.pieceSize()).isEqualTo(SliderCaptchaImage.CANVAS);
        assertThat(render.pad()).isEqualTo(SliderCaptchaImage.PAD);

        var background = decode(render.background());
        var piece = decode(render.piece());
        assertThat(background.getWidth()).isEqualTo(SliderCaptchaImage.WIDTH);
        assertThat(background.getHeight()).isEqualTo(SliderCaptchaImage.HEIGHT);
        assertThat(piece.getWidth()).isEqualTo(SliderCaptchaImage.CANVAS);
        assertThat(piece.getHeight()).isEqualTo(SliderCaptchaImage.CANVAS);
        // 拼图块是带 alpha 的（缺口外是透明的，这样才能"对得上缺口形状"）
        assertThat(piece.getColorModel().hasAlpha()).isTrue();
        // 角落应当是透明的
        assertThat((piece.getRGB(0, 0) >>> 24)).isZero();
    }

    @Test
    @DisplayName("缺口位置随机：几十次生成里位置分布足够散，且始终落在合法区间")
    void answerPositionIsRandom() {
        Set<Integer> xs = new HashSet<>();
        Set<Integer> ys = new HashSet<>();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 40; i++) {
            SliderCaptchaImage.Render render = SliderCaptchaImage.render(random);
            assertThat(render.answerX()).isBetween(SliderCaptchaImage.MIN_X, SliderCaptchaImage.MAX_X);
            assertThat(render.pieceY()).isBetween(SliderCaptchaImage.MIN_Y, SliderCaptchaImage.MAX_Y);
            xs.add(render.answerX());
            ys.add(render.pieceY());
        }
        assertThat(xs.size()).isGreaterThan(20);
        assertThat(ys.size()).isGreaterThan(20);
    }

    @Test
    @DisplayName("拼图形状随机：凸起/凹陷的数量、位置随机，轮廓不能是同一个模子")
    void shapeIsRandom() {
        SecureRandom random = new SecureRandom();
        Set<String> shapes = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            Area area = SliderCaptchaImage.buildShape(random);
            Rectangle2D bounds = area.getBounds2D();
            // 用"轮廓边界盒 + 面积（四边形近似）"做一个形状指纹
            String fingerprint = String.format("%.1f/%.1f/%.1f/%.1f", bounds.getX(), bounds.getY(),
                    bounds.getWidth(), bounds.getHeight());
            shapes.add(fingerprint);
        }
        assertThat(shapes.size()).isGreaterThan(10);
    }

    @Test
    @DisplayName("两次生成的内容完全不同（没有固定模板 / 固定种子）")
    void imagesDifferBetweenRenders() {
        SecureRandom random = new SecureRandom();
        SliderCaptchaImage.Render first = SliderCaptchaImage.render(random);
        SliderCaptchaImage.Render second = SliderCaptchaImage.render(random);

        assertThat(first.background()).isNotEqualTo(second.background());
        assertThat(first.piece()).isNotEqualTo(second.piece());
    }

    @Test
    @DisplayName("干扰强度可配：把噪点调低也能正常出图（安全不能靠'越花越安全'）")
    void styleIsConfigurable() throws Exception {
        SliderCaptchaImage.Style quiet = new SliderCaptchaImage.Style(4, 2, 1, 2, 200, 4, false);
        SliderCaptchaImage.Render render = SliderCaptchaImage.render(new SecureRandom(), quiet);

        var background = decode(render.background());
        assertThat(background.getWidth()).isEqualTo(SliderCaptchaImage.WIDTH);
    }

    private static java.awt.image.BufferedImage decode(String dataUrl) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(PREFIX.length()));
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
