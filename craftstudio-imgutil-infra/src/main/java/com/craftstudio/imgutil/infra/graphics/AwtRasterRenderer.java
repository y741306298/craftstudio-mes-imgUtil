package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.RasterSpec;
import com.craftstudio.imgutil.domain.graphics.RenderResult;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.Locale;

/** Pure rendering extracted from MES border/label/splice implementations; no order or database dependency. */
@Component
public class AwtRasterRenderer {
    private final RenderLimits limits;
    private final GraphicsProperties properties;
    public AwtRasterRenderer(RenderLimits limits, GraphicsProperties properties) {
        this.limits = limits; this.properties = properties;
        ImageIO.setUseCache(false);
    }

    public void validate(RasterSpec spec) {
        if (spec.kind() == null) throw new IllegalArgumentException("缺少 raster.kind");
        RenderLimits.positive(spec.widthMm(), "widthMm"); RenderLimits.positive(spec.heightMm(), "heightMm");
        if (spec.dpi() < 1 || spec.dpi() > properties.maxDpi()) throw new IllegalArgumentException("dpi 超出范围");
        spec.margins().validate();
        color(spec.foreground()); color(spec.background());
        for (double v : new double[]{spec.strokeMm(), spec.fontSizeMm(), spec.stripeMm(), spec.dashedInsetMm()}) {
            RenderLimits.finite(v, "绘图参数");
            if (v < 0 || v > Math.max(spec.widthMm(), spec.heightMm()) * 2) throw new IllegalArgumentException("绘图参数超出范围");
        }
        RenderLimits.finite(spec.rotationDegrees(), "rotationDegrees");
        if (Math.abs(spec.rotationDegrees()) > 360) throw new IllegalArgumentException("旋转角度必须在 -360 到 360 之间");
        if (spec.lines().size() > 32 || spec.lines().stream().anyMatch(s -> s.length() > 2048)) throw new IllegalArgumentException("标签文字过长");
        if (spec.kind() == RasterSpec.Kind.LABEL && spec.lines().isEmpty()) throw new IllegalArgumentException("文字标签缺少 lines");
        if (spec.fontFamily().length() > 100) throw new IllegalArgumentException("字体名称过长");
        int[] size = size(spec); limits.checkDimensions(size[0], size[1]);
        int[] rotated = rotatedSize(size[0], size[1], spec.rotationDegrees()); limits.checkDimensions(rotated[0], rotated[1]);
        if (spec.kind() == RasterSpec.Kind.BORDER && (spec.margins().left() + spec.margins().right() >= spec.widthMm()
                || spec.margins().top() + spec.margins().bottom() >= spec.heightMm())) throw new IllegalArgumentException("内框边距超过画布");
    }

    public RenderResult render(RasterSpec spec, OutputStream output) throws Exception {
        validate(spec); RenderLimits.checkpoint();
        int[] size = size(spec);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = image;
        try {
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.setColor(color(spec.background())); graphics.fillRect(0, 0, size[0], size[1]);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setColor(color(spec.foreground()));
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                float stroke = (float) Math.max(1, px(spec.strokeMm(), spec.dpi()));
                graphics.setStroke(new BasicStroke(stroke));
                switch (spec.kind()) {
                    case SOLID -> graphics.fillRect(0, 0, size[0], size[1]);
                    case BORDER -> drawBorder(graphics, spec, size, stroke);
                    case LABEL -> drawLabel(graphics, spec, size);
                    case STRIPES -> drawStripes(graphics, spec, size);
                    case CIRCLE -> graphics.fill(new Ellipse2D.Double(0, 0, size[0], size[1]));
                    case CROSS -> {
                        graphics.drawLine(size[0] / 2, 0, size[0] / 2, size[1] - 1);
                        graphics.drawLine(0, size[1] / 2, size[0] - 1, size[1] / 2);
                    }
                }
            } finally { graphics.dispose(); }
            RenderLimits.checkpoint();
            if (Math.abs(spec.rotationDegrees() % 360) > 0.000001) {
                int[] rotated = rotatedSize(size[0], size[1], spec.rotationDegrees());
                result = new BufferedImage(rotated[0], rotated[1], BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = result.createGraphics();
                try {
                    g.translate(rotated[0] / 2D, rotated[1] / 2D);
                    g.rotate(Math.toRadians(spec.rotationDegrees()));
                    g.translate(-size[0] / 2D, -size[1] / 2D);
                    g.drawImage(image, 0, 0, null);
                } finally { g.dispose(); }
            }
            RenderLimits.checkpoint();
            writePng(result, spec.dpi(), output);
            return new RenderResult("image/png", "png", result.getWidth(), result.getHeight(), "px");
        } finally {
            if (result != image) result.flush();
            image.flush();
        }
    }

    private void drawBorder(Graphics2D g, RasterSpec s, int[] size, float stroke) {
        double inset = stroke / 2D;
        g.draw(new Rectangle2D.Double(inset, inset, Math.max(0, size[0] - stroke), Math.max(0, size[1] - stroke)));
        if (s.margins().left() + s.margins().right() + s.margins().top() + s.margins().bottom() > 0) {
            double x = px(s.margins().left(), s.dpi()), y = px(s.margins().top(), s.dpi());
            g.draw(new Rectangle2D.Double(x, y, px(s.widthMm() - s.margins().left() - s.margins().right(), s.dpi()),
                    px(s.heightMm() - s.margins().top() - s.margins().bottom(), s.dpi())));
        }
        double dashed = px(s.dashedInsetMm(), s.dpi());
        if (dashed > 0 && dashed * 2 < Math.min(size[0], size[1])) {
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
            g.draw(new Rectangle2D.Double(dashed, dashed, size[0] - 2 * dashed, size[1] - 2 * dashed));
        }
    }

    private void drawLabel(Graphics2D g, RasterSpec s, int[] size) {
        int requested = s.fontSizeMm() > 0 ? (int) Math.ceil(px(s.fontSizeMm(), s.dpi())) : Math.max(1, size[1] / (s.lines().size() + 1));
        Font font = GraphicsTextFonts.resolve(new Font(s.fontFamily(), s.bold() ? Font.BOLD : Font.PLAIN, requested),
                String.join("", s.lines()));
        FontMetrics metrics = g.getFontMetrics(font);
        int width = s.lines().stream().mapToInt(metrics::stringWidth).max().orElse(1);
        double scale = Math.min(1D, Math.min((size[0] - 2D) / Math.max(1, width), (size[1] - 2D) / (metrics.getHeight() * (double) s.lines().size())));
        g.setFont(font.deriveFont((float) Math.max(1, requested * scale)));
        FontMetrics fm = g.getFontMetrics();
        int y = (size[1] - fm.getHeight() * s.lines().size()) / 2 + fm.getAscent();
        for (String line : s.lines()) { g.drawString(line, (size[0] - fm.stringWidth(line)) / 2, y); y += fm.getHeight(); }
    }

    private void drawStripes(Graphics2D g, RasterSpec s, int[] size) throws InterruptedException {
        int stripe = Math.max(1, ceilPixels(px(s.stripeMm(), s.dpi())));
        int extent = s.verticalStripes() ? size[0] : size[1];
        for (int offset = 0, index = 0; offset < extent; offset += stripe, index++) {
            RenderLimits.checkpoint();
            g.setColor(index % 2 == 0 ? color(s.foreground()) : Color.WHITE);
            if (s.verticalStripes()) g.fillRect(offset, 0, stripe, size[1]); else g.fillRect(0, offset, size[0], stripe);
        }
    }
    private int[] size(RasterSpec s) {
        double w = Math.ceil(px(s.widthMm(), s.dpi()) - 1e-9), h = Math.ceil(px(s.heightMm(), s.dpi()) - 1e-9);
        if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) throw new IllegalArgumentException("画布过大");
        return new int[]{(int) w, (int) h};
    }
    private int[] rotatedSize(int w, int h, double degrees) {
        double sin = Math.abs(Math.sin(Math.toRadians(degrees))), cos = Math.abs(Math.cos(Math.toRadians(degrees)));
        return new int[]{Math.max(1, (int) Math.ceil(w * cos + h * sin - 1e-8)), Math.max(1, (int) Math.ceil(h * cos + w * sin - 1e-8))};
    }
    private double px(double mm, int dpi) { return mm / 25.4D * dpi; }
    private int ceilPixels(double value) { return (int) Math.ceil(value - 1e-9); }
    private void writePng(BufferedImage image, int dpi, OutputStream output) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            var type = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image);
            var metadata = writer.getDefaultImageMetadata(type, writer.getDefaultWriteParam());
            var root = new IIOMetadataNode("javax_imageio_png_1.0");
            var physical = new IIOMetadataNode("pHYs");
            physical.setAttribute("pixelsPerUnitXAxis", Long.toString(Math.round(dpi / 0.0254)));
            physical.setAttribute("pixelsPerUnitYAxis", Long.toString(Math.round(dpi / 0.0254)));
            physical.setAttribute("unitSpecifier", "meter"); root.appendChild(physical);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
            imageOutput.flush();
        } finally { writer.dispose(); }
    }
    private Color color(String value) {
        if ("transparent".equals(value)) return new Color(0, 0, 0, 0);
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("颜色必须为 #RRGGBB 或 transparent");
        return Color.decode(value.toUpperCase(Locale.ROOT));
    }
}
