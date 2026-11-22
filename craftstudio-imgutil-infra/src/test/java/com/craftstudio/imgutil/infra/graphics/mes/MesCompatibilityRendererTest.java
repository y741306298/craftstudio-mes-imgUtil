package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.graphics.*;
import com.craftstudio.imgutil.infra.persistence.FileJobRepository;
import com.craftstudio.imgutil.infra.storage.FileArtifactStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MesCompatibilityRendererTest {
    @TempDir Path directory;
    private MesCompatibilityRenderer renderer;
    private FileArtifactStore store;
    @BeforeEach void setup()throws Exception{
        var properties=new GraphicsProperties("",directory.toString(),2,16,30,24,8192,8000000,600,1048576,33554432,20000,64,2147483648L,null);
        store=new FileArtifactStore(properties);
        renderer=new MesCompatibilityRenderer(new RenderLimits(properties),properties,store,new FileJobRepository(properties,new JsonMapper()),new MesSvgRenderer(new DomSvgEditor(properties)));
    }
    @AfterEach void close(){store.close();}
    private byte[] render(String operation,Map<String,Object> parameters)throws Exception{
        var out=new ByteArrayOutputStream();renderer.render(new MesSpec(operation,parameters),out);return out.toByteArray();
    }
    @Test void preservesPixelBasedSolidAndStripeMarks()throws Exception{
        var black=ImageIO.read(new ByteArrayInputStream(render("BLACK",Map.of("width",8,"height",3))));
        assertEquals(8,black.getWidth());assertEquals(3,black.getHeight());assertEquals(Color.BLACK.getRGB(),black.getRGB(4,1));
        var stripes=ImageIO.read(new ByteArrayInputStream(render("STRIPES_PX",Map.of("width",6,"height",4,"color",new Color(204,204,204).getRGB()))));
        assertEquals(new Color(204,204,204).getRGB(),stripes.getRGB(0,0));assertEquals(Color.WHITE.getRGB(),stripes.getRGB(0,1));
    }
    @Test void preservesSublimationBorderResolutionAndTransparency()throws Exception{
        var image=ImageIO.read(new ByteArrayInputStream(render("SUBLIMATION_BORDER",Map.of("width",100,"height",50))));
        assertEquals((int)Math.ceil(100D*36D/25.4D),image.getWidth());assertEquals(0,image.getRGB(50,30)>>>24);assertNotEquals(0,image.getRGB(0,0)>>>24);
    }
    @Test void rendersAllDedicatedLabelAndBorderFamilies()throws Exception{
        for(String operation:List.of("CIRCLE_TAG","CROSS_TAG","DOUBLE_TAG","CAIFU_TAG","LIUBAI_TAG","LIUBAI_BORDER","SPLICE_TEXT","MARK_TEXT","MARK_TWO_LINES","NESTING_MARKER")){
            var params=new HashMap<String,Object>();
            params.putAll(Map.of("stripWidth",300,"stripHeight",30,"width",100,"height",20,"text","MES test","elementA","A-001","elementB","plate.plt","elementAExtInfos",List.of("<b>REDO</b>"),"edgeSize",10));
            var image=ImageIO.read(new ByteArrayInputStream(render(operation,params)));assertNotNull(image,operation);assertTrue(image.getWidth()>0,operation);
        }
    }

    @Test
    void preservesLegacyBoldFontForTextItCanDisplay() {
        Font legacy = new Font(Font.SANS_SERIF, Font.BOLD, 24);
        assertSame(legacy, LegacySupport.resolveMarkFont(legacy, "MES 1234"));
    }

    @Test
    void selectsAnInstalledFontThatCanDisplayTheEntireChineseMark() {
        String text = "留白 1234.tif";
        var environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        var fonts = Arrays.stream(environment.getAvailableFontFamilyNames(Locale.ROOT))
                .map(family -> new Font(family, Font.BOLD, 24)).toList();
        Assumptions.assumeTrue(fonts.stream().anyMatch(font -> font.canDisplayUpTo(text) < 0));
        var preferred = fonts.stream().filter(font -> font.canDisplay('A') && font.canDisplayUpTo(text) >= 0).findFirst();
        Assumptions.assumeTrue(preferred.isPresent());

        Font selected = LegacySupport.resolveMarkFont(preferred.get(), text);
        assertEquals(-1, selected.canDisplayUpTo(text));
        assertEquals(24, selected.getSize());
        assertTrue(selected.isBold());
    }

    @Test
    void rejectsAProcessMarkWhenNoFontCanRenderItsText() {
        String unrenderable = new String(Character.toChars(0x10FFFF));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacySupport.boldMarkFont(unrenderable, 24));
        assertTrue(failure.getMessage().contains("U+10FFFF"));
    }

    @Test
    void rendersDistinctChineseGlyphsForProcessMarks() throws Exception {
        Assumptions.assumeTrue(Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts())
                .anyMatch(font -> font.canDisplayUpTo("留白") < 0));
        for (String operation : List.of("LIUBAI_TAG", "SPLICE_TEXT", "MARK_TEXT", "MARK_TWO_LINES")) {
            var first = Map.<String, Object>of("width", 100, "height", 32, "text", "留");
            var second = Map.<String, Object>of("width", 100, "height", 32, "text", "白");
            assertFalse(Arrays.equals(render(operation, first), render(operation, second)), operation);
        }
    }

    @Test
    void liubaiUsesTheElementFFontForMeasurementAndDrawing() throws Exception {
        int height = (int) Math.ceil(10 * 300 / 25.4);
        int padding = (int) Math.ceil(300 / 25.4);
        Font font = new Font("Source Han Sans SC VF", Font.PLAIN, (int) Math.floor(height * 0.72));
        String text = font.canDisplayUpTo("留白 AaWwx 0123.tif") < 0 ? "留白 AaWwx 0123.tif" : "AaWwx 0123.tif";
        BufferedImage tag = ImageIO.read(new ByteArrayInputStream(render("LIUBAI_TAG", Map.of("text", text, "edgeSize", 10))));

        BufferedImage reference = new BufferedImage(tag.getWidth(), height, BufferedImage.TYPE_INT_ARGB);
        var graphics = reference.createGraphics();
        try {
            graphics.setFont(font);
            assertEquals(graphics.getFontMetrics().stringWidth(text) + 2 * padding, tag.getWidth());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var metrics = graphics.getFontMetrics();
            int baseline = Math.max(metrics.getAscent(), Math.min(height - 1,
                    (height + metrics.getAscent() - metrics.getDescent()) / 2));
            graphics.setColor(Color.BLACK);
            graphics.drawString(text, padding, baseline);
        } finally {
            graphics.dispose();
        }
        assertEquals(height, tag.getHeight());
        int width = tag.getWidth();
        assertArrayEquals(reference.getRGB(0, 0, width, height, null, 0, width),
                tag.getRGB(0, 0, width, height, null, 0, width));
    }

    private Map<String,Object> doubleLabel(boolean rotate) {
        return new HashMap<>(Map.of("stripWidth",300,"stripHeight",30,"elementA","A-001",
                "elementB","001.plt","rotate180",rotate));
    }
    @Test void legacyArrowUrlsInDoubleLabelQrSlotKeepOriginalPixels() throws Exception {
        for(boolean rotate:List.of(false,true)) {
            var parameters=doubleLabel(rotate);
            byte[] original=render("DOUBLE_TAG",parameters);
            for(String arrow:List.of("leftarrow","rightarrow")) {
                parameters.put("qrDataUri","https://craftstudio-mes-test.oss-cn-hangzhou.aliyuncs.com/basetag/basetag-"+arrow+".png");
                assertArrayEquals(original,render("DOUBLE_TAG",parameters));
            }
        }
        var parameters=doubleLabel(false);parameters.put("qrDataUri","http://127.0.0.1:1/arbitrary.png");
        assertThrows(IllegalStateException.class,()->render("DOUBLE_TAG",parameters));
    }
    @Test void allQrLabelFamiliesStillRenderDecodableQrContentAndInlinePng() throws Exception {
        String content="PLATE-001.plt";
        String qrContent="qr-content:"+Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var matrix=new com.google.zxing.qrcode.QRCodeWriter().encode(content,com.google.zxing.BarcodeFormat.QR_CODE,512,512);
        var qrImage=new java.awt.image.BufferedImage(512,512,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)qrImage.setRGB(x,y,matrix.get(x,y)?Color.BLACK.getRGB():Color.WHITE.getRGB());
        var png=new ByteArrayOutputStream();ImageIO.write(qrImage,"png",png);qrImage.flush();
        String inline="data:image/png;base64,"+Base64.getEncoder().encodeToString(png.toByteArray());
        for(String operation:List.of("DOUBLE_TAG","CIRCLE_TAG","CROSS_TAG")) {
            for(String input:List.of(qrContent,inline)) {
                var parameters=doubleLabel(false);parameters.put("qrDataUri",input);
                var image=ImageIO.read(new ByteArrayInputStream(render(operation,parameters)));
                int qrLeft=(int)Math.round(10*300/25.4),qrSize=(int)Math.round(15*300/25.4);
                if(!operation.equals("DOUBLE_TAG"))qrLeft+=(int)Math.round(30*300/25.4);
                int qrTop=image.getHeight()-(int)Math.round(17*300/25.4);
                int x=qrLeft-10,y=qrTop-10,w=qrSize+20,h=qrSize+20;
                int[] pixels=image.getRGB(x,y,w,h,null,0,w);
                var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(w,h,pixels)));
                assertEquals(content,new com.google.zxing.MultiFormatReader().decode(bitmap,Map.of(com.google.zxing.DecodeHintType.PURE_BARCODE,true)).getText(),operation);
                image.flush();
            }
        }
    }
    @Test void preservesNegativeViewBoxAndPltPolicyDuringLiubaiExpansion()throws Exception{
        String source="<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50' viewBox='0 0 100 50'><rect width='100' height='50'/></svg>";
        String result=new String(render("LIUBAI_EXPAND",Map.of("originalSvg",source,"pieceMongoId","piece","markPngUrl","border.png","originalContentImg","image.tif","originalWidth",100,"originalHeight",50,"margins",Map.of("left",20,"right",20,"top",20,"bottom",20),"specName","2cm","tagMarkKeyPrefix","liubai-tag")),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(result.contains("viewBox=\"-20 -20 140 90\""));assertTrue(result.contains("img=\"border.png\""));assertTrue(result.contains("id=\"piece\""));assertTrue(result.contains("require-plt=\"true\""));
    }
    @Test void appendsBucklePointsAndPreservesOriginalImage()throws Exception{
        String source="<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'><path d='M0 0 H100 V50 H0 Z'/></svg>";
        String result=new String(render("BUCKLE_APPEND",Map.of("originalSvg",source,"pieceId","piece","originalContentImg","image.tif","markKeyPrefix","buckle","markPoints",List.of(Map.of("suffix","top-left","centerX",25,"centerY",25)))),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(result.contains("buckle-top-left-piece"));assertTrue(result.contains("img=\"image.tif\""));assertTrue(result.contains("translate(21 21)"));
    }
    @Test void mirrorCleanupPreservesLegacyTextAndUploadsOnlyChangedContent() throws Exception {
        String prefix = "<svg xmlns='http://www.w3.org/2000/svg'><g style='fill:var(--ink)'><rect/></g>";
        String original = prefix + "</svg>";
        var out = new ByteArrayOutputStream();
        RenderResult unchanged = renderer.render(new MesSpec("NESTED_MIRROR_CLEAN", Map.of("source", original)), out);
        assertEquals(original, out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(unchanged.publishToOss());

        out.reset();
        String source = prefix + "<g id='ADHESIVE-SPLICE-mark'><rect/></g></svg>";
        RenderResult changed = renderer.render(new MesSpec("NESTED_MIRROR_CLEAN", Map.of("source", source)), out);
        assertEquals(original, out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(changed.publishToOss());
        assertThrows(IllegalArgumentException.class, () -> render("SVG_SAVE", Map.of("source", original)));
    }

    @Test void sublimationMirrorCleanupRunsBothLegacyPasses() throws Exception {
        String source = """
                <svg xmlns="http://www.w3.org/2000/svg">
                  <g id="sublimation-original-piece" style="stroke:var(--ink)"><rect/></g>
                  <g id="panel-splice-mark"><rect/></g>
                  <g id="sublimation-border-piece"><rect/></g>
                </svg>
                """;
        String result = new String(render("NESTED_MIRROR_CLEAN", Map.of("source", source, "sublimation", true)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(result.contains("sublimation-original-piece"));
        assertTrue(result.contains("stroke:var(--ink)"));
        assertFalse(result.contains("panel-splice-mark"));
        assertFalse(result.contains("sublimation-border-piece"));
    }

    @Test void rejectsUnsafeSvgUnknownOperationsAndInvalidDimensions()throws Exception{
        assertThrows(IllegalArgumentException.class,()->renderer.validate(new MesSpec("CLASS_FOR_NAME",Map.of())));
        assertThrows(IllegalArgumentException.class,()->render("BLACK",Map.of("width",0,"height",1)));
        assertThrows(IllegalArgumentException.class,()->render("SVG_SAVE",Map.of("source","<svg xmlns='http://www.w3.org/2000/svg'><script>bad</script></svg>")));
    }

    @Test void batchedSpliceMarksMatchLegacyOutputAndKeepOriginalMetadata() throws Exception {
        var marks = List.of(
                Map.<String,Object>of("id", "stripe-a", "img", "https://bucket/stripe.png?x=1&y=2", "x", 0.007049, "y", 1169.919444, "width", 6, "height", 1),
                Map.<String,Object>of("id", "text-b", "img", "https://bucket/text.png", "x", 1543.494444, "y", 1181.919444, "width", 56, "height", 8));
        String fragments = "\n";
        for (var mark : marks) fragments += new String(render("SVG_RECT_GROUP", mark), java.nio.charset.StandardCharsets.UTF_8);
        for (String inner : List.of("<rect x='0' y='0' width='100' height='50'/>",
                "<g id='original' img='image.tif' require-plt='true' data-forme='true'><path d='M0 0 H100 V50 H0 Z'/></g>")) {
            String source = "<svg xmlns='http://www.w3.org/2000/svg' width='140' height='90' viewBox='-20 -20 140 90'>" + inner + "</svg>";
            var params = new HashMap<String,Object>(Map.of("originalSvg", source, "pieceMongoId", "piece", "originalContentImg", "image.tif"));
            params.put("marksSvg", fragments);
            byte[] legacy = render("SPLICE_APPEND", params);
            params.remove("marksSvg"); params.put("marks", marks);
            byte[] batch = render("SPLICE_MARKS_APPEND", params);
            assertArrayEquals(legacy, batch);
            String svg = new String(batch, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(svg.contains("require-plt=\"false\""));
            assertTrue(svg.contains("data-source-name=\"stripe.png\""));
            assertTrue(svg.contains("viewBox='-20 -20 140 90'"));
        }
    }

    @Test void rejectsInvalidSpliceBatchesAsAWhole() {
        var valid = Map.<String,Object>of("id", "mark", "img", "stripe.png", "x", 0, "y", 0, "width", 6, "height", 1);
        var badSize = new HashMap<>(valid); badSize.put("width", -1);
        var badCoordinate = new HashMap<>(valid); badCoordinate.put("x", Double.NaN);
        var unsafe = new HashMap<>(valid); unsafe.put("img", "javascript:bad");
        for (var marks : List.of(List.of(), Collections.nCopies(33, valid), List.of(valid, valid),
                List.of(valid, badSize), List.of(badCoordinate), List.of(unsafe))) {
            assertThrows(IllegalArgumentException.class, () -> render("SPLICE_MARKS_APPEND", Map.of(
                    "originalSvg", "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'></svg>", "marks", marks)));
        }
    }
}
