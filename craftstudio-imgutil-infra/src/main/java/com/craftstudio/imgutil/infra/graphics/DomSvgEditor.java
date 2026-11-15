package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Edits SVG DOM without rasterizing it or resolving any remote resource. */
@Component
public class DomSvgEditor {
    private static final String NS = "http://www.w3.org/2000/svg";
    private static final Set<String> ELEMENTS = Set.of("svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "defs", "clipPath", "title", "desc", "text", "tspan", "image");
    private static final Set<String> ATTRIBUTES = Set.of("id", "width", "height", "viewBox", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "fill", "fill-rule", "fill-opacity", "stroke", "stroke-width", "stroke-dasharray", "stroke-linecap", "stroke-linejoin", "stroke-opacity", "opacity", "clip-path", "clipPathUnits", "preserveAspectRatio", "version", "img", "require-plt", "href", "font-family", "font-size", "font-weight", "text-anchor", "dominant-baseline");
    private final GraphicsProperties properties;
    public DomSvgEditor(GraphicsProperties properties) { this.properties = properties; }
    public void validateMarkup(String source) {
        if(source.getBytes(StandardCharsets.UTF_8).length>properties.maxRequestBytes())throw new IllegalArgumentException("SVG 文本过大");
        checkDocument(parse(source));
    }

    public void validate(SvgSpec s) {
        if (s.kind() == null) throw new IllegalArgumentException("缺少 svg.kind");
        s.margins().validate();
        if (s.marks().size() > 1000 || s.removeGroupIds().size() > 1000 || s.imageReplacements().size() > 1000) throw new IllegalArgumentException("SVG 编辑操作过多");
        if (s.kind() == SvgSpec.Kind.MASK) { dimension(s.width()); dimension(s.height()); }
        else if (s.source() == null || s.source().isBlank()) throw new IllegalArgumentException("SVG 编辑缺少 source");
        if (s.source() != null && s.source().getBytes(StandardCharsets.UTF_8).length > properties.maxRequestBytes()) throw new IllegalArgumentException("SVG 输入过大");
        if (s.sourceImage() != null) reference(s.sourceImage());
        for (SvgMark mark : s.marks()) {
            if (mark.kind() == null || mark.id() == null || !mark.id().matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw new IllegalArgumentException("标记类型或 id 无效");
            dimension(mark.width()); dimension(mark.height());
            coordinate(mark.x()); coordinate(mark.y()); coordinate(mark.rotationDegrees());
            if (mark.image() != null) reference(mark.image());
        }
        s.imageReplacements().values().forEach(DomSvgEditor::reference);
        if (s.kind() == SvgSpec.Kind.EDIT) {
            Document document = parse(s.source());
            checkDocument(document);
            // Validate physical dimensions and viewBox before accepting the job.
            rootSize(document.getDocumentElement());
        }
    }

    public RenderResult render(SvgSpec s, OutputStream output) throws Exception {
        validate(s); RenderLimits.checkpoint();
        Document document = s.kind() == SvgSpec.Kind.MASK ? empty() : parse(s.source());
        Element root = document.getDocumentElement();
        if (s.kind() == SvgSpec.Kind.MASK) {
            setSize(root, s.width(), s.height());
            Element original = element(document, "g"); original.setAttribute("id", "original-content");
            original.setAttribute("require-plt", Boolean.toString(s.requirePlt()));
            if (s.sourceImage() != null) original.setAttribute("img", s.sourceImage());
            Element path = element(document, "path");
            path.setAttribute("d", rectPath(0, 0, s.width(), s.height())); path.setAttribute("fill", "#000000");
            original.appendChild(path); root.appendChild(original);
        }
        double[] size = rootSize(root);
        List<Element> elements = new ArrayList<>(); collect(root, elements);
        for (Element e : elements) {
            RenderLimits.checkpoint();
            if ("g".equals(e.getLocalName()) && s.removeGroupIds().contains(e.getAttribute("id")) && e.getParentNode() != null) e.getParentNode().removeChild(e);
            String replacement = s.imageReplacements().get(e.getAttribute("id"));
            if (replacement != null) e.setAttribute("img", replacement);
            if (s.normalizeRects() && "rect".equals(e.getLocalName()) && !e.hasAttribute("rx") && !e.hasAttribute("ry")) {
                Element path = element(document, "path");
                NamedNodeMap attrs = e.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node attr = attrs.item(i);
                    if (!Set.of("x", "y", "width", "height").contains(attr.getNodeName())) path.setAttribute(attr.getNodeName(), attr.getNodeValue());
                }
                path.setAttribute("d", rectPath(number(e, "x", 0), number(e, "y", 0), number(e, "width", 0), number(e, "height", 0)));
                if (e.getParentNode() != null) e.getParentNode().replaceChild(path, e);
            }
        }
        Insets m = s.margins();
        double width = size[0] + m.left() + m.right(), height = size[1] + m.top() + m.bottom();
        dimension(width); dimension(height);
        if (!Insets.ZERO.equals(m)) {
            Element group = element(document, "g"); group.setAttribute("transform", "translate(" + n(m.left()) + " " + n(m.top()) + ")");
            while (root.hasChildNodes()) group.appendChild(root.getFirstChild());
            root.appendChild(group); setSize(root, width, height);
        }
        Set<String> ids = new HashSet<>();
        List<Element> existing = new ArrayList<>(); collect(root, existing);
        for (Element e : existing) if (e.hasAttribute("id")) ids.add(e.getAttribute("id"));
        for (SvgMark mark : s.marks()) {
            if (!ids.add(mark.id())) throw new IllegalArgumentException("重复的 SVG 标记 id: " + mark.id());
            root.appendChild(mark(document, mark));
        }
        checkDocument(document); RenderLimits.checkpoint();
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer(); transformer.setOutputProperty("encoding", "UTF-8");
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return new RenderResult("image/svg+xml", "svg", width, height, "mm");
    }

    private Element mark(Document d, SvgMark m) {
        Element group = element(d, "g"); group.setAttribute("id", m.id());
        group.setAttribute("require-plt", Boolean.toString(m.requirePlt())); group.setAttribute("data-forme", "false");
        group.setAttribute("data-rotation", n(m.rotationDegrees()));
        if (m.image() != null) group.setAttribute("img", m.image());
        group.setAttribute("transform", "translate(" + n(m.x()) + " " + n(m.y()) + ") rotate(" + n(m.rotationDegrees()) + " " + n(m.width() / 2) + " " + n(m.height() / 2) + ")");
        Element path = element(d, "path");
        String shape = switch (m.kind()) {
            case RECT -> rectPath(0, 0, m.width(), m.height());
            case CIRCLE -> "M0 " + n(m.height() / 2) + " A" + n(m.width() / 2) + " " + n(m.height() / 2) + " 0 1 0 " + n(m.width()) + " " + n(m.height() / 2)
                    + " A" + n(m.width() / 2) + " " + n(m.height() / 2) + " 0 1 0 0 " + n(m.height() / 2) + " Z";
            case CROSS -> "M0 " + n(m.height() / 2) + " H" + n(m.width()) + " M" + n(m.width() / 2) + " 0 V" + n(m.height());
        };
        path.setAttribute("d", shape); path.setAttribute("fill", m.kind() == SvgMark.Kind.CROSS ? "none" : "#000000");
        if (m.kind() == SvgMark.Kind.CROSS) { path.setAttribute("stroke", "#000000"); path.setAttribute("stroke-width", "1"); }
        group.appendChild(path); return group;
    }
    private Document empty() throws Exception {
        Document d = builder().newDocument(); d.appendChild(element(d, "svg")); return d;
    }
    private Document parse(String source) {
        try {
            var parser = builder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            return parser.parse(new InputSource(new StringReader(source)));
        } catch (Exception e) { throw new IllegalArgumentException("SVG XML 无效或包含禁止的实体声明", e); }
    }
    private javax.xml.parsers.DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false); f.setExpandEntityReferences(false); return f.newDocumentBuilder();
    }
    private void checkDocument(Document d) {
        Element root = d.getDocumentElement();
        if (!"svg".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI())) throw new IllegalArgumentException("必须使用标准 SVG 命名空间");
        inspect(root, 1, new int[]{0});
        for (Node node = d.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) throw new IllegalArgumentException("禁止 XML 处理指令");
        }
    }
    private void inspect(Node node, int depth, int[] count) {
        if (++count[0] > properties.maxSvgNodes() || depth > properties.maxSvgDepth()) throw new IllegalArgumentException("SVG 节点数或深度超过上限");
        if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) throw new IllegalArgumentException("禁止处理指令");
        if (node instanceof Element e) {
            if (!NS.equals(e.getNamespaceURI()) || !ELEMENTS.contains(e.getLocalName())) throw new IllegalArgumentException("不支持的 SVG 元素: " + e.getNodeName());
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i); String key = a.getLocalName() == null ? a.getNodeName() : a.getLocalName(), value = a.getNodeValue();
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) continue;
                if (a.getNamespaceURI() != null && !("http://www.w3.org/1999/xlink".equals(a.getNamespaceURI()) && "href".equals(key))) throw new IllegalArgumentException("不支持的属性命名空间");
                if (!ATTRIBUTES.contains(key) && !key.startsWith("data-")) throw new IllegalArgumentException("不支持的 SVG 属性: " + key);
                if (value.length() > 200000) throw new IllegalArgumentException("SVG 属性过长");
                if (Set.of("href", "img").contains(key)) reference(value);
                if (value.toLowerCase(Locale.ROOT).contains("url(") && !value.matches("url\\(#[A-Za-z_][A-Za-z0-9_.-]*\\)")) throw new IllegalArgumentException("禁止 SVG 外部资源表达式");
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) inspect(child, depth + 1, count);
    }
    private void collect(Element root, List<Element> target) {
        target.add(root);
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) collect(e, target);
    }
    private double[] rootSize(Element root) {
        double width = number(root, "width", -1), height = number(root, "height", -1);
        dimension(width); dimension(height);
        if (root.hasAttribute("viewBox")) {
            String[] values = root.getAttribute("viewBox").trim().split("[\\s,]+");
            if (values.length != 4) throw new IllegalArgumentException("SVG viewBox 必须包含四个数值");
            double[] viewBox = Arrays.stream(values).mapToDouble(Double::parseDouble).toArray();
            for (double value : viewBox) coordinate(value);
            if (viewBox[0] != 0 || viewBox[1] != 0 || Math.abs(viewBox[2] - width) > 1e-6 || Math.abs(viewBox[3] - height) > 1e-6)
                throw new IllegalArgumentException("SVG viewBox 必须为 0 0 width height，先在 MES 归一化坐标");
        }
        return new double[]{width, height};
    }
    private double number(Element e, String key, double fallback) {
        if (!e.hasAttribute(key)) return fallback;
        String value = e.getAttribute(key).trim();
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?(?:mm|px)?")) throw new IllegalArgumentException("不支持的 SVG 尺寸: " + key);
        double result = Double.parseDouble(value.replaceAll("(?:mm|px)$", "")); coordinate(result); return result;
    }
    private static void reference(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 4096 || value.startsWith("//") || (uri.getScheme() != null && !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT)))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("图片引用必须是对象名或 HTTP(S) URL；不允许 data/file/javascript", e); }
    }
    private void dimension(double v) { RenderLimits.positive(v, "SVG 尺寸"); if (v > 1000000) throw new IllegalArgumentException("SVG 尺寸过大"); }
    private void coordinate(double v) { RenderLimits.finite(v, "SVG 坐标"); if (Math.abs(v) > 10000000) throw new IllegalArgumentException("SVG 坐标过大"); }
    private Element element(Document d, String name) { return d.createElementNS(NS, name); }
    private void setSize(Element root, double width, double height) { root.setAttribute("width", n(width)); root.setAttribute("height", n(height)); root.setAttribute("viewBox", "0 0 " + n(width) + " " + n(height)); }
    private String rectPath(double x, double y, double width, double height) { return "M" + n(x) + " " + n(y) + " H" + n(x + width) + " V" + n(y + height) + " H" + n(x) + " Z"; }
    private String n(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
