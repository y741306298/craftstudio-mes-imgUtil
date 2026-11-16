package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.*;
import org.springframework.stereotype.Component;
import java.io.OutputStream;

@Component
public class LocalGraphicsRenderer implements GraphicsRenderer {
    private final AwtRasterRenderer raster;
    private final DomSvgEditor svg;
    private final com.craftstudio.imgutil.infra.graphics.mes.MesCompatibilityRenderer mes;
    public LocalGraphicsRenderer(AwtRasterRenderer raster, DomSvgEditor svg, com.craftstudio.imgutil.infra.graphics.mes.MesCompatibilityRenderer mes) { this.raster = raster; this.svg = svg; this.mes = mes; }
    @Override public void validate(GraphicsRequest request) {
        request.validate();
        if(request.mes()!=null)mes.validate(request.mes());
        else if (request.raster() != null) raster.validate(request.raster()); else svg.validate(request.svg());
    }
    @Override public RenderResult render(GraphicsRequest request, OutputStream output) throws Exception {
        return request.mes()!=null ? mes.render(request.requestId(),request.mes(),output) : request.raster() != null ? raster.render(request.raster(), output) : svg.render(request.svg(), output);
    }
}
