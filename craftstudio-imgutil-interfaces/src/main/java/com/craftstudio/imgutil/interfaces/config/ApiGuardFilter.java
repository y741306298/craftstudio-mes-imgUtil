package com.craftstudio.imgutil.interfaces.config;

import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.interfaces.api.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiGuardFilter extends OncePerRequestFilter {
    private final GraphicsProperties properties;
    private final JsonMapper mapper;
    public ApiGuardFilter(GraphicsProperties properties, JsonMapper mapper) { this.properties = properties; this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        // Only the health endpoint is public; path normalization must not bypass authentication.
        if (path.equals("/actuator/health")) { chain.doFilter(request, response); return; }
        String expected = properties.apiKey(), supplied = request.getHeader("X-Api-Key");
        if (expected != null && !expected.isBlank() && (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))) {
            reject(response, 401, "UNAUTHORIZED", "API Key 无效"); return;
        }
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        int maximum = properties.maxRequestBytes();
        if (request.getContentLengthLong() > maximum) { reject(response, 413, "REQUEST_TOO_LARGE", "请求体超过大小限制"); return; }
        byte[] body = request.getInputStream().readNBytes(maximum + 1);
        if (body.length > maximum) { reject(response, 413, "REQUEST_TOO_LARGE", "请求体超过大小限制"); return; }
        HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int offset, int length) { return input.read(b, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("仅支持同步请求体读取"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
        chain.doFilter(wrapper, response);
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
