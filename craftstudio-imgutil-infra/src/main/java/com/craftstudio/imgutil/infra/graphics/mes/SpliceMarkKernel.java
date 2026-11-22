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

public class SpliceMarkKernel extends LegacySupport {
    public BufferedImage createRotatedTwoLineTextPng(double width, double height, String text) {
        try {
            int w = (int) Math.ceil(width);
            int h = (int) Math.ceil(height);
            Font font = boldMarkFont(text, Math.max(8, h / 2 - 2));
            BufferedImage image = canvas(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, w, h);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getAscent();
            int x = Math.max(0, (w - textW) / 2);
            int firstLineY = Math.max(textH, h / 2 - 2);
            int secondLineY = Math.max(textH + 2, h - 2);
            g.setColor(Color.WHITE);
            g.drawString(text, x, firstLineY);
            g.setColor(createGrayColor(20));
            g.drawString(text, x, secondLineY);
            g.dispose();
            BufferedImage rotated = rotateClockwise90(image);
            return rotated;
        } catch (Exception e) {
            throw new IllegalStateException("生成文字 PNG 失败", e);
        }
    }
    public BufferedImage createSingleLineTextImage(int w, int h, String text, Color textColor) {
        Font font = boldMarkFont(text, Math.max(8, h - 2));
        BufferedImage image = canvas(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, w, h);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(text);
        int textH = fm.getAscent();
        int x = Math.max(0, (w - textW) / 2);
        int y = Math.max(textH, Math.min(h - 2, (h + textH) / 2 - 1));
        g.setColor(textColor);
        g.drawString(text, x, y);
        g.dispose();
        return image;
    }
    public BufferedImage createStripeImage(int w, int h) {
        BufferedImage image = canvas(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        for (int y = 0; y < h; y++) {
            boolean blackBand = y % 2 == 0;
            Color bandColor = blackBand ? Color.BLACK : Color.WHITE;
            g.setColor(new Color(bandColor.getRed(), bandColor.getGreen(), bandColor.getBlue(), 255));
            g.fillRect(0, y, w, 1);
        }
        g.dispose();
        return image;
    }
    private Color createGrayColor(int blackPercent) {
        int black = Math.max(0, Math.min(100, blackPercent));
        int v = (int) Math.round(255 * (1 - black / 100.0));
        return new Color(v, v, v);
    }
    public BufferedImage rotateClockwise90(BufferedImage src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        BufferedImage dst = canvas(srcH, srcW, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                dst.setRGB(srcH - 1 - y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }
}
