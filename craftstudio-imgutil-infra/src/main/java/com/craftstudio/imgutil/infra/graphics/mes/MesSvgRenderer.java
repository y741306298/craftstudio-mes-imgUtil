package com.craftstudio.imgutil.infra.graphics.mes;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.infra.graphics.DomSvgEditor;
import org.springframework.stereotype.Component;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@Component
public class MesSvgRenderer {
    private static final Set<String> OPS=Set.of("SVG_SAVE","SVG_MASK","LIUBAI_EXPAND","LIUBAI_GROUPS","BUCKLE_APPEND","SPLICE_APPEND","SPLICE_MARKS_APPEND","SUB_OPEN","SUB_ORIGINAL","SUB_BACK","SUB_POINT","SUB_BORDER","SUB_CLEAN","SVG_RESIZE","SVG_RECT_GROUP","NESTING_MARKER_SVG","SVG_REMOVE_SPLICE","SVG_REDO_ID","NESTED_MIRROR_CLEAN");
    private static final Pattern SPLICE_MARK_GROUP_PATTERN = Pattern.compile(
            "<g\\b(?=[^>]*\\bid\\s*=\\s*([\"'])(?:super-width-splice|adhesive-splice|photo-splice|board-cover-splice|inkjet-splice|seamless-splice|panel-splice)-).*?</g\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private final DomSvgEditor guard;
    public MesSvgRenderer(DomSvgEditor guard){this.guard=guard;}
    public boolean supports(String operation){return operation!=null && OPS.contains(operation);}
    @SuppressWarnings("unchecked")
    public RenderResult render(MesSpec spec,OutputStream output)throws Exception {
        Params p=new Params(spec.parameters());
        String source=p.text("source");
        if ("NESTED_MIRROR_CLEAN".equals(spec.operation())) {
            String cleaned = SPLICE_MARK_GROUP_PATTERN.matcher(source).replaceAll("");
            if (p.bool("sublimation", false)) {
                cleaned = SublimationMirrorSvgCleaner.clean(cleaned);
            }
            output.write(cleaned.getBytes(StandardCharsets.UTF_8));
            return new RenderResult("image/svg+xml", "svg", 0, 0, "mm", Map.of(), !cleaned.equals(source));
        }
        for(String field:List.of("source","originalSvg"))if(p.values.containsKey(field))guard.validateMarkup(p.text(field));
        var liubai=new LiubaiSvgKernel(p);
        Params margins=new Params((Map<String,Object>)p.values.getOrDefault("margins",Map.of()));
        var m=new LiubaiSvgKernel.ExpandMargins(margins.number("left",0),margins.number("right",0),margins.number("top",0),margins.number("bottom",0));
        String result=switch(spec.operation()) {
            case "SVG_SAVE" -> source;
            case "SVG_REDO_ID" -> {
                Pattern pattern=Pattern.compile("(?i)(\\bid\\s*=\\s*)([\"'])"+Pattern.quote(p.text("oldId"))+"\\2");
                Matcher matcher=pattern.matcher(source);
                yield matcher.find()?matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1)+matcher.group(2)+p.text("newId")+matcher.group(2))):source;
            }
            case "SVG_MASK" -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""+p.text("width")+"\" height=\""+p.text("height")+"\" viewBox=\"0 0 "+p.text("width")+" "+p.text("height")+"\"><path d=\"M0 0 H"+p.text("width")+" V"+p.text("height")+" H0 Z\" fill=\"#000000\" stroke=\"#000000\" stroke-width=\"1\"/></svg>";
            case "LIUBAI_EXPAND" -> liubai.buildExpandedSvg(p.text("originalSvg"),p.text("pieceMongoId"),p.text("markPngUrl"),LiubaiSvgKernel.tags(p),p.text("originalContentImg"),p.number("originalWidth",1),p.number("originalHeight",1),m);
            case "LIUBAI_GROUPS" -> liubai.buildLiubaiTagGroups(p.text("pieceMongoId"),LiubaiSvgKernel.tags(p),p.number("originalWidth",1),p.number("originalHeight",1),m);
            case "BUCKLE_APPEND" -> {
                var points=((List<Map<String,Object>>)p.values.getOrDefault("markPoints",List.of())).stream().map(v->{Params t=new Params(v);return new BuckleSvgKernel.BuckleMarkPoint(t.text("suffix"),t.number("centerX",0),t.number("centerY",0));}).toList();
                yield new BuckleSvgKernel(p).appendBuckleMarks(p.text("originalSvg"),p.text("pieceId"),p.text("originalContentImg"),points);
            }
            case "SPLICE_MARKS_APPEND" -> new SpliceSvgKernel().appendMarksSvg(p.text("originalSvg"),
                    p.text("pieceMongoId"), p.text("originalContentImg"), rectMarks(p));
            case "SPLICE_APPEND" -> new SpliceSvgKernel().appendMarksSvg(p.text("originalSvg"),p.text("pieceMongoId"),p.text("originalContentImg"),p.text("marksSvg"));
            case "SUB_OPEN" -> SublimationSvg.open(p.number("width",1),p.number("height",1));
            case "SUB_ORIGINAL" -> SublimationSvg.original(p.text("id"),p.text("imageUrl"),p.number("width",1),p.number("height",1));
            case "SUB_BACK" -> SublimationSvg.forBack(p.text("id"),p.text("backUrl"),p.number("width",1),p.number("height",1));
            case "SUB_BORDER" -> SublimationSvg.border(p.text("id"),p.text("imageUrl"),p.number("width",1),p.number("height",1));
            case "SUB_POINT" -> {
                Params t=new Params((Map<String,Object>)p.values.get("point"));
                yield SublimationSvg.point(p.text("id"),new SublimationGeometry.Point(t.text("key"),SublimationGeometry.MarkKind.valueOf(t.text("kind")),t.number("x",0),t.number("y",0)));
            }
            case "SUB_CLEAN" -> SublimationMirrorSvgCleaner.clean(source);
            case "SVG_RESIZE" -> resize(source,p);
            case "SVG_RECT_GROUP" -> rectGroup(p);
            case "NESTING_MARKER_SVG" -> marker(p);
            case "SVG_REMOVE_SPLICE" -> removeSplice(source);
            default -> throw new IllegalArgumentException("未知 SVG 操作");
        };
        boolean fragment=Set.of("LIUBAI_GROUPS","SUB_OPEN","SUB_ORIGINAL","SUB_POINT","SUB_BORDER","SVG_RECT_GROUP").contains(spec.operation());
        if(spec.operation().equals("SUB_OPEN"))guard.validateMarkup(result+"</svg>");
        else guard.validateMarkup(fragment?"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\">"+result+"</svg>":result);
        output.write(result.getBytes(StandardCharsets.UTF_8));
        return new RenderResult("image/svg+xml","svg",0,0,fragment?"fragment":"mm");
    }
    private String resize(String source,Params p){
        Matcher root=Pattern.compile("<svg\\b[^>]*>",Pattern.CASE_INSENSITIVE).matcher(source);if(!root.find())throw new IllegalArgumentException("缺少 SVG 根节点");
        String tag=root.group();
        for(String name:List.of("width","height")) {
            String attr=" "+name+"=\""+p.number(name,1)+"\"";
            Pattern pattern=Pattern.compile("\\s"+name+"\\s*=\\s*([\"']).*?\\1",Pattern.CASE_INSENSITIVE);Matcher matcher=pattern.matcher(tag);
            tag=matcher.find()?matcher.replaceFirst(Matcher.quoteReplacement(attr)):tag.substring(0,tag.length()-1)+attr+">";
        }
        return source.substring(0,root.start())+tag+source.substring(root.end());
    }
    /** Same per-mark renderer as SVG_RECT_GROUP, with one bounded task for the whole piece. */
    private String rectMarks(Params p) throws InterruptedException {
        if (!(p.values.get("marks") instanceof List<?> marks) || marks.isEmpty() || marks.size() > 32) {
            throw new IllegalArgumentException("拼接标记数量必须为 1 到 32");
        }
        StringBuilder result = new StringBuilder("\n");
        Set<String> ids = new HashSet<>();
        for (Object value : marks) {
            com.craftstudio.imgutil.infra.graphics.RenderLimits.checkpoint();
            if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("拼接标记参数无效");
            Map<String, Object> values = new LinkedHashMap<>();
            raw.forEach((key, item) -> values.put(key.toString(), item));
            Params mark = new Params(values);
            if (mark.text("id").isBlank() || !ids.add(mark.text("id")) || mark.text("img").isBlank()) {
                throw new IllegalArgumentException("拼接标记 id 或图片无效，id 不可重复");
            }
            for (String key : List.of("x", "y", "width", "height")) {
                if (!values.containsKey(key)) throw new IllegalArgumentException("拼接标记缺少参数: " + key);
                double number = mark.number(key, 0);
                if ((key.equals("width") || key.equals("height")) && number <= 0) {
                    throw new IllegalArgumentException("拼接标记尺寸必须大于零");
                }
            }
            result.append(rectGroup(mark));
        }
        return result.toString();
    }

    private String rectGroup(Params p){
        String img=p.text("img"), path=img.split("\\?",2)[0];String name=path.substring(path.lastIndexOf('/')+1);
        double x=p.number("x",0),y=p.number("y",0),w=p.number("width",1),h=p.number("height",1);
        return "<g id=\""+escape(p.text("id"))+"\" img=\""+escape(img)+"\" data-source-name=\""+escape(name)+"\" data-forme=\"false\" data-rotation=\"0\" require-plt=\"false\">\n"
            +"<path d=\"M"+num(x)+" "+num(y)+" H"+num(x+w)+" V"+num(y+h)+" H"+num(x)+" Z\" fill=\"#111111\" fill-opacity=\"0.82\" fill-rule=\"evenodd\" />\n</g>\n";
    }
    private String num(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}

    private String marker(Params p){int w=p.integer("width",1),left=p.integer("left",0),line=Math.max(w-30-left,1);return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""+w+"mm\" height=\"6mm\" viewBox=\"0 0 "+w+" 6\"><rect x=\"0\" y=\"0\" width=\""+w+"\" height=\"6\" fill=\"#ffffff\"/><rect x=\""+left+"\" y=\"2.5\" width=\""+line+"\" height=\"1\" fill=\"#000000\"/></svg>";}
    private String escape(String s){return s.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private String removeSplice(String source)throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(source)));
        var groups=document.getElementsByTagNameNS("*","g");
        for(int i=groups.getLength()-1;i>=0;i--){var e=(org.w3c.dom.Element)groups.item(i);String id=e.getAttribute("id");if(id.matches("^(super-width|adhesive|photo|board-cover|inkjet|seamless|panel)-splice-.*"))e.getParentNode().removeChild(e);}
        var tf=javax.xml.transform.TransformerFactory.newInstance();tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var writer=new java.io.StringWriter();tf.newTransformer().transform(new javax.xml.transform.dom.DOMSource(document),new javax.xml.transform.stream.StreamResult(writer));return writer.toString();
    }
}
