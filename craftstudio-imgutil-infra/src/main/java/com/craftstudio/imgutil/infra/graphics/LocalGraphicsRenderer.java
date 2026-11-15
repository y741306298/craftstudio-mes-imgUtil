package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.*;
import org.springframework.stereotype.Component;
import java.io.OutputStream;

@Component
public class LocalGraphicsRenderer implements GraphicsRenderer {
    private final AwtRasterRenderer raster;
    private final DomSvgEditor svg;
    public LocalGraphicsRenderer(AwtRasterRenderer raster, DomSvgEditor svg) { this.raster = raster; this.svg = svg; }
    @Override public void validate(GraphicsRequest request) {
        request.validate();
        if (request.raster() != null) raster.validate(request.raster()); else svg.validate(request.svg());
    }
    @Override public RenderResult render(GraphicsRequest request, OutputStream output) throws Exception {
        return request.raster() != null ? raster.render(request.raster(), output) : svg.render(request.svg(), output);
    }
}
