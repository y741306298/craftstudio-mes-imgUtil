package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.graphics.RenderLimits;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.Set;

@Component
public class MesCompatibilityRenderer {
    private static final Set<String> PNG = Set.of("BLACK","CIRCLE_TAG","CROSS_TAG","DOUBLE_TAG","CAIFU_TAG","SPLICE_TEXT","MARK_TEXT","MARK_TWO_LINES","STRIPES_PX","LIUBAI_BORDER","LIUBAI_TAG","TRANSFORM","SUBLIMATION_BORDER","NESTING_MARKER");
    private final RenderLimits limits;
    private final GraphicsProperties properties;
    private final ArtifactStore artifacts;
    private final JobRepository jobs;
    private final MesSvgRenderer svg;
    public MesCompatibilityRenderer(RenderLimits limits, GraphicsProperties properties, ArtifactStore artifacts, JobRepository jobs, MesSvgRenderer svg) {
        this.limits=limits;this.properties=properties;this.artifacts=artifacts;this.jobs=jobs;this.svg=svg;
    }
    public void validate(MesSpec spec) {
        if(spec.operation()==null)throw new IllegalArgumentException("缺少 MES 图形操作名");
        if(!PNG.contains(spec.operation()) && !svg.supports(spec.operation()))throw new IllegalArgumentException("未知 MES 图形操作");
        if(spec.parameters().size()>80)throw new IllegalArgumentException("参数过多");
        Params p=new Params(spec.parameters());
        for(String key: new String[]{"width","height","stripWidth","stripHeight","edgeSize"}) {
            if(spec.parameters().containsKey(key) && p.number(key,0)<=0)throw new IllegalArgumentException("尺寸必须大于零: "+key);
        }
    }
    public RenderResult render(MesSpec spec,OutputStream output)throws Exception {
        validate(spec);RenderLimits.checkpoint();
        if(svg.supports(spec.operation()))return svg.render(spec,output);
        Params p=new Params(spec.parameters());
        LegacySupport kernel=switch(spec.operation()) {
            case "CIRCLE_TAG" -> new CircleQrLayoutBuildServiceKernel();
            case "CROSS_TAG" -> new CrossQrLayoutBuildServiceKernel();
            case "DOUBLE_TAG" -> new DoubleSideMountingLayoutBuildServiceKernel();
            case "CAIFU_TAG" -> new CaifuTagKernel();
            case "SPLICE_TEXT","STRIPES_PX" -> new SpliceRasterKernel();
            case "MARK_TEXT","MARK_TWO_LINES" -> new SpliceMarkKernel();
            case "LIUBAI_BORDER","LIUBAI_TAG" -> new LiubaiRasterKernel(p);
            default -> new LegacySupport(){};
        };
        kernel.configure(limits,properties.maxPixels());
        try(kernel) {
            BufferedImage image=switch(spec.operation()) {
                case "CIRCLE_TAG" -> ((CircleQrLayoutBuildServiceKernel)kernel).render(p.text("businessId"),p.text("manufacturerMetaId"),p.text("typesettingId"),p.text("elementA"),p.strings("elementAExtInfos"),p.text("commonOrderId"),p.text("elementB"),p.text("qrDataUri"),decimal(p,"stripWidth"),BigDecimal.valueOf(p.number("tagContentMaxWidth",p.number("stripWidth",1))),decimal(p,"stripHeight"),p.bool("rotate180",false),p.bool("hasRedoProductionPiece",false));
                case "CROSS_TAG" -> ((CrossQrLayoutBuildServiceKernel)kernel).render(p.text("businessId"),p.text("manufacturerMetaId"),p.text("typesettingId"),p.text("elementA"),p.strings("elementAExtInfos"),p.text("commonOrderId"),p.text("elementB"),p.text("qrDataUri"),decimal(p,"stripWidth"),BigDecimal.valueOf(p.number("tagContentMaxWidth",p.number("stripWidth",1))),decimal(p,"stripHeight"),p.bool("rotate180",false),p.bool("hasRedoProductionPiece",false));
                case "DOUBLE_TAG" -> ((DoubleSideMountingLayoutBuildServiceKernel)kernel).render(p.text("businessId"),p.text("manufacturerMetaId"),p.text("typesettingId"),p.text("elementA"),p.strings("elementAExtInfos"),p.text("commonOrderId"),p.text("elementB"),p.text("qrDataUri"),decimal(p,"stripWidth"),decimal(p,"stripHeight"),p.bool("rotate180",false));
                case "CAIFU_TAG" -> ((CaifuTagKernel)kernel).render(p.text("elementA"),p.integer("stripWidth",1),p.bool("rotate180",false));
                case "SPLICE_TEXT" -> ((SpliceRasterKernel)kernel).createTextImage(p.number("width",1),p.number("height",1),p.text("text"),new Color(p.integer("color",Color.WHITE.getRGB()),true));
                case "STRIPES_PX" -> ((SpliceRasterKernel)kernel).createStripeImage((int)Math.ceil(p.number("width",1)),(int)Math.ceil(p.number("height",1)),new Color(p.integer("color",Color.BLACK.getRGB()),true));
                case "MARK_TEXT" -> ((SpliceMarkKernel)kernel).createSingleLineTextImage(p.integer("width",1),p.integer("height",1),p.text("text"),new Color(p.integer("color",Color.WHITE.getRGB()),true));
                case "MARK_TWO_LINES" -> ((SpliceMarkKernel)kernel).createRotatedTwoLineTextPng(p.number("width",1),p.number("height",1),p.text("text"));
                case "LIUBAI_BORDER" -> ((LiubaiRasterKernel)kernel).createBorderPng(p.number("width",1),p.number("height",1),new LiubaiRasterKernel.ExpandMargins(p.number("left",0),p.number("right",0),p.number("top",0),p.number("bottom",0)));
                case "LIUBAI_TAG" -> ((LiubaiRasterKernel)kernel).createHorizontalLiubaiTagPng(p.text("text"),p.number("edgeSize",10));
                case "TRANSFORM" -> transform(kernel,p);
                case "SUBLIMATION_BORDER" -> sublimation(kernel,p);
                case "NESTING_MARKER" -> marker(kernel,p);
                default -> black(kernel,p);
            };
            RenderLimits.checkpoint();
            if(!ImageIO.write(image,"png",output))throw new IllegalStateException("PNG 编码器不可用");
            return new RenderResult("image/png","png",image.getWidth(),image.getHeight(),"px");
        }
    }
    private BigDecimal decimal(Params p,String key){return BigDecimal.valueOf(p.number(key,1));}
    private BufferedImage black(LegacySupport k,Params p){
        var image=k.newCanvas((int)Math.ceil(p.number("width",1)),(int)Math.ceil(p.number("height",1)),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();try{g.setColor(Color.BLACK);g.fillRect(0,0,image.getWidth(),image.getHeight());}finally{g.dispose();}return image;
    }
    private BufferedImage marker(LegacySupport k,Params p){
        var image=black(k,p);var g=image.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.BLACK);int left=p.integer("left",0);g.fillRect(left,2,Math.max(image.getWidth()-30-left,1),1);}finally{g.dispose();}return image;
    }
    private BufferedImage sublimation(LegacySupport k,Params p){
        double w=p.number("width",1),h=p.number("height",1),scale=Math.min(36D/25.4D,4096D/Math.max(w,h));
        var image=k.newCanvas(Math.max(1,(int)Math.ceil(w*scale)),Math.max(1,(int)Math.ceil(h*scale)),BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();try{g.scale(image.getWidth()/w,image.getHeight()/h);g.setColor(Color.BLACK);g.setStroke(new BasicStroke(2F));g.draw(new Rectangle2D.Double(1,1,w-2,h-2));}finally{g.dispose();}return image;
    }
    private BufferedImage transform(LegacySupport k,Params p)throws Exception {
        var source=jobs.find(p.text("sourceTaskId")).filter(j->j.status()==JobStatus.SUCCEEDED && j.artifact()!=null && j.artifact().mediaType().equals("image/png")).orElseThrow(()->new IllegalArgumentException("源 PNG 任务未完成或已过期"));
        var image=k.readPng(artifacts.resolve(source.taskId(),source.artifact().filename()));
        int crop=p.integer("cropWidth",0);if(crop>0)image=image.getSubimage(0,0,Math.min(crop,image.getWidth()),image.getHeight());
        double angle=p.number("angle",0),r=Math.toRadians(angle),sin=Math.abs(Math.sin(r)),cos=Math.abs(Math.cos(r));
        int w=image.getWidth(),h=image.getHeight();
        int nw=Math.max(1,(int)Math.ceil(w*cos+h*sin-1e-8)),nh=Math.max(1,(int)Math.ceil(h*cos+w*sin-1e-8));
        if(p.text("rounding").equals("FLOOR")){nw=Math.max(1,(int)Math.floor(w*cos+h*sin));nh=Math.max(1,(int)Math.floor(h*cos+w*sin));}
        var result=k.newCanvas(nw,nh,BufferedImage.TYPE_INT_ARGB);var g=result.createGraphics();
        try{g.setComposite(AlphaComposite.Src);g.translate((nw-w)/2D,(nh-h)/2D);g.rotate(r,w/2D,h/2D);g.drawImage(image,0,0,null);}finally{g.dispose();}return result;
    }
}
