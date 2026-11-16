package com.craftstudio.imgutil.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="graphics.callback-dispatch-ms=100")
@AutoConfigureMockMvc
class GraphicsApiTest {
    private static final Path DATA = createDirectory();
    private static Path createDirectory() { try { return Files.createTempDirectory("imgutil-api-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("graphics.data-directory", DATA::toString);
        registry.add("graphics.api-key", () -> "test-api-key");
        registry.add("graphics.max-request-bytes", () -> 4096);
    }
    @Autowired MockMvc mvc;
    @Test void completesWholeSpliceBatchAndActivelyDeliversSignedResult() throws Exception {
        var received=new java.util.concurrent.CountDownLatch(1);
        var body=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var signature=new java.util.concurrent.atomic.AtomicReference<String>();
        var callback=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        callback.createContext("/complete",exchange->{body.set(exchange.getRequestBody().readAllBytes());signature.set(exchange.getRequestHeaders().getFirst("X-Graphics-Signature"));exchange.sendResponseHeaders(200,-1);exchange.close();received.countDown();});callback.start();
        try{
            String request="""
                {"requestId":"callback-http","mes":{"operation":"SPLICE_MARKS_APPEND","parameters":{
                   "originalSvg":"<svg xmlns='http://www.w3.org/2000/svg' width='100' height='200'><rect width='100' height='200'/></svg>",
                   "pieceMongoId":"piece", "originalContentImg":"image.tif",
                   "marks":[
                     {"id":"stripe-a","img":"stripe.png","x":0,"y":180,"width":6,"height":1},
                     {"id":"stripe-b","img":"stripe.png","x":94,"y":180,"width":6,"height":1},
                     {"id":"gray-a","img":"gray.png","x":0,"y":184,"width":40,"height":8},
                     {"id":"gray-b","img":"gray.png","x":60,"y":184,"width":40,"height":8},
                     {"id":"yellow-a","img":"yellow.png","x":0,"y":192,"width":40,"height":8},
                     {"id":"yellow-b","img":"yellow.png","x":60,"y":192,"width":40,"height":8}
                   ]}},
                 "callbackConfig":{"callbackUrl":"http://127.0.0.1:%d/complete","callbackCustomValue":{"workflowId":"workflow","taskId":"callback-http"}}}
                """.formatted(callback.getAddress().getPort());
            mvc.perform(post("/api/graphics/tasks").header("X-Api-Key","test-api-key").header("X-Fc-Invocation-Type","Async").contentType("application/json").content(request))
                    .andExpect(status().isAccepted());
            org.junit.jupiter.api.Assertions.assertTrue(received.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-api-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            org.junit.jupiter.api.Assertions.assertEquals(java.util.HexFormat.of().formatHex(mac.doFinal(body.get())),signature.get());
            String result=new String(body.get(),java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(result.contains("\"status\":\"success\""));
            org.junit.jupiter.api.Assertions.assertTrue(result.contains("\"workflowId\":\"workflow\""));
            String svg = mvc.perform(get("/api/graphics/tasks/callback-http/artifact").header("X-Api-Key", "test-api-key"))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/svg+xml"))
                    .andReturn().getResponse().getContentAsString();
            for (String id : java.util.List.of("stripe-a", "stripe-b", "gray-a", "gray-b", "yellow-a", "yellow-b")) {
                org.junit.jupiter.api.Assertions.assertTrue(svg.contains("id=\"" + id + "\""));
            }
            org.junit.jupiter.api.Assertions.assertTrue(svg.contains("require-plt=\"true\""));
        }finally{callback.stop(0);}
    }
    @Test void requiresAuthenticationAndCapsRequestBody() throws Exception {
        mvc.perform(get("/api/graphics/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/graphics/capabilities").header("X-Api-Key", "test-api-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxPixels").value(8000000))
                .andExpect(jsonPath("$.data.spliceMarksBatchVersion").value(1));
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content("x".repeat(5000)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void completesPngJobAndStreamsArtifact() throws Exception {
        String request = """
                {"requestId":"http-label","raster":{"kind":"LABEL","widthMm":50,"heightMm":10,"dpi":150,"lines":["MES-001"]}}
                """;
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content(request))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/graphics/tasks/http-label"));
        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(DATA.resolve("artifacts/http-label/result.png")) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        // Publishing the manifest follows publishing the artifact.
        while (System.currentTimeMillis() < deadline) {
            String result = mvc.perform(get("/api/graphics/tasks/http-label").header("X-Api-Key", "test-api-key")).andReturn().getResponse().getContentAsString();
            if (result.contains("SUCCEEDED")) break;
            Thread.sleep(20);
        }
        mvc.perform(get("/api/graphics/tasks/http-label").header("X-Api-Key", "test-api-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        mvc.perform(get("/api/graphics/tasks/http-label/artifact").header("X-Api-Key", "test-api-key"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }
    @Test void rejectsInvalidRequestsAndUnknownJobs() throws Exception {
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json")
                        .content("{\"requestId\":\"too-big\",\"raster\":{\"kind\":\"SOLID\",\"widthMm\":20000,\"heightMm\":20000,\"dpi\":600}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/graphics/tasks/unknown").header("X-Api-Key", "test-api-key")).andExpect(status().isNotFound());
    }
}
