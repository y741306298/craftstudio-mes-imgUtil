package com.craftstudio.imgutil.application.command;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import com.craftstudio.imgutil.infra.persistence.FileJobRepository;
import com.craftstudio.imgutil.infra.storage.FileArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AppGraphicsTaskServiceTest {
    @TempDir Path directory;
    private GraphicsProperties properties(int timeout) { return new GraphicsProperties("", directory.toString(), 1, 1, timeout, 1, 8192, 8000000, 600, 1048576, 1024, 20000, 64, 10485760, null); }
    private GraphicsRequest request(String id, double width) { return new GraphicsRequest(id, new RasterSpec(RasterSpec.Kind.SOLID, width, 1, 150, null, null, 0, 0, null, false, null, 0, false, 0, null, 0), null); }
    private GraphicsRenderer renderer(CountDownLatch started, CountDownLatch release, boolean ignoreInterrupt) {
        return new GraphicsRenderer() {
            public void validate(GraphicsRequest request) { request.validate(); }
            public RenderResult render(GraphicsRequest request, OutputStream out) throws Exception {
                started.countDown();
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException e) { if (!ignoreInterrupt) throw e; }
                }
                out.write(new byte[]{1, 2, 3}); return new RenderResult("image/png", "png", 1, 1, "px");
            }
        };
    }
    private GraphicsJob await(AppGraphicsTaskService service, String id, JobStatus status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var job = service.find(id); if (job.status() == status) return job;
            Thread.sleep(20);
        }
        fail("任务未进入 " + status + ": " + service.find(id)); return null;
    }
    @Test void boundsQueueHandlesIdempotencyAndPersistsArtifacts() throws Exception {
        var props = properties(10); var mapper = new JsonMapper(); var repo = new FileJobRepository(props, mapper); var store = new FileArtifactStore(props);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = new AppGraphicsTaskService(repo, store, renderer(started, release, false), props, mapper);
        try {
            service.submit(request("first", 1)); assertTrue(started.await(2, TimeUnit.SECONDS));
            service.submit(request("second", 1));
            assertEquals("first", service.submit(request("first", 1)).taskId());
            assertEquals(409, assertThrows(TaskException.class, () -> service.submit(request("first", 2))).status());
            assertEquals(429, assertThrows(TaskException.class, () -> service.submit(request("third", 1))).status());
            assertTrue(repo.find("third").isEmpty());
            release.countDown();
            var job = await(service, "first", JobStatus.SUCCEEDED);
            var secondJob = await(service, "second", JobStatus.SUCCEEDED);
            String filename = job.artifact().filename();
            assertEquals(UUID.fromString(filename.substring(0, 36)) + ".png", filename);
            assertNotEquals(filename, secondJob.artifact().filename());
            assertEquals(filename, service.submit(request("first", 1)).artifact().filename());
            assertEquals("/api/graphics/tasks/first/artifact", job.artifact().downloadPath());
            assertEquals(3, job.artifact().bytes()); assertEquals(64, job.artifact().sha256().length());
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(service.artifact("first")));
            assertEquals(JobStatus.SUCCEEDED, new FileJobRepository(props, mapper).find("first").orElseThrow().status());
            assertThrows(IllegalArgumentException.class, () -> service.find("../escape"));
        } finally { release.countDown(); service.close(); store.close(); }
    }
    @Test void timedOutWorkerKeepsItsSlotUntilItActuallyExits() throws Exception {
        var props = properties(1); var mapper = new JsonMapper(); var repo = new FileJobRepository(props, mapper); var store = new FileArtifactStore(props);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = new AppGraphicsTaskService(repo, store, renderer(started, release, true), props, mapper);
        try {
            service.submit(request("slow", 1)); assertTrue(started.await(2, TimeUnit.SECONDS));
            await(service, "slow", JobStatus.TIMED_OUT);
            service.submit(request("queued", 1));
            assertEquals(1, service.activeCount()); assertEquals(JobStatus.QUEUED, service.find("queued").status());
            assertEquals(429, assertThrows(TaskException.class, () -> service.submit(request("overflow", 1))).status());
            release.countDown(); await(service, "queued", JobStatus.SUCCEEDED);
            assertEquals(JobStatus.TIMED_OUT, service.find("slow").status());
            assertThrows(TaskException.class, () -> service.artifact("slow"));
        } finally { release.countDown(); service.close(); store.close(); }
    }
    @Test void recoversInterruptedJobsAndCleansExpiredResults() throws Exception {
        var props = properties(10); var mapper = new JsonMapper(); var repo = new FileJobRepository(props, mapper); var store = new FileArtifactStore(props);
        repo.save(new GraphicsJob("interrupted", "fingerprint", JobStatus.RUNNING, 1, 1, null, null, null));
        repo.save(new GraphicsJob("expired", "fingerprint", JobStatus.FAILED, 1, 1, null, "ERROR", "failed"));
        var legacy = new Artifact("result.png", "image/png", 3, "hash", 1, 1, "px", "/api/graphics/tasks/legacy/artifact", null);
        repo.save(new GraphicsJob("legacy", "fingerprint", JobStatus.SUCCEEDED, 1, System.currentTimeMillis(), legacy, null, null));
        Files.write(Files.createDirectories(directory.resolve("artifacts/legacy")).resolve(legacy.filename()), new byte[]{1, 2, 3});
        var service = new AppGraphicsTaskService(repo, store, renderer(new CountDownLatch(0), new CountDownLatch(0), false), props, mapper);
        try {
            assertEquals("SERVICE_RESTARTED", service.find("interrupted").errorCode());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(service.artifact("legacy")));
            service.cleanupExpired(); assertTrue(repo.find("expired").isEmpty()); assertTrue(repo.find("interrupted").isPresent());
        } finally { service.close(); store.close(); }
    }
    @Test void outputLimitPreventsUnlimitedArtifactWrites() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new LimitedOutputStream(bytes, 3)) {
            out.write(new byte[]{1,2,3}); assertThrows(java.io.IOException.class, () -> out.write(4));
            assertEquals(3, bytes.size());
        }
    }
}
