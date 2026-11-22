package com.craftstudio.imgutil.infra.graphics.mes;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class SpliceRasterKernel extends LegacySupport {
 private static final double MM_PER_INCH=25.4D; private static final double TEXT_PNG_DPI=300D;
    public BufferedImage createTextImage(double widthMm, double heightMm, String text, Color textColor) {
        int width = convertTextMmToPixels(widthMm);
        int height = convertTextMmToPixels(heightMm);
        Font font = boldMarkFont(text, Math.max(8, height - convertTextMmToPixels(1D)));
        BufferedImage image = canvas(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getAscent();
        int x = Math.max(0, (width - textW) / 2);
        int y = Math.max(textH, Math.min(height - convertTextMmToPixels(0.5D), (height + textH) / 2 - 1));
        g.setColor(textColor);
        g.drawString(text, x, y);
        g.dispose();
        return image;
    }
    private int convertTextMmToPixels(double valueMm) {
        return Math.max(1, (int) Math.ceil(valueMm / MM_PER_INCH * TEXT_PNG_DPI));
    }
    public BufferedImage createStripeImage(int width, int height) {
        return createStripeImage(width, height, Color.BLACK);
    }
    public BufferedImage rotateImageByAngle(BufferedImage src, double angle) {
        if (Math.abs(angle) < 0.000001D) {
            return src;
        }
        double rad = Math.toRadians(angle);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int w = src.getWidth();
        int h = src.getHeight();
        int newW = Math.max(1, (int) Math.ceil(w * cos + h * sin));
        int newH = Math.max(1, (int) Math.ceil(h * cos + w * sin));
        BufferedImage rotated = canvas(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = rotated.createGraphics();
        g2d.setComposite(AlphaComposite.Src);
        g2d.translate((newW - w) / 2D, (newH - h) / 2D);
        g2d.rotate(rad, w / 2D, h / 2D);
        g2d.drawRenderedImage(src, null);
        g2d.dispose();
        return rotated;
    }
    public BufferedImage createStripeImage(int width, int height, Color darkBandColor) {
        BufferedImage image = canvas(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        for (int y = 0; y < image.getHeight(); y++) {
            boolean blackBand = y % 2 == 0;
            Color bandColor = blackBand ? darkBandColor : Color.WHITE;
            g.setColor(new Color(bandColor.getRed(), bandColor.getGreen(), bandColor.getBlue(), 255));
            g.fillRect(0, y, image.getWidth(), 1);
        }
        g.dispose();
        return image;
    }
}
