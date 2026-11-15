package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalGraphicsRendererTest {
    private final GraphicsProperties properties = new GraphicsProperties("", "data", 2, 16, 30, 24, 8192, 8000000, 600, 1048576, 33554432, 20000, 64, 2147483648L, null);
    private final AwtRasterRenderer raster = new AwtRasterRenderer(new RenderLimits(properties), properties);
    private final DomSvgEditor svg = new DomSvgEditor(properties);

    private RasterSpec raster(RasterSpec.Kind kind, double width, double height, double rotation, List<String> lines) {
        return new RasterSpec(kind, width, height, 254, "#000000", "transparent", 0.1, 2, "SansSerif", true, lines, 0.1, false, rotation, null, 0);
    }
    @Test void stripesHaveExpectedPixelsAndRotatedDimensions() throws Exception {
        var out = new ByteArrayOutputStream();
        var result = raster.render(raster(RasterSpec.Kind.STRIPES, 2, 1, 0, List.of()), out);
        var image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(20, image.getWidth()); assertEquals(10, image.getHeight());
        assertEquals(Color.BLACK.getRGB(), image.getRGB(0, 0)); assertEquals(Color.WHITE.getRGB(), image.getRGB(0, 1));
        out.reset(); result = raster.render(raster(RasterSpec.Kind.STRIPES, 2, 1, 90, List.of()), out);
        assertEquals(10, result.width()); assertEquals(20, result.height());
        image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(Color.BLACK.getRGB(), image.getRGB(9, 0));
    }
    @Test void borderKeepsTransparentCentre() throws Exception {
        var out = new ByteArrayOutputStream(); raster.render(raster(RasterSpec.Kind.BORDER, 10, 10, 0, List.of()), out);
        var image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(0, image.getRGB(50, 50) >>> 24); assertNotEquals(0, image.getRGB(0, 0) >>> 24);
    }
    @Test void labelActuallyDrawsText() throws Exception {
        var out = new ByteArrayOutputStream(); raster.render(raster(RasterSpec.Kind.LABEL, 30, 10, 180, List.of("MES", "ABC")), out);
        var image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        int occupied = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) occupied++;
        assertTrue(occupied > 100); assertTrue(occupied < image.getWidth() * image.getHeight());
    }
    @Test void rejectsOversizedNonFiniteAndRotatedCanvasBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> raster.validate(raster(RasterSpec.Kind.SOLID, 2000, 2000, 0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> raster.validate(raster(RasterSpec.Kind.SOLID, Double.NaN, 1, 0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> raster.validate(raster(RasterSpec.Kind.SOLID, 800, 10, 45, List.of())));
    }
    @Test void createsMaskWithEscapedMetadataAndPltFlag() throws Exception {
        var spec = new SvgSpec(SvgSpec.Kind.MASK, null, 100, 50, null, "https://example.com/a?x=1&y=2", true, false, null, null, null);
        var out = new ByteArrayOutputStream(); svg.render(spec, out);
        String result = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(result.contains("require-plt=\"true\"")); assertTrue(result.contains("&amp;y=2")); assertTrue(result.contains("M0 0 H100 V50 H0 Z"));
    }
    @Test void editsSvgExpandsCanvasNormalizesRectAndPreservesMesMetadata() throws Exception {
        String source = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"50\" viewBox=\"0 0 100 50\"><g id=\"content\" img=\"old.png\" require-plt=\"true\"><rect width=\"100\" height=\"50\"/></g><g id=\"remove\"><path d=\"M0 0 H1\"/></g></svg>";
        var spec = new SvgSpec(SvgSpec.Kind.EDIT, source, 0, 0, new Insets(2, 3, 4, 5), null, false, true,
                List.of(new SvgMark(SvgMark.Kind.CIRCLE, "point", 10, 10, 8, 8, "mark.png", false, 0)), List.of("remove"), Map.of("content", "new.png"));
        var out = new ByteArrayOutputStream(); var result = svg.render(spec, out);
        String text = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(106, result.width()); assertEquals(58, result.height());
        assertTrue(text.contains("translate(2 3)")); assertTrue(text.contains("img=\"new.png\""));
        assertFalse(text.contains("<rect")); assertFalse(text.contains("id=\"remove\""));
        assertTrue(text.contains("require-plt=\"true\"")); assertTrue(text.contains("id=\"point\""));
    }
    @Test void rejectsEntitiesScriptsExternalStylesAndDeepNesting() {
        for (String source : List.of(
                "<!DOCTYPE svg [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'>&xxe;</svg>",
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><script>alert(1)</script></svg>",
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1' onload='alert(1)'/>",
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><image href='file:///etc/passwd'/></svg>",
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1' fill='url(https://example.com/a)'/>",
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'>" + "<g>".repeat(70) + "</g>".repeat(70) + "</svg>")) {
            assertThrows(IllegalArgumentException.class, () -> svg.validate(new SvgSpec(SvgSpec.Kind.EDIT, source, 0, 0, null, null, false, false, null, null, null)));
        }
    }
}
