package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.graphics.*;
import com.craftstudio.imgutil.infra.storage.FileArtifactStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SplicePieceRendererTest {
    @TempDir Path directory;
    private GraphicsProperties properties;
    private FileArtifactStore local;
    private final Map<String,Path> published = new LinkedHashMap<>();
    private SplicePieceRenderer renderer;
    private RenderLimits limits;
    @BeforeEach void setup() throws Exception {
        properties = new GraphicsProperties("key", directory.toString(), 1, 16, 30, 24, 8192, 8000000, 600,
                1048576, 33554432, 20000, 64, 2147483648L, null);
        local = new FileArtifactStore(properties); limits = new RenderLimits(properties);
        ArtifactStore store = new ArtifactStore() {
            public Path createTemporary(String id) throws Exception { return local.createTemporary(id); }
            public Artifact publish(String id, Path path, RenderResult result) throws Exception { return local.publish(id,path,result); }
            public Artifact publishNamed(String id, String name, Path path, RenderResult result) throws Exception {
                Artifact a = local.publishNamed(id,name,path,result);
                published.put(name, directory.resolve("artifacts/" + id + "/" + name + ".png"));
                return new Artifact(a.filename(),a.mediaType(),a.bytes(),a.sha256(),a.width(),a.height(),a.unit(),null,"imgutil/"+id+"/"+a.filename());
            }
            public Path resolve(String id,String name) { return local.resolve(id,name); }
            public void delete(String id) throws Exception { local.delete(id); }
            public boolean hasCapacity() { return true; }
        };
        renderer = new SplicePieceRenderer(limits,properties,store,new MesSvgRenderer(new DomSvgEditor(properties)));
    }
    @AfterEach void close() { local.close(); }
    private Map<String,Object> parameters(List<?> marks) {
        return Map.of("groupText","618#1-2", "artifactBaseUrl","https://bucket.example.com", "pieceMongoId","piece",
                "originalContentImg","image.tif", "originalSvg","<svg xmlns='http://www.w3.org/2000/svg' width='100' height='200'><path d='M0 0 H100 V200 H0 Z'/></svg>", "marks",marks);
    }
    @Test void createsAllEightAssetsWithLegacyPixelsAndEmbedsTheirUrls() throws Exception {
        var out = new ByteArrayOutputStream();
        RenderResult result = renderer.render("piece", parameters(List.of(Map.of("id","mark","assetKey","yellow-vertical",
                "x",12.5,"y",20,"width",8,"height",56))), out);
        assertEquals(8,result.assets().size()); assertEquals(8,published.size());
        for (var entry : published.entrySet()) {
            try (var kernel = new SpliceRasterKernel()) {
                kernel.configure(limits);
                String key = entry.getKey();
                BufferedImage expected;
                if (key.startsWith("dark-")) expected = kernel.createStripeImage(key.endsWith("vertical")?1:6,key.endsWith("vertical")?6:1,Color.BLACK);
                else {
                    expected = key.startsWith("stripe-") ? kernel.createStripeImage(6,1,new Color(204,204,204))
                            : kernel.createTextImage(56,8,"618#1-2",key.startsWith("yellow-")?new Color(255,255,204):new Color(204,204,204));
                    // Encode/decode and rotate as the former remote TRANSFORM path did.
                    var png = new ByteArrayOutputStream(); ImageIO.write(expected,"png",png);
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
                    int w=decoded.getWidth(),h=decoded.getHeight(),nw=key.endsWith("vertical")?h:w,nh=key.endsWith("vertical")?w:h;
                    expected=kernel.newCanvas(nw,nh,BufferedImage.TYPE_INT_ARGB);
                    var g=expected.createGraphics();
                    try { g.setComposite(AlphaComposite.Src);g.translate((nw-w)/2D,(nh-h)/2D);g.rotate(key.endsWith("vertical")?Math.PI/2:0,w/2D,h/2D);g.drawImage(decoded,0,0,null); }
                    finally { g.dispose();decoded.flush(); }
                }
                BufferedImage actual=ImageIO.read(entry.getValue().toFile());
                assertEquals(expected.getWidth(),actual.getWidth(),key);assertEquals(expected.getHeight(),actual.getHeight(),key);
                assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),
                        actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth()),key);
                actual.flush();
            }
        }
        String svg=out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(svg.contains("img=\"https://bucket.example.com/imgutil/piece/yellow-vertical.png\""));
        assertTrue(svg.contains("id=\"piece\""));assertTrue(svg.contains("require-plt=\"true\""));
        local.delete("piece");assertFalse(Files.exists(directory.resolve("artifacts/piece")));
    }
    @Test void invalidPlanPublishesNoAssetsAndOldArtifactJsonStaysCompatible() {
        assertThrows(IllegalArgumentException.class,()->renderer.render("piece",parameters(List.of(Map.of("assetKey","unknown"))),new ByteArrayOutputStream()));
        assertTrue(published.isEmpty());
        var mapper=new tools.jackson.databind.json.JsonMapper();
        var old=new Artifact("result.png","image/png",1,"hash",1,1,"px","download","object");
        assertFalse(mapper.writeValueAsString(old).contains("assets"));
        assertTrue(mapper.readValue(mapper.writeValueAsString(old),Artifact.class).assets().isEmpty());
    }
}
