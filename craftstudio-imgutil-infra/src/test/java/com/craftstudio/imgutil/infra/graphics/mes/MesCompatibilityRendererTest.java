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
