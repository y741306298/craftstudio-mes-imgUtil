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
//    private static final Path DATA = createDirectory();
//    private static Path createDirectory() { try { return Files.createTempDirectory("imgutil-api-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
//    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
//        registry.add("graphics.data-directory", DATA::toString);
//        registry.add("graphics.api-key", () -> "test-api-key");
//        registry.add("graphics.max-request-bytes", () -> 4096);
//    }
//    @Autowired MockMvc mvc;
//    @Test void requiresAuthenticationAndCapsRequestBody() throws Exception {
//        mvc.perform(get("/api/graphics/capabilities")).andExpect(status().isUnauthorized());
//        mvc.perform(get("/api/graphics/capabilities").header("X-Api-Key", "test-api-key"))
//                .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxPixels").value(8000000));
//        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content("x".repeat(5000)))
//                .andExpect(status().isPayloadTooLarge());
//        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
//    }
//    @Test void completesPngJobAndStreamsArtifact() throws Exception {
//        String request = """
//                {"requestId":"http-label","raster":{"kind":"LABEL","widthMm":50,"heightMm":10,"dpi":150,"lines":["MES-001"]}}
//                """;
//        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content(request))
//                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/graphics/tasks/http-label"));
//        long deadline = System.currentTimeMillis() + 5000;
//        while (!Files.exists(DATA.resolve("artifacts/http-label/result.png")) && System.currentTimeMillis() < deadline) Thread.sleep(20);
//        // Publishing the manifest follows publishing the artifact.
//        while (System.currentTimeMillis() < deadline) {
//            String result = mvc.perform(get("/api/graphics/tasks/http-label").header("X-Api-Key", "test-api-key")).andReturn().getResponse().getContentAsString();
//            if (result.contains("SUCCEEDED")) break;
//            Thread.sleep(20);
//        }
//        mvc.perform(get("/api/graphics/tasks/http-label").header("X-Api-Key", "test-api-key"))
//                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
//        mvc.perform(get("/api/graphics/tasks/http-label/artifact").header("X-Api-Key", "test-api-key"))
//                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
//                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
//        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json").content(request))
//                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
//    }
//    @Test void rejectsInvalidRequestsAndUnknownJobs() throws Exception {
//        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key", "test-api-key").contentType("application/json")
//                        .content("{\"requestId\":\"too-big\",\"raster\":{\"kind\":\"SOLID\",\"widthMm\":20000,\"heightMm\":20000,\"dpi\":600}}"))
//                .andExpect(status().isBadRequest());
//        mvc.perform(get("/api/graphics/tasks/unknown").header("X-Api-Key", "test-api-key")).andExpect(status().isNotFound());
//    }

}
