package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.infra.graphics.GraphicsTextFonts;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class LiubaiRasterKernel extends LegacySupport {
 private static final double MARK_PNG_DPI=36D, LIUBAI_TAG_PNG_DPI=300D, MM_PER_INCH=25.4D; private static final float DEFAULT_BORDER_STROKE_WIDTH_PX=1F;

 private final Params p;
 public LiubaiRasterKernel(Params p) { this.p=p; }
 private double liubaiTagFontScale(){return p.number("fontScale",1);}
 private double borderStrokeWidthScale(){return p.number("strokeScale",1);}
 private Color outerBorderColor(){return new Color(p.integer("borderColor", java.awt.Color.BLACK.getRGB()),true);}
 private boolean shouldDrawInnerOriginalBorder(){return p.bool("innerBorder",true);}
 private double dashedInsetFromOuterBorderMm(){return p.number("dashedInset",0);}
 private List<Color> liubaiTagTextColors(){return p.integers("colors").stream().map(c -> new Color(c,true)).toList();}
 public static class ExpandMargins { public double left,right,top,bottom; public ExpandMargins(double l,double r,double t,double b){left=l;right=r;top=t;bottom=b;} }
    public BufferedImage createBorderPng(double originalWidth, double originalHeight, ExpandMargins margins) {
        double widthMm = originalWidth + margins.left + margins.right;
        double heightMm = originalHeight + margins.top + margins.bottom;
        int imageWidth = convertMmToPixels(widthMm);
        int imageHeight = convertMmToPixels(heightMm);
        try {
            BufferedImage image = canvas(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setStroke(new BasicStroke(borderStrokeWidthPx()));
            graphics.setColor(outerBorderColor());
            graphics.drawRect(0, 0, imageWidth - 1, imageHeight - 1);
            drawDashedInsetBorderIfNecessary(graphics, imageWidth, imageHeight, widthMm, heightMm, margins);
            drawInnerOriginalBorderIfNecessary(graphics, imageWidth, imageHeight, originalWidth, originalHeight, margins);
            graphics.dispose();
            return image;
        } catch (Exception e) {
            throw new IllegalStateException("生成留白外框 PNG 失败", e);
        }
    }
    private void drawInnerOriginalBorderIfNecessary(Graphics2D graphics,
                                                    int imageWidth,
                                                    int imageHeight,
                                                    double originalWidth,
                                                    double originalHeight,
                                                    ExpandMargins margins) {
        if (!shouldDrawInnerOriginalBorder()) {
            return;
        }
        drawPhysicalBorder(graphics, imageWidth, imageHeight, margins.left, margins.top, originalWidth, originalHeight, Color.BLACK, false);
    }
    private void drawDashedInsetBorderIfNecessary(Graphics2D graphics,
                                                   int imageWidth,
                                                   int imageHeight,
                                                   double widthMm,
                                                   double heightMm,
                                                   ExpandMargins margins) {
        double insetMm = dashedInsetFromOuterBorderMm();
        if (insetMm <= 0D) {
            return;
        }
        double leftInsetMm = dashedInsetForMargin(margins.left, insetMm);
        double topInsetMm = dashedInsetForMargin(margins.top, insetMm);
        double rightInsetMm = dashedInsetForMargin(margins.right, insetMm);
        double bottomInsetMm = dashedInsetForMargin(margins.bottom, insetMm);
        double innerWidthMm = widthMm - leftInsetMm - rightInsetMm;
        double innerHeightMm = heightMm - topInsetMm - bottomInsetMm;
        if (innerWidthMm <= 0D || innerHeightMm <= 0D) {
            return;
        }
        drawPhysicalBorder(graphics, imageWidth, imageHeight, leftInsetMm, topInsetMm, innerWidthMm, innerHeightMm, Color.BLACK, true);
    }
    private double dashedInsetForMargin(double marginMm, double insetMm) {
        return marginMm <= 0D ? 0D : insetMm;
    }
    private void drawPhysicalBorder(Graphics2D graphics,
                                    int imageWidth,
                                    int imageHeight,
                                    double leftMm,
                                    double topMm,
                                    double widthMm,
                                    double heightMm,
                                    Color color,
                                    boolean dashed) {
        int left = Math.min(imageWidth - 1, convertMmToPixels(leftMm));
        int top = Math.min(imageHeight - 1, convertMmToPixels(topMm));
        int right = Math.min(imageWidth - 1, convertMmToPixels(leftMm + widthMm) - 1);
        int bottom = Math.min(imageHeight - 1, convertMmToPixels(topMm + heightMm) - 1);
        if (right < left || bottom < top) {
            return;
        }
        graphics.setColor(color);
        graphics.setStroke(dashed
                ? new BasicStroke(borderStrokeWidthPx(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10F, new float[]{6F, 4F}, 0F)
                : new BasicStroke(borderStrokeWidthPx()));
        graphics.drawRect(left, top, right - left, bottom - top);
    }
    private int convertMmToPixels(double valueMm) {
        return Math.max(1, (int) Math.ceil(valueMm / MM_PER_INCH * MARK_PNG_DPI));
    }
    private float borderStrokeWidthPx() {
        return (float) (DEFAULT_BORDER_STROKE_WIDTH_PX * borderStrokeWidthScale());
    }
    public BufferedImage createHorizontalLiubaiTagPng(String text, double edgeSizeMm) {
        int heightPx = convertMmToPixels(edgeSizeMm, LIUBAI_TAG_PNG_DPI);
        int baseFontSize = Math.max(12, (int) Math.floor(heightPx * 0.72D));
        int fontSize = Math.max(1, (int) Math.round(baseFontSize * liubaiTagFontScale()));
        Font font = GraphicsTextFonts.productionMark(text, fontSize);
        FontMetrics metrics = fontMetrics(font);
        int paddingPx = Math.max(4, convertMmToPixels(1D, LIUBAI_TAG_PNG_DPI));
        List<Color> colors = liubaiTagTextColors();
        if (colors == null || colors.isEmpty()) {
            colors = List.of(Color.BLACK);
        }
        int segmentGapPx = Math.max(paddingPx, convertMmToPixels(2D, LIUBAI_TAG_PNG_DPI));
        int textWidthPx = metrics.stringWidth(text);
        int widthPx = Math.max(1, textWidthPx * colors.size() + segmentGapPx * Math.max(0, colors.size() - 1) + paddingPx * 2);
        BufferedImage image = canvas(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        clearAndConfigureTextGraphics(graphics, widthPx, heightPx);
        graphics.setFont(font);
        FontMetrics imageMetrics = graphics.getFontMetrics();
        int x = paddingPx;
        int y = Math.max(imageMetrics.getAscent(), Math.min(heightPx - 1, (heightPx + imageMetrics.getAscent() - imageMetrics.getDescent()) / 2));
        for (Color color : colors) {
            graphics.setColor(color == null ? Color.BLACK : color);
            graphics.drawString(text, x, y);
            x += textWidthPx + segmentGapPx;
        }
        graphics.dispose();
        return image;
    }
    private FontMetrics fontMetrics(Font font) {
        BufferedImage image = canvas(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.dispose();
        return metrics;
    }
    private void clearAndConfigureTextGraphics(Graphics2D graphics, int width, int height) {
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    private int convertMmToPixels(double valueMm, double dpi) {
        return Math.max(1, (int) Math.ceil(valueMm / MM_PER_INCH * dpi));
    }
}
