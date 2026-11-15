package com.craftstudio.imgutil.domain.graphics;

import java.io.OutputStream;

public interface GraphicsRenderer {
    void validate(GraphicsRequest request);
    RenderResult render(GraphicsRequest request, OutputStream output) throws Exception;
}
