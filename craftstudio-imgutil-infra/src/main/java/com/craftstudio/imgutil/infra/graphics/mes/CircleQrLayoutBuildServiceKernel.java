package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.infra.graphics.GraphicsTextFonts;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.*;
import javax.imageio.ImageIO;


public class CircleQrLayoutBuildServiceKernel extends LegacySupport {
    private static final int TAG_DPI = 300;
    private static final double MM_PER_INCH = 25.4D;
    private static final int QR_LEFT_MM = 10;
    private static final int ELEMENT_F_SHIFT_MM = 30;
    private static final int QR_SIZE_MM = 15;
    private static final int QR_BOTTOM_GAP_MM = 2;
    private static final int ELEMENT_GAP_MM = 30;
    private static final int EXTRA_INFO_GAP_MM = 5;
    private static final int WRAPPED_EXT_INFO_TEXT_HEIGHT_MM = 4;
    private static final double WRAPPED_EXT_INFO_ROW_GAP_MM = 1.5D;
    private static final int ANCHOR_SIZE_MM = 4;
    private static final int ANCHOR_GAP_TO_MARGIN_BOTTOM_MM = 2;
    private static final int TOP_ANCHOR_LEFT_MM = QR_LEFT_MM + QR_SIZE_MM + 15;
    private static final int TOP_ANCHOR_RIGHT_MM = 40;
    private static final int BOTTOM_ANCHOR_LEFT_MM = 40;
    private static final int BOTTOM_ANCHOR_RIGHT_MM = 40;
    private static final int NESTED_HEIGHT_EXPAND_THRESHOLD_MM = 2400;
    private static final int SIDE_EXPAND_MM = 6;
    private static final int SIDE_ANCHOR_INTERVAL_MM = 1150;
    private static final int ANCHOR_INNER_SHIFT_MM = 30;
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
                                        BigDecimal tagContentMaxWidth,
                                        BigDecimal stripHeight,
                                        boolean rotate180,
                                        boolean hasRedoProductionPiece) {
        // 生成标签条 PNG 并上传至 OSS 的 /tag 路径，返回可访问 URL
        int stripHeightInt = stripHeight.intValue();
        int stripWidthInt = stripWidth.intValue();
        int canvasWidthPx = mmToPx(stripWidthInt);
        int canvasHeightPx = mmToPx(stripHeightInt);
        int elementShiftPx = mmToPx(ELEMENT_F_SHIFT_MM);
        int qrLeftPx = mmToPx(QR_LEFT_MM) + elementShiftPx;
        int qrSizePx = mmToPx(QR_SIZE_MM);
        int qrTopPx = canvasHeightPx - mmToPx(QR_BOTTOM_GAP_MM + QR_SIZE_MM);
        int bX = qrLeftPx + qrSizePx + mmToPx(ELEMENT_GAP_MM);
        int textHeight = Math.max(mmToPx(4), 1);
        int maxContentWidthPx = mmToPx(tagContentMaxWidth == null ? stripWidthInt : tagContentMaxWidth.doubleValue());
        List<String> tagTexts = buildTagTexts(elementA, elementAExtInfos, commonOrderId, elementB, hasRedoProductionPiece);
        String displayText = tagTexts.stream().map(this::extractDisplayText).collect(Collectors.joining());
        Font font = GraphicsTextFonts.productionMark(displayText, textHeight);
        Font wrappedFont = font.deriveFont((float) Math.max(mmToPx(WRAPPED_EXT_INFO_TEXT_HEIGHT_MM), 1));
        TagTextLayout tagTextLayout = resolveTagTextLayout(tagTexts, bX, font, wrappedFont, maxContentWidthPx);

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
            Font extInfoFont = tagTextLayout.wrap ? wrappedFont : font;
            FontMetrics extInfoFontMetrics = tagTextLayout.wrap ? g.getFontMetrics(extInfoFont) : fontMetrics;
            int extInfoFirstBaseLineY = textBaseLineY;
            int extInfoSecondBaseLineY = textBaseLineY;
            if (tagTextLayout.wrap) {
                int textAreaHeightPx = mmToPx(WRAPPED_EXT_INFO_TEXT_HEIGHT_MM * 2 + WRAPPED_EXT_INFO_ROW_GAP_MM);
                int textAreaTopY = (canvasHeightPx - textAreaHeightPx) / 2;
                extInfoFirstBaseLineY = textAreaTopY + ((mmToPx(WRAPPED_EXT_INFO_TEXT_HEIGHT_MM) - extInfoFontMetrics.getHeight()) / 2) + extInfoFontMetrics.getAscent();
                extInfoSecondBaseLineY = extInfoFirstBaseLineY + mmToPx(WRAPPED_EXT_INFO_TEXT_HEIGHT_MM + WRAPPED_EXT_INFO_ROW_GAP_MM);
            }

            BufferedImage qrImage = decodePngDataUri(qrDataUri);
            if (qrImage != null) {
                BufferedImage effectiveQrImage = trimWhiteBorder(qrImage);
                g.drawImage(effectiveQrImage, qrLeftPx, qrTopPx, qrSizePx, qrSizePx, null);
            }
            Font originFont = g.getFont();
            g.setFont(extInfoFont);
            try {
                drawTagTextRow(g, tagTextLayout.firstRow, bX, extInfoFirstBaseLineY, extInfoFontMetrics);
                if (tagTextLayout.wrap) {
                    drawTagTextRow(g, tagTextLayout.secondRow, bX, extInfoSecondBaseLineY, extInfoFontMetrics);
                }
            } finally {
                g.setFont(originFont);
            }

            BufferedImage uploadImage = rotate180 ? rotateCenter180(canvas) : canvas;

            return uploadImage;
        } catch (Exception e) {
            throw new IllegalStateException("生成并上传标签条PNG失败", e);
        } finally {
            g.dispose();
        }
    }

    private TagTextLayout resolveTagTextLayout(List<String> tagTexts, int textStartX, Font font,
                                               Font wrappedFont, int maxContentWidthPx) {
        FontMetrics metrics = createFontMetrics(font);
        int singleRowEndX = textStartX + measureJoinedWidth(tagTexts, metrics);
        if (singleRowEndX <= maxContentWidthPx) {
            return new TagTextLayout(false, tagTexts, new ArrayList<>());
        }
        FontMetrics wrappedMetrics = createFontMetrics(wrappedFont);
        List<String> firstRow = new ArrayList<>();
        List<String> secondRow = new ArrayList<>();
        int firstRowWidth = 0;
        int maxRowWidth = Math.max(maxContentWidthPx - textStartX, 0);
        for (String tagText : tagTexts) {
            int nextWidth = firstRowWidth + wrappedMetrics.stringWidth(extractDisplayText(tagText)) + mmToPx(EXTRA_INFO_GAP_MM);
            if (!firstRow.isEmpty() && nextWidth > maxRowWidth) {
                secondRow.add(tagText);
            } else {
                firstRow.add(tagText);
                firstRowWidth = nextWidth;
            }
        }
        return new TagTextLayout(true, firstRow, secondRow);
    }

    private List<String> buildTagTexts(String elementA,
                                       List<String> elementAExtInfos,
                                       String commonOrderId,
                                       String elementB,
                                       boolean hasRedoProductionPiece) {
        List<String> tagTexts = new ArrayList<>();
        if (hasRedoProductionPiece) {
            tagTexts.add("<font color='red'>重做</font>");
        }
        if (StringUtils.isNotBlank(elementB)) {
            tagTexts.add(elementB);
        }
        if (StringUtils.isNotBlank(commonOrderId)) {
            tagTexts.add("<font color='blue'>" + commonOrderId + "</font>");
        }
        if (StringUtils.isNotBlank(elementA)) {
            tagTexts.add(elementA);
        }
        if (elementAExtInfos != null) {
            tagTexts.addAll(elementAExtInfos.stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList()));
        }
        return tagTexts;
    }

    private FontMetrics createFontMetrics(Font font) {
        BufferedImage image = canvas(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            return graphics.getFontMetrics(font);
        } finally {
            graphics.dispose();
        }
    }

    private int measureJoinedWidth(List<String> texts, FontMetrics metrics) {
        int width = 0;
        for (String text : texts) {
            width += metrics.stringWidth(extractDisplayText(text)) + mmToPx(EXTRA_INFO_GAP_MM);
        }
        return width;
    }

    private void drawTagTextRow(Graphics2D g, List<String> tagTexts, int x, int baselineY, FontMetrics fontMetrics) {
        int currentX = x;
        for (String tagText : tagTexts) {
            drawTextRotate180(g, tagText, currentX, baselineY, fontMetrics);
            currentX += fontMetrics.stringWidth(extractDisplayText(tagText)) + mmToPx(EXTRA_INFO_GAP_MM);
        }
    }

    private static class TagTextLayout {
        private final boolean wrap;
        private final List<String> firstRow;
        private final List<String> secondRow;

        private TagTextLayout(boolean wrap, List<String> firstRow, List<String> secondRow) {
            this.wrap = wrap;
            this.firstRow = firstRow;
            this.secondRow = secondRow;
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
        return text.replaceAll("<font\\s+color='(red|blue)'>", "").replace("</font>", "");
    }

    private void drawRichText(Graphics2D g, String text, int x, int baselineY, FontMetrics fontMetrics) {
        String safeText = text == null ? "" : text;
        String redOpenTag = "<font color='red'>";
        String blueOpenTag = "<font color='blue'>";
        String closeTag = "</font>";
        int redOpenIdx = safeText.indexOf(redOpenTag);
        int blueOpenIdx = safeText.indexOf(blueOpenTag);
        boolean blueText = blueOpenIdx >= 0 && (redOpenIdx < 0 || blueOpenIdx < redOpenIdx);
        String openTag = blueText ? blueOpenTag : redOpenTag;
        int openIdx = blueText ? blueOpenIdx : redOpenIdx;
        int closeIdx = safeText.indexOf(closeTag);
        if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) {
            g.setColor(Color.BLACK);
            g.drawString(safeText, x, baselineY);
            return;
        }

        String prefix = safeText.substring(0, openIdx);
        String coloredText = safeText.substring(openIdx + openTag.length(), closeIdx);
        String suffix = safeText.substring(closeIdx + closeTag.length());

        int currentX = x;
        if (StringUtils.isNotEmpty(prefix)) {
            g.setColor(Color.BLACK);
            g.drawString(prefix, currentX, baselineY);
            currentX += fontMetrics.stringWidth(prefix);
        }
        if (StringUtils.isNotEmpty(coloredText)) {
            g.setColor(blueText ? Color.BLUE : Color.RED);
            g.drawString(coloredText, currentX, baselineY);
            currentX += fontMetrics.stringWidth(coloredText);
        }
        if (StringUtils.isNotEmpty(suffix)) {
            g.setColor(Color.BLACK);
            g.drawString(suffix, currentX, baselineY);
        }
    }

    private BufferedImage decodePngDataUri(String dataUri) {
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
        return mmToPx((double) mm);
    }

    private int mmToPx(double mm) {
        return Math.max((int) Math.round(mm * TAG_DPI / MM_PER_INCH), 1);
    }


}
