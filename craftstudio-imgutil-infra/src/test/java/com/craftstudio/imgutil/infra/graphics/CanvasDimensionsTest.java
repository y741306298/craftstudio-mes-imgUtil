package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.graphics.mes.SpliceRasterKernel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanvasDimensionsTest {
    @TempDir Path directory;
    private GraphicsProperties properties(int dimension, long pixels) {
        return new GraphicsProperties("",directory.toString(),1,1,30,24,dimension,pixels,600,
                1048576,33554432,20000,64,null);
    }
    @Test void rendersAboveLegacyLimitsWithoutAllocatingAnUnnecessarilyLargeImage() throws Exception {
        var properties=properties(4,4);
        var renderer=new AwtRasterRenderer(new RenderLimits(properties),properties);
        // 9000x2 pixels exceeds the former 8192 side limit; the tiny legacy settings also
        // verify that neither width, total pixels nor the rotated height are capped.
        var spec=new RasterSpec(RasterSpec.Kind.SOLID,900,0.2,254,"#000000","transparent",0,0,
                "SansSerif",false,List.of(),0,false,90,null,0);
        var out=new ByteArrayOutputStream();
        var result=renderer.render(spec,out);
        assertEquals(2,result.width());assertEquals(9000,result.height());
        var image=ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2,image.getWidth());assertEquals(9000,image.getHeight());image.flush();
    }
    @Test void legacyDrawingAndPngDecodingHaveNoPerImageOrAccumulatedPixelCap() throws Exception {
        var limits=new RenderLimits(properties(4,4));
        try(var kernel=new SpliceRasterKernel()) {
            kernel.configure(limits);
            BufferedImage source=null;
            for(int i=0;i<5;i++)source=kernel.newCanvas(20,10,BufferedImage.TYPE_INT_ARGB);
            var png=new ByteArrayOutputStream();ImageIO.write(source,"png",png);
            var decoded=kernel.decodeDataUri("data:image/png;base64,"+Base64.getEncoder().encodeToString(png.toByteArray()));
            assertEquals(20,decoded.getWidth());assertEquals(10,decoded.getHeight());
            Path file=directory.resolve("source.png");ImageIO.write(source,"png",file.toFile());
            assertEquals(20,kernel.readPng(file).getWidth());
            var rotated=kernel.rotateImageByAngle(source,90);assertTrue(rotated.getHeight()>=20);
            assertThrows(IllegalArgumentException.class,()->kernel.newCanvas(0,10,BufferedImage.TYPE_INT_ARGB));
        }
    }
    @Test void omittedLegacyPixelSettingsDoNotPreventStartupAndPositiveDimensionsRemainRequired() {
        var limits=new RenderLimits(properties(0,0));
        assertDoesNotThrow(()->limits.checkDimensions(100000,100000)); // Validation only, no allocation.
        assertThrows(IllegalArgumentException.class,()->limits.checkDimensions(0,1));
        assertThrows(IllegalArgumentException.class,()->limits.checkDimensions(1,-1));
    }
}
