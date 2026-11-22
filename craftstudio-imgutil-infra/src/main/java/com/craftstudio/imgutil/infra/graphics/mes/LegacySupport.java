package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.infra.graphics.RenderLimits;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Validate dimensions and release legacy image allocations within one isolated task. */
public abstract class LegacySupport implements AutoCloseable {
    private final List<BufferedImage> allocated = new ArrayList<>();
    private RenderLimits limits;
    public void configure(RenderLimits limits) { this.limits=limits; }

    protected BufferedImage canvas(int width, int height, int type) {
        limits.checkDimensions(width,height);
        BufferedImage image=new BufferedImage(width,height,type);allocated.add(image);return image;
    }
    public BufferedImage decodeDataUri(String uri) {
        if(uri==null || uri.isBlank()) return null;
        if(uri.startsWith("qr-content:")){
            if(uri.length()>8192)throw new IllegalArgumentException("二维码内容过长");
            try{
                String content=new String(Base64.getDecoder().decode(uri.substring(11)),java.nio.charset.StandardCharsets.UTF_8);
                var matrix=new com.google.zxing.qrcode.QRCodeWriter().encode(content,com.google.zxing.BarcodeFormat.QR_CODE,512,512);
                var image=canvas(512,512,BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,matrix.get(x,y)?java.awt.Color.BLACK.getRGB():java.awt.Color.WHITE.getRGB());
                return image;
            }catch(Exception e){throw new IllegalArgumentException("二维码生成失败",e);}
        }
        if(!uri.startsWith("data:image/png;base64,") || uri.length()>1048576) throw new IllegalArgumentException("仅允许受限 PNG data URI");
        byte[] bytes=Base64.getDecoder().decode(uri.substring(uri.indexOf(',')+1));
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input); if(!readers.hasNext())throw new IllegalArgumentException("无效 PNG");
            var reader=readers.next();
            try {
                reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);limits.checkDimensions(w,h);
                BufferedImage image=reader.read(0);allocated.add(image);return image;
            } finally { reader.dispose(); }
        } catch(IOException e){throw new IllegalArgumentException("PNG 解码失败",e);}
    }
    public BufferedImage readPng(java.nio.file.Path path) throws IOException {
        try(var input=ImageIO.createImageInputStream(path.toFile())) {
            var reader=ImageIO.getImageReaders(input).next();
            try { reader.setInput(input);limits.checkDimensions(reader.getWidth(0),reader.getHeight(0));var image=reader.read(0);allocated.add(image);return image; }
            finally {reader.dispose();}
        }
    }
    public BufferedImage newCanvas(int w,int h,int type){return canvas(w,h,type);}
    @Override public void close(){allocated.forEach(BufferedImage::flush);allocated.clear();}
}
