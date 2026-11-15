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

public class BuckleSvgKernel {
    protected static final String MARK_IMG = "https://craftstudio-mes-prod.oss-cn-hangzhou.aliyuncs.com/basetag/basetag-point.png";
    protected static final String MARK_SOURCE_NAME = "basetag-point.png";
    protected static final double MARK_SIZE_MM = 8D;
    protected static final double EDGE_OFFSET_MM = 25D;
    protected static final double OUTSIDE_BUCKLE_EDGE_OFFSET_MM = 75D;
    protected static final double MAX_BUCKLE_SPACING_MM = 300D;
    private static final String INSIDE_BUCKLE_NODE_NAME = "画内打扣";
    private static final String OUTSIDE_BUCKLE_NODE_NAME = "画外打扣";
    private static final String MARK_PATH_FILL = "#000000";
    private static final Pattern SVG_WIDTH_PATTERN = Pattern.compile("width\\s*=\\s*[\"']\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:px|mm)?\\s*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_HEIGHT_PATTERN = Pattern.compile("height\\s*=\\s*[\"']\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:px|mm)?\\s*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_OPEN_PATTERN = Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_CLOSE_PATTERN = Pattern.compile("</svg\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_GROUP_PATTERN = Pattern.compile("<g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_RECT_PATTERN = Pattern.compile("<rect\\b([^>]*)\\s*/>|<rect\\b([^>]*)>\\s*</rect\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_PATH_PATTERN = Pattern.compile("<path\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_PATH_TOKEN_PATTERN = Pattern.compile("[MmLlHhVvZz]|[-+]?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern SVG_UNSUPPORTED_PATH_COMMAND_PATTERN = Pattern.compile("[AaCcQqSsTt]");
    private static final Pattern SVG_ATTRIBUTE_PATTERN = Pattern.compile("\\s+([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\"']).*?\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_NUMBER_PATTERN = Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?");

 private final Params p; public BuckleSvgKernel(Params p){this.p=p;}
 private String markKeyPrefix(){return p.text("markKeyPrefix");}
 public record BuckleMarkPoint(String suffix,double centerX,double centerY){ public String getSuffix(){return suffix;} public double getCenterX(){return centerX;} public double getCenterY(){return centerY;} }
    public String appendBuckleMarks(String originalSvg, String pieceId, String originalContentImg,
                                     List<BuckleMarkPoint> markPoints) {
        int closeIndex = lastSvgCloseIndex(originalSvg);
        if (closeIndex < 0) {
            return originalSvg;
        }
        if (StringUtils.isBlank(pieceId)) {
            pieceId = "unknown";
        }
        String baseSvg = containsGroup(originalSvg) ? originalSvg : rebuildSvgWithOriginalGroup(originalSvg, pieceId, originalContentImg);
        closeIndex = lastSvgCloseIndex(baseSvg);
        if (closeIndex < 0) {
            return baseSvg;
        }
        String marksSvg = buildMarksSvg(pieceId, markPoints);
        return baseSvg.substring(0, closeIndex) + marksSvg + baseSvg.substring(closeIndex);
    }
    private boolean containsGroup(String svg) {
        return StringUtils.isNotBlank(svg) && SVG_GROUP_PATTERN.matcher(svg).find();
    }
    private int lastSvgCloseIndex(String svg) {
        Matcher matcher = SVG_CLOSE_PATTERN.matcher(svg);
        int closeIndex = -1;
        while (matcher.find()) {
            closeIndex = matcher.start();
        }
        return closeIndex;
    }
    private String rebuildSvgWithOriginalGroup(String originalSvg, String pieceId, String originalContentImg) {
        Matcher openMatcher = SVG_OPEN_PATTERN.matcher(originalSvg);
        if (!openMatcher.find()) {
            return originalSvg;
        }
        int closeIndex = lastSvgCloseIndex(originalSvg);
        if (closeIndex < 0 || closeIndex < openMatcher.end()) {
            return originalSvg;
        }
        String prefix = originalSvg.substring(0, openMatcher.end());
        String innerSvg = originalSvg.substring(openMatcher.end(), closeIndex).trim();
        String suffix = originalSvg.substring(closeIndex);
        return prefix + "\n" + buildOriginalContentGroup(pieceId, originalContentImg, innerSvg) + suffix;
    }
    private String buildOriginalContentGroup(String pieceId, String originalContentImg, String innerSvg) {
        String groupId = StringUtils.isBlank(pieceId) ? "original-mask" : pieceId;
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
        Matcher matcher = SVG_NUMBER_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (Exception ignored) {
            return fallback;
        }
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
    private String buildMarksSvg(String pieceId, List<BuckleMarkPoint> markPoints) {
        StringBuilder builder = new StringBuilder("\n");
        for (BuckleMarkPoint point : markPoints) {
            appendPointGroup(builder, pieceId, point);
        }
        return builder.toString();
    }
    private void appendPointGroup(StringBuilder builder, String pieceId, BuckleMarkPoint point) {
        double x = point.getCenterX() - MARK_SIZE_MM / 2D;
        double y = point.getCenterY() - MARK_SIZE_MM / 2D;
        builder.append("<g id=\"").append(markKeyPrefix()).append("-").append(point.getSuffix()).append("-").append(escapeAttr(pieceId))
                .append("\" img=\"").append(escapeAttr(MARK_IMG))
                .append("\" data-source-name=\"").append(MARK_SOURCE_NAME)
                .append("\" data-forme=\"false\" data-rotation=\"0\" require-plt=\"false\" transform=\"translate(").append(format(x)).append(" ").append(format(y)).append(")\">\n")
                .append("<path d=\"").append(buildPointPath())
                .append("\" fill=\"").append(MARK_PATH_FILL).append("\"/>\n")
                .append("</g>\n");
    }
    private String buildPointPath() {
        double radius = MARK_SIZE_MM / 2D;
        double c = radius * 0.5522847498307936D;
        return "M" + format(radius) + " 0"
                + " C" + format(radius + c) + " 0 " + format(MARK_SIZE_MM) + " " + format(radius - c) + " " + format(MARK_SIZE_MM) + " " + format(radius)
                + " C" + format(MARK_SIZE_MM) + " " + format(radius + c) + " " + format(radius + c) + " " + format(MARK_SIZE_MM) + " " + format(radius) + " " + format(MARK_SIZE_MM)
                + " C" + format(radius - c) + " " + format(MARK_SIZE_MM) + " 0 " + format(radius + c) + " 0 " + format(radius)
                + " C0 " + format(radius - c) + " " + format(radius - c) + " 0 " + format(radius) + " 0 Z";
    }
    private String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
    private String escapeAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}