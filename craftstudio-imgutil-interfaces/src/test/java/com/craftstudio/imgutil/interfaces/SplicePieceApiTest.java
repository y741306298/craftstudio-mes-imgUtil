package com.craftstudio.imgutil.interfaces;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"graphics.callback-dispatch-ms=50", "graphics.workers=1", "graphics.queue-capacity=16",
        "graphics.task-timeout-seconds=30", "graphics.retention-hours=24", "graphics.max-dpi=600", "graphics.max-request-bytes=1048576",
        "graphics.max-artifact-bytes=33554432", "graphics.max-svg-nodes=20000", "graphics.max-svg-depth=64",
        "graphics.max-storage-bytes=2147483648"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SplicePieceApiTest {
    private static final String KEY="test-piece-key";
    private static final Path DATA=directory();
    private static final Map<String,byte[]> OBJECTS=new ConcurrentHashMap<>();
    private static final CountDownLatch RECEIVED=new CountDownLatch(1);
    private static final AtomicInteger CALLBACKS=new AtomicInteger();
    private static final AtomicReference<byte[]> BODY=new AtomicReference<>();
    private static final AtomicReference<String> SIGNATURE=new AtomicReference<>();
    private static final HttpServer HTTP=server();
    private static Path directory(){try{return Files.createTempDirectory("piece-api-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static HttpServer server(){
        try{
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                if(exchange.getRequestMethod().equals("PUT")){
                    OBJECTS.put(exchange.getRequestURI().getPath(),exchange.getRequestBody().readAllBytes());
                    exchange.getResponseHeaders().set("ETag","\"test-etag\"");exchange.sendResponseHeaders(200,-1);
                }else{
                    BODY.set(exchange.getRequestBody().readAllBytes());SIGNATURE.set(exchange.getRequestHeaders().getFirst("X-Graphics-Signature"));
                    CALLBACKS.incrementAndGet();exchange.sendResponseHeaders(200,-1);RECEIVED.countDown();
                }
                exchange.close();
            });server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @AfterAll static void stop(){HTTP.stop(0);}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("graphics.data-directory",DATA::toString);
        registry.add("graphics.api-key",()->KEY);
        registry.add("graphics.oss.enabled",()->true);
        registry.add("graphics.oss.endpoint",()->"http://127.0.0.1:"+HTTP.getAddress().getPort());
        registry.add("graphics.oss.bucket",()->"test-bucket");
        registry.add("graphics.oss.access-key-id",()->"test-access-key");
        registry.add("graphics.oss.access-key-secret",()->"test-secret");
    }
    @Autowired MockMvc mvc;
    @Test void oneSubmissionUploadsAllAssetsAndMaskThenSendsOneSignedCallback()throws Exception{
        var mapper=new JsonMapper();
        String request=mapper.writeValueAsString(Map.of("requestId","whole-piece","mes",Map.of("operation","SPLICE_PIECE","parameters",Map.of(
                "groupText","618#1-2","artifactBaseUrl","https://bucket.example.com","pieceMongoId","piece",
                "originalContentImg","image.tif","originalSvg","<svg xmlns='http://www.w3.org/2000/svg' width='100' height='200'><path d='M0 0 H100 V200 H0 Z'/></svg>",
                "marks",List.of(Map.of("id","stripe-a","assetKey","stripe-horizontal","x",0,"y",180,"width",6,"height",1),
                        Map.of("id","yellow-b","assetKey","yellow-horizontal","x",44,"y",192,"width",56,"height",8)))),
                "callbackConfig",Map.of("callbackUrl","http://127.0.0.1:"+HTTP.getAddress().getPort()+"/callback",
                        "callbackCustomValue",Map.of("workflowId","workflow","taskId","whole-piece"))));
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key",KEY).contentType("application/json").content(request)).andExpect(status().isAccepted());
        assertTrue(RECEIVED.await(10,TimeUnit.SECONDS));
        var result=mapper.readTree(BODY.get());assertEquals("success",result.path("status").asString());
        var artifact = result.path("result").path("artifact");
        var assets = artifact.path("assets");
        assertEquals(8, assets.size());
        assertEquals(9,OBJECTS.size()); // Eight PNGs and one final SVG, no child tasks.
        try(var jobs=Files.list(DATA.resolve("jobs"))){assertEquals(1,jobs.count());}
        try(var files=Files.list(DATA.resolve("artifacts/whole-piece"))){assertEquals(9,files.count());}
        String filename = artifact.path("filename").asString();
        assertEquals(UUID.fromString(filename.substring(0, 36)) + ".svg", filename);
        String objectName = artifact.path("objectName").asString();
        assertTrue(objectName.endsWith("/whole-piece/" + filename));
        assertTrue(OBJECTS.containsKey("/test-bucket/" + objectName));
        Set<String> assetFilenames = new HashSet<>();
        for (var entry : assets.properties()) {
            String assetObjectName = entry.getValue().asString();
            String assetFilename = assetObjectName.substring(assetObjectName.lastIndexOf('/') + 1);
            assertEquals(UUID.fromString(assetFilename.substring(0, 36)) + ".png", assetFilename);
            assertTrue(assetFilenames.add(assetFilename));
            assertTrue(OBJECTS.containsKey("/test-bucket/" + assetObjectName));
        }
        String svg = Files.readString(DATA.resolve("artifacts/whole-piece").resolve(filename));
        assertTrue(svg.contains("https://bucket.example.com/" + assets.path("stripe-horizontal").asString()));
        assertTrue(svg.contains("https://bucket.example.com/" + assets.path("yellow-horizontal").asString()));
        mvc.perform(get("/api/graphics/tasks/whole-piece/artifact").header("X-Api-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(BODY.get())),SIGNATURE.get());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            String state=mvc.perform(get("/api/graphics/tasks/whole-piece/callback").header("X-Api-Key",KEY)).andReturn().getResponse().getContentAsString();
            if(state.contains("DELIVERED"))break;
            Thread.sleep(10);
        }
        mvc.perform(post("/api/graphics/tasks").header("X-Api-Key",KEY).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifact.assets.dark-horizontal").exists());
        assertEquals(9,OBJECTS.size());assertEquals(1,CALLBACKS.get());
        mvc.perform(get("/api/graphics/capabilities").header("X-Api-Key",KEY)).andExpect(jsonPath("$.data.splicePieceVersion").value(1))
                .andExpect(jsonPath("$.data.pixelLimitsEnabled").value(false))
                .andExpect(jsonPath("$.data.maxDimension").doesNotExist()).andExpect(jsonPath("$.data.maxPixels").doesNotExist());
    }
}
