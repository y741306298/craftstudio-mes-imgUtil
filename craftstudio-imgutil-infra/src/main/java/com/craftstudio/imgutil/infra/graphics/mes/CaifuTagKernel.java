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

public class CaifuTagKernel extends LegacySupport {
    private static final int EXPAND_LEFT_MM = 10;
    private static final int EXPAND_RIGHT_MM = 13;
    private static final int MARK_B_WIDTH_MM = 8;
    private static final int MARK_B_HEIGHT_MM = 3;
    private static final int MARK_B_OFFSET_Y_MM = 255;
    private static final int MARK_C_WIDTH_MM = 3;
    private static final int MARK_C_OFFSET_X_MM = 20;
    protected static final int OPEN_BACK_TAG_HEIGHT_MM = 20;
    private static final int TAG_DPI = 300;
    private static final double MM_PER_INCH = 25.4D;
    private static final int TAG_TEXT_LEFT_MM = 30;
    private static final int TAG_TEXT_GAP_MM = 5;
    public BufferedImage render(String elementA, int stripWidth, boolean rotate180) {
        int canvasWidthPx = mmToPx(stripWidth);
        int canvasHeightPx = mmToPx(OPEN_BACK_TAG_HEIGHT_MM);
        int textHeight = Math.max(mmToPx(4), 1);
        BufferedImage canvas = canvas(canvasWidthPx, canvasHeightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasWidthPx, canvasHeightPx);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(GraphicsTextFonts.productionMark(Objects.toString(elementA, ""), textHeight));
            FontMetrics fontMetrics = g.getFontMetrics();
            int baselineY = ((canvasHeightPx - textHeight) / 2) + ((textHeight - fontMetrics.getHeight()) / 2) + fontMetrics.getAscent();
            int currentX = mmToPx(TAG_TEXT_LEFT_MM);
            if (elementA != null && !elementA.trim().isEmpty()) {
                drawTextRotate180(g, elementA, currentX, baselineY, fontMetrics);
                currentX += fontMetrics.stringWidth(elementA) + mmToPx(TAG_TEXT_GAP_MM);
            }
            BufferedImage uploadImage = rotate180 ? rotateCenter180(canvas) : canvas;
            return uploadImage;
        } catch (Exception e) {
            throw new IllegalStateException("生成开背标签条PNG失败", e);
        } finally {
            g.dispose();
        }
    }
    private void drawTextRotate180(Graphics2D g, String text, int x, int baselineY, FontMetrics fontMetrics) {
        int textWidth = fontMetrics.stringWidth(text);
        if (textWidth <= 0) {
            return;
        }
        int textHeight = fontMetrics.getHeight();
        double centerX = x + textWidth / 2.0D;
        double centerY = baselineY - fontMetrics.getAscent() + textHeight / 2.0D;
        AffineTransform origin = g.getTransform();
        try {
            g.rotate(Math.PI, centerX, centerY);
            g.drawString(text, x, baselineY);
        } finally {
            g.setTransform(origin);
        }
    }
    private BufferedImage rotateCenter180(BufferedImage source) {
        BufferedImage rotated = canvas(source.getWidth(), source.getHeight(), source.getType());
        Graphics2D g = rotated.createGraphics();
        try {
            g.rotate(Math.PI, source.getWidth() / 2.0D, source.getHeight() / 2.0D);
            g.drawImage(source, 0, 0, null);
            return rotated;
        } finally {
            g.dispose();
        }
    }
    private int mmToPx(int mm) {
        return (int) Math.round(mm / MM_PER_INCH * TAG_DPI);
    }
}
