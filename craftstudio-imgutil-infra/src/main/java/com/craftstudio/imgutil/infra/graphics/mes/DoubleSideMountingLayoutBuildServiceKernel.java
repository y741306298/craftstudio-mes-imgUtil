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


public class DoubleSideMountingLayoutBuildServiceKernel extends LegacySupport {
    private static final int TAG_DPI = 300;
    private static final double MM_PER_INCH = 25.4D;
    private static final int QR_LEFT_MM = 10;
    private static final int QR_SIZE_MM = 15;
    private static final int QR_BOTTOM_GAP_MM = 2;
    private static final int ELEMENT_GAP_MM = 30;
    private static final int EXTRA_INFO_GAP_MM = 5;
    private static final int NESTED_HEIGHT_EXPAND_THRESHOLD_MM = 2400;
    private static final int SIDE_EXPAND_MM = 6;
    private static final String LEFT_ARROW_URL = "https://craftstudio-mes-test.oss-cn-hangzhou.aliyuncs.com/basetag/basetag-leftarrow.png";
    private static final String RIGHT_ARROW_URL = "https://craftstudio-mes-test.oss-cn-hangzhou.aliyuncs.com/basetag/basetag-rightarrow.png";
    public BufferedImage render(String businessId,
                                        String manufacturerMetaId,
                                        String typesettingId,
                                        String elementA,
                                        List<String> elementAExtInfos,
                                        String commonOrderId,
                                        String elementB,
                                        String qrDataUri,
                                        BigDecimal stripWidth,
                                        BigDecimal stripHeight,
                                        boolean rotate180) {
        // 生成标签条 PNG 并上传至 OSS 的 /tag 路径，返回可访问 URL
        int stripHeightInt = stripHeight.intValue();
        int stripWidthInt = stripWidth.intValue();
        int canvasWidthPx = mmToPx(stripWidthInt);
        int canvasHeightPx = mmToPx(stripHeightInt);
        int qrLeftPx = mmToPx(QR_LEFT_MM);
        int qrSizePx = mmToPx(QR_SIZE_MM);
        int qrTopPx = canvasHeightPx - mmToPx(QR_BOTTOM_GAP_MM + QR_SIZE_MM);
        int bX = qrLeftPx + qrSizePx + mmToPx(ELEMENT_GAP_MM);
        int textHeight = Math.max(mmToPx(4), 1);
        StringBuilder displayText = new StringBuilder();
        displayText.append(extractDisplayText(elementB)).append(extractDisplayText(commonOrderId))
                .append(extractDisplayText(elementA));
        if (elementAExtInfos != null) {
            elementAExtInfos.forEach(info -> displayText.append(extractDisplayText(info)));
        }
        Font font = GraphicsTextFonts.productionMark(displayText.toString(), textHeight);

        BufferedImage canvas = canvas(canvasWidthPx, canvasHeightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasWidthPx, canvasHeightPx);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            FontMetrics fontMetrics = g.getFontMetrics();
            int textTopY = (canvasHeightPx - textHeight) / 2;
            int textBaseLineY = textTopY + ((textHeight - fontMetrics.getHeight()) / 2) + fontMetrics.getAscent();

            BufferedImage qrImage = decodePngDataUri(qrDataUri);
            if (qrImage != null) {
                BufferedImage effectiveQrImage = trimWhiteBorder(qrImage);
                g.drawImage(effectiveQrImage, qrLeftPx, qrTopPx, qrSizePx, qrSizePx, null);
            }
            drawTextRotate180(g, elementB, bX, textBaseLineY, fontMetrics);
            int currentX = bX + fontMetrics.stringWidth(elementB == null ? "" : elementB) + mmToPx(EXTRA_INFO_GAP_MM);
            if (StringUtils.isNotBlank(commonOrderId)) {
                drawTextRotate180(g, commonOrderId, currentX, textBaseLineY, fontMetrics);
                currentX += fontMetrics.stringWidth(commonOrderId) + mmToPx(EXTRA_INFO_GAP_MM);
            }
            drawTextRotate180(g, elementA, currentX, textBaseLineY, fontMetrics);
            currentX += fontMetrics.stringWidth(elementA == null ? "" : elementA) + mmToPx(EXTRA_INFO_GAP_MM);
            if (elementAExtInfos != null) {
                for (String extInfo : elementAExtInfos) {
                    if (StringUtils.isBlank(extInfo)) {
                        continue;
                    }
                    drawTextRotate180(g, extInfo, currentX, textBaseLineY, fontMetrics);
                    currentX += fontMetrics.stringWidth(extractDisplayText(extInfo)) + mmToPx(EXTRA_INFO_GAP_MM);
                }
            }

            BufferedImage uploadImage = rotate180 ? rotateCenter180(canvas) : canvas;

            return uploadImage;
        } catch (Exception e) {
            throw new IllegalStateException("生成并上传标签条PNG失败", e);
        } finally {
            g.dispose();
        }
    }



    private void drawTextRotate180(Graphics2D g, String text, int x, int baselineY, FontMetrics fontMetrics) {
        String safeText = text == null ? "" : text;
        String plainText = extractDisplayText(safeText);
        int textWidth = fontMetrics.stringWidth(plainText);
        if (textWidth <= 0) {
            return;
        }
        int textHeight = fontMetrics.getHeight();
        double centerX = x + textWidth / 2.0D;
        double centerY = baselineY - fontMetrics.getAscent() + textHeight / 2.0D;

        AffineTransform origin = g.getTransform();
        Color originColor = g.getColor();
        try {
            g.rotate(Math.PI, centerX, centerY);
            drawRichText(g, safeText, x, baselineY, fontMetrics);
        } finally {
            g.setColor(originColor);
            g.setTransform(origin);
        }
    }

    private String extractDisplayText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<font\\s+color='red'>", "").replace("</font>", "");
    }

    private void drawRichText(Graphics2D g, String text, int x, int baselineY, FontMetrics fontMetrics) {
        String safeText = text == null ? "" : text;
        String openTag = "<font color='red'>";
        String closeTag = "</font>";
        int openIdx = safeText.indexOf(openTag);
        int closeIdx = safeText.indexOf(closeTag);
        if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) {
            g.setColor(Color.BLACK);
            g.drawString(safeText, x, baselineY);
            return;
        }

        String prefix = safeText.substring(0, openIdx);
        String redText = safeText.substring(openIdx + openTag.length(), closeIdx);
        String suffix = safeText.substring(closeIdx + closeTag.length());

        int currentX = x;
        if (StringUtils.isNotEmpty(prefix)) {
            g.setColor(Color.BLACK);
            g.drawString(prefix, currentX, baselineY);
            currentX += fontMetrics.stringWidth(prefix);
        }
        if (StringUtils.isNotEmpty(redText)) {
            g.setColor(Color.RED);
            g.drawString(redText, currentX, baselineY);
            currentX += fontMetrics.stringWidth(redText);
        }
        if (StringUtils.isNotEmpty(suffix)) {
            g.setColor(Color.BLACK);
            g.drawString(suffix, currentX, baselineY);
        }
    }

    private BufferedImage decodePngDataUri(String dataUri) {
        // Older MES sends its separate arrow mark in the optional QR slot. The original
        // renderer ignored this URL; keep that layout without fetching remote resources.
        if (LEFT_ARROW_URL.equals(dataUri) || RIGHT_ARROW_URL.equals(dataUri)) return null;
        return decodeDataUri(dataUri);
    }

    private BufferedImage trimWhiteBorder(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xFF;
                int red = (rgb >>> 16) & 0xFF;
                int green = (rgb >>> 8) & 0xFF;
                int blue = rgb & 0xFF;
                boolean isWhitePixel = alpha == 0 || (red > 245 && green > 245 && blue > 245);
                if (!isWhitePixel) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return source;
        }
        return source.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private BufferedImage rotateCenter180(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int imageType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage rotated = canvas(width, height, imageType);
        Graphics2D g = rotated.createGraphics();
        try {
            g.drawImage(source, width, height, -width, -height, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    private int mmToPx(int mm) {
        return Math.max((int) Math.round(mm * TAG_DPI / MM_PER_INCH), 1);
    }


}
