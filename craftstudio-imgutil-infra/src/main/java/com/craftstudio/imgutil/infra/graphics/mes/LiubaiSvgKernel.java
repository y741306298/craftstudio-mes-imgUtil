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

public class LiubaiSvgKernel {
    private static final float DEFAULT_BORDER_STROKE_WIDTH_PX = 1F;
    private static final double DEFAULT_BORDER_STROKE_WIDTH_SVG = 1.23D;
    private static final double MARK_PNG_DPI = 36D;
    private static final double LIUBAI_TAG_PNG_DPI = 300D;
    private static final double DEFAULT_LIUBAI_TAG_EDGE_SIZE_MM = 10D;
    private static final double LIUBAI_TAG_ADJACENT_EDGE_GAP_MM = 10D;
    private static final double MM_PER_INCH = 25.4D;
    private static final Pattern SVG_WIDTH_PATTERN = Pattern.compile("width\\s*=\\s*[\"']\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:px|mm)?\\s*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_HEIGHT_PATTERN = Pattern.compile("height\\s*=\\s*[\"']\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:px|mm)?\\s*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_OPEN_PATTERN = Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_CLOSE_PATTERN = Pattern.compile("</svg\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_GROUP_PATTERN = Pattern.compile("<g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_RECT_PATTERN = Pattern.compile("<rect\\b([^>]*)\\s*/>|<rect\\b([^>]*)>\\s*</rect\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_ATTRIBUTE_PATTERN = Pattern.compile("\\s+([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\"']).*?\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_SEQ_PATTERN = Pattern.compile("#\\s*\\d+-(\\d+)");

 private final Params p;
 public LiubaiSvgKernel(Params p){this.p=p;}
 private double borderStrokeWidthScale(){return p.number("strokeScale",1);}
 private double dashedInsetFromOuterBorderMm(){return p.number("dashedInset",0);}
 private boolean shouldDrawInnerOriginalBorder(){return p.bool("innerBorder",true);}
 private double expandMm(){return p.number("expandMm",1);}
 private String specName(){return p.text("specName");}
 private String tagMarkKeyPrefix(){return p.text("tagMarkKeyPrefix");}
    public String buildExpandedSvg(String originalSvg,
                                    String pieceMongoId,
                                    String markPngUrl,
                                    LiubaiTagAssets tagAssets,
                                    String originalContentImg,
                                    double originalWidth,
                                    double originalHeight,
                                    ExpandMargins margins) {
        double newWidth = originalWidth + margins.left + margins.right;
        double newHeight = originalHeight + margins.top + margins.bottom;
        String updatedSvg = updateRootSvgAttributes(originalSvg, newWidth, newHeight, margins);
        String liubaiGroup = shouldBuildLiubaiMarkGroup()
                ? buildLiubaiGroup(pieceMongoId, markPngUrl, sourceName(markPngUrl), originalWidth, originalHeight, margins)
                : "";
        String tagGroups = buildLiubaiTagGroups(pieceMongoId, tagAssets, originalWidth, originalHeight, margins);
        String markGroups = liubaiGroup + tagGroups;
        if (!containsGroup(originalSvg)) {
            return rebuildSvgWithLiubaiGroups(updatedSvg, pieceMongoId, originalContentImg, markGroups);
        }
        return shouldInsertMarkGroupsBeforeOriginalContent()
                ? insertAfterRootOpenTag(updatedSvg, markGroups)
                : insertBeforeRootCloseTag(updatedSvg, markGroups);
    }
    private boolean containsGroup(String svg) {
        return StringUtils.isNotBlank(svg) && SVG_GROUP_PATTERN.matcher(svg).find();
    }
    private String insertAfterRootOpenTag(String svg, String groupSvg) {
        Matcher matcher = SVG_OPEN_PATTERN.matcher(svg);
        if (!matcher.find()) {
            return svg;
        }
        return svg.substring(0, matcher.end()) + groupSvg + svg.substring(matcher.end());
    }
    private String insertBeforeRootCloseTag(String svg, String groupSvg) {
        int closeIndex = lastSvgCloseIndex(svg);
        if (closeIndex < 0) {
            return svg;
        }
        return svg.substring(0, closeIndex) + groupSvg + svg.substring(closeIndex);
    }
    private String rebuildSvgWithLiubaiGroups(String updatedSvg, String pieceMongoId, String originalContentImg, String liubaiGroup) {
        Matcher openMatcher = SVG_OPEN_PATTERN.matcher(updatedSvg);
        if (!openMatcher.find()) {
            return updatedSvg;
        }
        int closeIndex = lastSvgCloseIndex(updatedSvg);
        if (closeIndex < 0 || closeIndex < openMatcher.end()) {
            return updatedSvg;
        }
        String prefix = updatedSvg.substring(0, openMatcher.end());
        String innerSvg = updatedSvg.substring(openMatcher.end(), closeIndex).trim();
        String suffix = updatedSvg.substring(closeIndex);
        String originalContentGroup = buildOriginalContentGroup(pieceMongoId, originalContentImg, innerSvg);
        return shouldInsertMarkGroupsBeforeOriginalContent()
                ? prefix + liubaiGroup + originalContentGroup + suffix
                : prefix + originalContentGroup + liubaiGroup + suffix;
    }
    private String buildOriginalContentGroup(String pieceMongoId, String originalContentImg, String innerSvg) {
        String groupId = StringUtils.isBlank(pieceMongoId) ? "original-mask" : pieceMongoId;
        return "<g id=\"" + escapeAttr(groupId)
                + "\" img=\"" + escapeAttr(originalContentImg)
                + "\" data-source-name=\"" + escapeAttr(sourceName(originalContentImg))
                + "\" data-rotation=\"0\">\n"
                + normalizeRectsToPaths(innerSvg) + "\n"
                + "</g>\n";
    }
    private String normalizeRectsToPaths(String svgContent) {
        if (StringUtils.isBlank(svgContent)) {
            return svgContent;
        }
        Matcher matcher = SVG_RECT_PATTERN.matcher(svgContent);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String rectAttributes = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String path = convertRectToPath(matcher.group(), rectAttributes);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(path));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
    private String convertRectToPath(String originalRect, String rectAttributes) {
        Double x = parseSvgNumber(attributeValue(rectAttributes, "x"), 0D);
        Double y = parseSvgNumber(attributeValue(rectAttributes, "y"), 0D);
        Double width = parseSvgNumber(attributeValue(rectAttributes, "width"), null);
        Double height = parseSvgNumber(attributeValue(rectAttributes, "height"), null);
        if (x == null || y == null || width == null || height == null) {
            return originalRect;
        }
        StringBuilder path = new StringBuilder("<path d=\"M")
                .append(format(x)).append(" ").append(format(y))
                .append(" H").append(format(x + width))
                .append(" V").append(format(y + height))
                .append(" H").append(format(x))
                .append(" Z\"");
        appendRectAttributesAsPathAttributes(path, rectAttributes);
        path.append("/>");
        return path.toString();
    }
    private void appendRectAttributesAsPathAttributes(StringBuilder path, String rectAttributes) {
        Matcher matcher = SVG_ATTRIBUTE_PATTERN.matcher(rectAttributes);
        while (matcher.find()) {
            String attributeName = matcher.group(1);
            if ("x".equalsIgnoreCase(attributeName) || "y".equalsIgnoreCase(attributeName)
                    || "width".equalsIgnoreCase(attributeName) || "height".equalsIgnoreCase(attributeName)) {
                continue;
            }
            path.append(matcher.group());
        }
    }
    private String attributeValue(String attributes, String name) {
        Matcher matcher = SVG_ATTRIBUTE_PATTERN.matcher(attributes);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                String attribute = matcher.group();
                int equalsIndex = attribute.indexOf('=');
                if (equalsIndex < 0) {
                    return null;
                }
                String value = attribute.substring(equalsIndex + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
    private Double parseSvgNumber(String value, Double fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        Matcher matcher = Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?").matcher(value.trim());
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (Exception ignored) {
            return fallback;
        }
    }
    private int lastSvgCloseIndex(String svg) {
        Matcher matcher = SVG_CLOSE_PATTERN.matcher(svg);
        int closeIndex = -1;
        while (matcher.find()) {
            closeIndex = matcher.start();
        }
        return closeIndex;
    }
    private String updateRootSvgAttributes(String svg, double newWidth, double newHeight, ExpandMargins margins) {
        Matcher matcher = SVG_OPEN_PATTERN.matcher(svg);
        if (!matcher.find()) {
            return svg;
        }
        String openTag = matcher.group();
        String updatedOpenTag = upsertSvgAttribute(openTag, "width", format(newWidth));
        updatedOpenTag = upsertSvgAttribute(updatedOpenTag, "height", format(newHeight));
        updatedOpenTag = upsertSvgAttribute(updatedOpenTag, "viewBox",
                format(-margins.left) + " " + format(-margins.top) + " " + format(newWidth) + " " + format(newHeight));
        updatedOpenTag = upsertSvgAttribute(updatedOpenTag, "version", "1.1");
        updatedOpenTag = upsertSvgAttribute(updatedOpenTag, "require-plt", String.valueOf(rootRequirePlt()));
        return svg.substring(0, matcher.start()) + updatedOpenTag + svg.substring(matcher.end());
    }
    private String upsertSvgAttribute(String tag, String name, String value) {
        Pattern attributePattern = Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=\\s*([\"']).*?\\1", Pattern.CASE_INSENSITIVE);
        Matcher matcher = attributePattern.matcher(tag);
        String attribute = " " + name + "=\"" + escapeAttr(value) + "\"";
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(attribute));
        }
        int insertIndex = tag.endsWith("/>") ? tag.length() - 2 : tag.length() - 1;
        return tag.substring(0, insertIndex) + attribute + tag.substring(insertIndex);
    }
    protected boolean rootRequirePlt() {
        return p.bool("rootRequirePlt", true);
    }
    protected boolean liubaiOuterRectRequirePlt() {
        return p.bool("liubaiOuterRectRequirePlt", false);
    }
    protected boolean liubaiGroupRequirePlt() {
        return p.bool("liubaiGroupRequirePlt", false);
    }
    protected boolean shouldBuildLiubaiMarkGroup() {
        return p.bool("shouldBuildLiubaiMarkGroup", true);
    }
    protected boolean shouldInsertMarkGroupsBeforeOriginalContent() {
        return p.bool("shouldInsertMarkGroupsBeforeOriginalContent", true);
    }
    protected String outerRectFillAttributes() {
        return p.values.containsKey("outerRectFillAttributes") ? p.text("outerRectFillAttributes") : "fill=\"#d1495b\" fill-opacity=\"0.82\"";
    }
    protected String outerBorderStrokeColorSvg() {
        return p.values.containsKey("outerBorderStrokeColorSvg") ? p.text("outerBorderStrokeColorSvg") : "#111111";
    }
    private String buildLiubaiGroup(String pieceMongoId,
                                    String markPngUrl,
                                    String markSourceName,
                                    double originalWidth,
                                    double originalHeight,
                                    ExpandMargins margins) {
        double left = -margins.left;
        double top = -margins.top;
        double right = originalWidth + margins.right;
        double bottom = originalHeight + margins.bottom;
        return "\n<g id=\"liubai-" + specName() + "-" + escapeAttr(pieceMongoId) + "\" img=\"" + escapeAttr(markPngUrl)
                + "\" data-source-name=\"" + escapeAttr(markSourceName) + "\" data-forme=\"false\" data-rotation=\"0\" require-plt=\"" + liubaiGroupRequirePlt() + "\">\n"
                + "<path d=\"M" + format(left) + " " + format(top) + " H" + format(right) + " V" + format(bottom)
                + " H" + format(left) + " Z\" " + outerRectFillAttributes() + " stroke=\"" + outerBorderStrokeColorSvg() + "\" stroke-width=\""
                + borderStrokeWidthSvg() + "\" fill-rule=\"evenodd\" require-plt=\"" + liubaiOuterRectRequirePlt() + "\" />\n"
                + buildDashedInsetBorderPath(left, top, right, bottom, margins)
                + buildInnerOriginalBorderPath(originalWidth, originalHeight)
                + "</g>\n";
    }
    public String buildLiubaiTagGroups(String pieceMongoId,
                                         LiubaiTagAssets tagAssets,
                                         double originalWidth,
                                         double originalHeight,
                                         ExpandMargins margins) {
        if (tagAssets == null) {
            return "";
        }
        double left = -margins.left;
        double top = -margins.top;
        double right = originalWidth + margins.right;
        double bottom = originalHeight + margins.bottom;
        StringBuilder builder = new StringBuilder();
        double horizontalX = left + LIUBAI_TAG_ADJACENT_EDGE_GAP_MM;
        double verticalY = top + LIUBAI_TAG_ADJACENT_EDGE_GAP_MM;
        if (shouldBuildLiubaiTagGroup(margins.top)) {
            appendLiubaiTagGroup(builder, pieceMongoId, "horizontal", "top", tagAssets.topUrl, tagAssets.topWidthMm, tagAssets.topHeightMm, horizontalX, top);
        }
        if (shouldBuildLiubaiTagGroup(margins.bottom)) {
            appendLiubaiTagGroup(builder, pieceMongoId, "horizontal", "bottom", tagAssets.bottomUrl, tagAssets.bottomWidthMm, tagAssets.bottomHeightMm, horizontalX, bottom - tagAssets.bottomHeightMm);
        }
        if (shouldBuildLeftRightLiubaiTagGroups()) {
            if (shouldBuildLiubaiTagGroup(margins.left)) {
                appendLiubaiTagGroup(builder, pieceMongoId, "vertical", "left", tagAssets.leftUrl, tagAssets.leftWidthMm, tagAssets.leftHeightMm, left, verticalY);
            }
            if (shouldBuildLiubaiTagGroup(margins.right)) {
                appendLiubaiTagGroup(builder, pieceMongoId, "vertical", "right", tagAssets.rightUrl, tagAssets.rightWidthMm, tagAssets.rightHeightMm, right - tagAssets.rightWidthMm, verticalY);
            }
        }
        return builder.toString();
    }
    private boolean shouldBuildLiubaiTagGroup(double marginMm) {
        return expandMm() <= 0D || marginMm > 0D;
    }
    protected boolean shouldBuildLeftRightLiubaiTagGroups() {
        return p.bool("shouldBuildLeftRightLiubaiTagGroups", true);
    }
    private void appendLiubaiTagGroup(StringBuilder builder,
                                      String pieceMongoId,
                                      String direction,
                                      String suffix,
                                      String imageUrl,
                                      double widthMm,
                                      double heightMm,
                                      double x,
                                      double y) {
        builder.append("<g id=\"").append(tagMarkKeyPrefix()).append("-").append(direction).append("-").append(suffix).append("-").append(escapeAttr(pieceMongoId))
                .append("\" img=\"").append(escapeAttr(imageUrl))
                .append("\" data-source-name=\"").append(escapeAttr(sourceName(imageUrl)))
                .append("\" data-forme=\"false\" data-rotation=\"0\" require-plt=\"false\" transform=\"translate(").append(format(x)).append(" ").append(format(y)).append(")\">\n")
                .append("<path d=\"M0 0 H").append(format(widthMm)).append(" V").append(format(heightMm)).append(" H0 Z\" fill=\"#999999\" />\n")
                .append("</g>\n");
    }
    private String buildDashedInsetBorderPath(double outerLeft,
                                              double outerTop,
                                              double outerRight,
                                              double outerBottom,
                                              ExpandMargins margins) {
        double insetMm = dashedInsetFromOuterBorderMm();
        if (insetMm <= 0D) {
            return "";
        }
        double left = outerLeft + dashedInsetForMargin(margins.left, insetMm);
        double top = outerTop + dashedInsetForMargin(margins.top, insetMm);
        double right = outerRight - dashedInsetForMargin(margins.right, insetMm);
        double bottom = outerBottom - dashedInsetForMargin(margins.bottom, insetMm);
        if (right <= left || bottom <= top) {
            return "";
        }
        return "<path d=\"M" + format(left) + " " + format(top) + " H" + format(right) + " V" + format(bottom)
                + " H" + format(left) + " Z\" fill=\"none\" stroke=\"#111111\" stroke-width=\""
                + borderStrokeWidthSvg() + "\" stroke-dasharray=\"6 4\" fill-rule=\"evenodd\" />\n";
    }
    private String buildInnerOriginalBorderPath(double originalWidth, double originalHeight) {
        if (!shouldDrawInnerOriginalBorder()) {
            return "";
        }
        return "<path d=\"M0 0 H" + format(originalWidth) + " V" + format(originalHeight)
                + " H0 Z\" fill=\"none\" stroke=\"#111111\" stroke-width=\"" + borderStrokeWidthSvg() + "\" fill-rule=\"evenodd\" require-plt=\"" + liubaiOuterRectRequirePlt() + "\" />\n";
    }
    private String sourceName(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        int queryIndex = url.indexOf('?');
        String clean = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        int slashIndex = clean.lastIndexOf('/');
        return slashIndex >= 0 ? clean.substring(slashIndex + 1) : clean;
    }
    protected String escapeAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
    protected String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
    private double dashedInsetForMargin(double marginMm, double insetMm) {
        return marginMm <= 0D ? 0D : insetMm;
    }
    private String borderStrokeWidthSvg() {
        return format(DEFAULT_BORDER_STROKE_WIDTH_SVG * borderStrokeWidthScale());
    }
    protected static class LiubaiTagAssets {
        private final String text;
        private final String topUrl;
        private final String rightUrl;
        private final String bottomUrl;
        private final String leftUrl;
        private final double topWidthMm;
        private final double topHeightMm;
        private final double rightWidthMm;
        private final double rightHeightMm;
        private final double bottomWidthMm;
        private final double bottomHeightMm;
        private final double leftWidthMm;
        private final double leftHeightMm;

        private LiubaiTagAssets(String text, String topUrl, String rightUrl, String bottomUrl, String leftUrl,
                                double topWidthMm, double topHeightMm, double rightWidthMm, double rightHeightMm,
                                double bottomWidthMm, double bottomHeightMm, double leftWidthMm, double leftHeightMm) {
            this.text = text;
            this.topUrl = topUrl;
            this.rightUrl = rightUrl;
            this.bottomUrl = bottomUrl;
            this.leftUrl = leftUrl;
            this.topWidthMm = topWidthMm;
            this.topHeightMm = topHeightMm;
            this.rightWidthMm = rightWidthMm;
            this.rightHeightMm = rightHeightMm;
            this.bottomWidthMm = bottomWidthMm;
            this.bottomHeightMm = bottomHeightMm;
            this.leftWidthMm = leftWidthMm;
            this.leftHeightMm = leftHeightMm;
        }
    }
    protected static class ExpandMargins {
        /** 左边外扩量。 */
        protected double left;
        /** 右边外扩量。 */
        protected double right;
        /** 上边外扩量。 */
        protected double top;
        /** 下边外扩量。 */
        protected double bottom;

        /**
         * 构造四边外扩量。
         *
         * @param left 左边外扩量
         * @param right 右边外扩量
         * @param top 上边外扩量
         * @param bottom 下边外扩量
         */
        public ExpandMargins(double left, double right, double top, double bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

 public static LiubaiTagAssets tags(Params p){
  Object value=p.values.get("tagAssets");if(value==null)return null;
  Params t=new Params((Map<String,Object>)value);
  return new LiubaiTagAssets(t.text("text"),t.text("topUrl"),t.text("rightUrl"),t.text("bottomUrl"),t.text("leftUrl"),
   t.number("topWidthMm",0),t.number("topHeightMm",0),t.number("rightWidthMm",0),t.number("rightHeightMm",0),t.number("bottomWidthMm",0),t.number("bottomHeightMm",0),t.number("leftWidthMm",0),t.number("leftHeightMm",0));
 }
}