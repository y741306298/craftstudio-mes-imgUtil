package com.craftstudio.imgutil.application.command;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.persistence.*;
import com.craftstudio.imgutil.infra.storage.FileArtifactStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphicsCallbackDispatcherTest {
    @TempDir Path root;
    private final JsonMapper mapper=new JsonMapper();
    private GraphicsProperties properties;
    private FileJobRepository jobs;
    private FileCallbackRepository callbacks;
    private GraphicsCallbackDispatcher dispatcher;
    private HttpServer server;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicInteger status=new AtomicInteger(200);
    private final AtomicReference<String> payload=new AtomicReference<>();
    private final AtomicReference<String> signature=new AtomicReference<>();
    @BeforeEach void setup()throws Exception{
        properties=new GraphicsProperties("secret",root.toString(),1,1,30,24,8192,8000000,600,1048576,33554432,20000,64,2147483648L,null);
        jobs=new FileJobRepository(properties,mapper);callbacks=new FileCallbackRepository(properties,mapper);dispatcher=new GraphicsCallbackDispatcher(callbacks,jobs,properties,mapper);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/callback",exchange->{payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));signature.set(exchange.getRequestHeaders().getFirst("X-Graphics-Signature"));calls.incrementAndGet();exchange.sendResponseHeaders(status.get(),-1);exchange.close();});server.start();
    }
    @AfterEach void close(){dispatcher.close();server.stop(0);}
    private void register(){callbacks.register("task",new CallbackConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/callback",Map.of("workflowId","workflow","taskId","task")));}
    private GraphicsJob success(){return new GraphicsJob("task","hash",JobStatus.SUCCEEDED,1,2,new Artifact("result.png","image/png",3,"hash",8,3,"px","/api/graphics/tasks/task/artifact","imgutil/task/result.png"),null,null);}
    @Test void sendsSignedTerminalCallbackWithCustomValueAndDoesNotSendTwice()throws Exception{
        register();jobs.save(success());dispatcher.deliverDue();assertEquals(1,calls.get());
        assertEquals("DELIVERED",callbacks.find("task").orElseThrow().status());
        var body=mapper.readTree(payload.get());assertEquals("success",body.get("status").asString());assertEquals("workflow",body.get("callbackCustomValue").get("workflowId").asString());
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(payload.get().getBytes(StandardCharsets.UTF_8))),signature.get());
        dispatcher.deliverDue();assertEquals(1,calls.get());assertEquals("DELIVERED",new FileCallbackRepository(properties,mapper).find("task").orElseThrow().status());
    }
    @Test void retriesFailedDeliveryWithIdenticalBodyAfterBackoff(){
        register();jobs.save(success());status.set(503);dispatcher.deliverDue();var delivery=callbacks.find("task").orElseThrow();
        assertEquals("PENDING",delivery.status());assertEquals(1,delivery.attempts());assertTrue(delivery.nextAttemptAt()>System.currentTimeMillis());String first=payload.get();
        dispatcher.deliverDue();assertEquals(1,calls.get());
        callbacks.save(new CallbackDelivery(delivery.taskId(),delivery.config(),delivery.attempts(),0,"PENDING",delivery.lastError()));status.set(200);dispatcher.deliverDue();
        assertEquals(first,payload.get());assertEquals(2,callbacks.find("task").orElseThrow().attempts());assertEquals("DELIVERED",callbacks.find("task").orElseThrow().status());
        dispatcher.deliverDue();
        assertEquals(2, calls.get());
    }

    @Test void schedulesFiveRetriesAndStopsAfterSixFailedDeliveries() {
        register();
        jobs.save(success());
        status.set(503);
        long[] delaysMs = {5_000, 10_000, 30_000, 60_000, 300_000};

        for (int i = 0; i < delaysMs.length; i++) {
            long before = System.currentTimeMillis();
            dispatcher.deliverDue();
            long after = System.currentTimeMillis();
            var delivery = callbacks.find("task").orElseThrow();
            assertEquals(i + 1, delivery.attempts());
            assertEquals(i + 1, calls.get());
            assertEquals("PENDING", delivery.status());
            assertTrue(delivery.nextAttemptAt() >= before + delaysMs[i]);
            assertTrue(delivery.nextAttemptAt() <= after + delaysMs[i]);
            callbacks.save(new CallbackDelivery(delivery.taskId(), delivery.config(), delivery.attempts(),
                    0, "PENDING", delivery.lastError()));
        }

        dispatcher.deliverDue();
        var delivery = callbacks.find("task").orElseThrow();
        assertEquals(6, delivery.attempts());
        assertEquals("FAILED", delivery.status());
        assertEquals(0, delivery.nextAttemptAt());
        assertEquals("HTTP 503", delivery.lastError());
        dispatcher.deliverDue();
        assertEquals(6, calls.get());
    }

    @Test void stopsPendingDeliveriesThatAlreadyExhaustedTheNewLimit() {
        register();
        jobs.save(success());
        var delivery = callbacks.find("task").orElseThrow();
        callbacks.save(new CallbackDelivery(delivery.taskId(), delivery.config(), 8,
                Long.MAX_VALUE, "PENDING", "HTTP 503"));

        dispatcher.deliverDue();

        var failed = callbacks.find("task").orElseThrow();
        assertEquals(0, calls.get());
        assertEquals(8, failed.attempts());
        assertEquals("FAILED", failed.status());
        assertEquals(0, failed.nextAttemptAt());
        assertEquals("HTTP 503", failed.lastError());
    }
    @Test void sendsFailureAndIgnoresNonTerminalJobs(){
        register();jobs.save(success().transition(JobStatus.RUNNING,null,null,null));dispatcher.deliverDue();assertEquals(0,calls.get());
        jobs.save(success().transition(JobStatus.TIMED_OUT,null,"TASK_TIMEOUT","too slow"));dispatcher.deliverDue();
        var result=mapper.readTree(payload.get());assertEquals("error",result.get("status").asString());assertEquals("TASK_TIMEOUT",result.get("errorCode").asString());
    }
    @Test void existingNonCallbackTaskFingerprintRemainsCompatible()throws Exception{
        record OldRequest(String requestId,RasterSpec raster,SvgSpec svg,MesSpec mes){}
        var canonical=JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        MesSpec operation=new MesSpec("BLACK",Map.of("width",8,"height",3));
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(new OldRequest("task",null,null,operation))));
        var job=new GraphicsJob("task",fingerprint,JobStatus.SUCCEEDED,1,2,null,null,null);jobs.save(job);
        try(var store=new AutoClosingStore(new FileArtifactStore(properties))){
            GraphicsRenderer renderer=new GraphicsRenderer(){public void validate(GraphicsRequest r){fail("No new rendering expected");}public RenderResult render(GraphicsRequest r,java.io.OutputStream out){throw new AssertionError();}};
            var service=new AppGraphicsTaskService(jobs,store.store,renderer,properties,mapper,callbacks);
            try{assertEquals(job,service.submit(new GraphicsRequest("task",null,null,operation)));}finally{service.close();}
        }
    }
    private record AutoClosingStore(FileArtifactStore store)implements AutoCloseable{public void close(){store.close();}}
}
