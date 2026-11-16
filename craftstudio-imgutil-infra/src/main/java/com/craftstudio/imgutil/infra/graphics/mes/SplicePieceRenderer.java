package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.graphics.RenderLimits;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** One worker, one deadline and one callback for every asset and mark of a piece. */
final class SplicePieceRenderer {
    private static final List<String> ASSETS = List.of("dark-horizontal", "dark-vertical", "stripe-horizontal",
            "stripe-vertical", "yellow-horizontal", "yellow-vertical", "gray-horizontal", "gray-vertical");
    private final RenderLimits limits;
    private final GraphicsProperties properties;
    private final ArtifactStore store;
    private final MesSvgRenderer svg;
    SplicePieceRenderer(RenderLimits limits, GraphicsProperties properties, ArtifactStore store, MesSvgRenderer svg) {
        this.limits = limits; this.properties = properties; this.store = store; this.svg = svg;
    }
    RenderResult render(String taskId, Map<String,Object> parameters, OutputStream output) throws Exception {
        if (taskId == null) throw new IllegalArgumentException("零件任务编号不能为空");
        Params p = new Params(parameters);
        String text = p.text("groupText"), base = p.text("artifactBaseUrl").replaceAll("/+$", "");
        URI uri = URI.create(base);
        if (text.isBlank() || text.length() > 512 || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("零件文字或 OSS 访问域名无效");
        }
        // Validate the complete SVG plan before publishing any assets.
        Map<String,String> objects = new LinkedHashMap<>();
        ASSETS.forEach(key -> objects.put(key, key + ".png"));
        buildSvg(p, base, objects);
        objects.clear();
        long bytes = 0;
        for (String key : ASSETS) {
            RenderLimits.checkpoint();
            Path temporary = store.createTemporary(taskId);
            try (var kernel = new SpliceRasterKernel()) {
                kernel.configure(limits);
                BufferedImage image = image(kernel, key, text);
                try (var out = new BoundedOutput(Files.newOutputStream(temporary), properties.maxArtifactBytes() - bytes)) {
                    if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("PNG 编码器不可用");
                }
                bytes += Files.size(temporary);
                RenderLimits.checkpoint();
                Artifact artifact = store.publishNamed(taskId, key, temporary,
                        new RenderResult("image/png", "png", image.getWidth(), image.getHeight(), "px"));
                if (artifact.objectName() == null || artifact.objectName().isBlank()) {
                    throw new IllegalArgumentException("零件图形任务需开启 graphics.oss.enabled");
                }
                objects.put(key, artifact.objectName());
            } finally { Files.deleteIfExists(temporary); }
        }
        byte[] result = buildSvg(p, base, objects);
        if (result.length > properties.maxArtifactBytes() - bytes) throw new IllegalArgumentException("零件文件总大小超过上限");
        RenderLimits.checkpoint(); output.write(result);
        return new RenderResult("image/svg+xml", "svg", 0, 0, "mm", objects);
    }
    private byte[] buildSvg(Params p, String base, Map<String,String> objects) throws Exception {
        if (!(p.values.get("marks") instanceof List<?> values) || values.size() > 32) throw new IllegalArgumentException("零件标记数量无效");
        List<Map<String,Object>> marks = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("零件标记参数无效");
            Map<String,Object> mark = new LinkedHashMap<>(); raw.forEach((key,item) -> mark.put(key.toString(), item));
            String asset = Objects.toString(mark.remove("assetKey"), "");
            if (!objects.containsKey(asset)) throw new IllegalArgumentException("未知零件素材: " + asset);
            mark.put("img", base + "/" + objects.get(asset)); marks.add(mark);
        }
        Map<String,Object> params = new LinkedHashMap<>(p.values); params.put("marks", marks);
        if (marks.isEmpty()) params.put("source", p.text("originalSvg"));
        var out = new ByteArrayOutputStream();
        svg.render(new MesSpec(marks.isEmpty() ? "SVG_SAVE" : "SPLICE_MARKS_APPEND", params), out);
        return out.toByteArray();
    }
    private BufferedImage image(SpliceRasterKernel kernel, String key, String text) {
        if (key.equals("dark-horizontal")) return kernel.createStripeImage(6, 1, Color.BLACK);
        if (key.equals("dark-vertical")) return kernel.createStripeImage(1, 6, Color.BLACK);
        BufferedImage source = key.startsWith("stripe-") ? kernel.createStripeImage(6, 1, new Color(204,204,204))
                : kernel.createTextImage(Math.max(24D, text.length() * 8D), 8D, text,
                        key.startsWith("yellow-") ? new Color(255,255,204) : new Color(204,204,204));
        if (!key.endsWith("vertical")) return source;
        // Match the existing TRANSFORM operation, including its rounding and translation.
        double radians = Math.toRadians(90), sin = Math.abs(Math.sin(radians)), cos = Math.abs(Math.cos(radians));
        int w = source.getWidth(), h = source.getHeight();
        int nw = Math.max(1, (int)Math.ceil(w*cos+h*sin-1e-8)), nh = Math.max(1, (int)Math.ceil(h*cos+w*sin-1e-8));
        BufferedImage rotated = kernel.newCanvas(nw, nh, BufferedImage.TYPE_INT_ARGB);
        var g = rotated.createGraphics();
        try { g.setComposite(AlphaComposite.Src); g.translate((nw-w)/2D, (nh-h)/2D); g.rotate(radians,w/2D,h/2D); g.drawImage(source,0,0,null); }
        finally { g.dispose(); }
        return rotated;
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private long remaining;
        BoundedOutput(OutputStream output, long maximum) { super(output); remaining = maximum; }
        private void check(int size) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("任务已取消");
            if (size > remaining) throw new IOException("零件文件总大小超过上限"); remaining -= size;
        }
        @Override public void write(int value) throws IOException { check(1); out.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { check(length); out.write(value,offset,length); }
    }
}
