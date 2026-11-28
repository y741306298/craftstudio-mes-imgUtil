package com.craftstudio.imgutil.application.command;

import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class AppGraphicsTaskService {
    private static final Logger log = LoggerFactory.getLogger(AppGraphicsTaskService.class);
    private final JobRepository jobs;
    private final ArtifactStore artifacts;
    private final GraphicsRenderer renderer;
    private final GraphicsProperties properties;
    private final CallbackRepository callbacks;
    private final JsonMapper canonicalMapper = JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final Set<String> executing = new HashSet<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

    public AppGraphicsTaskService(JobRepository jobs, ArtifactStore artifacts, GraphicsRenderer renderer, GraphicsProperties properties, JsonMapper mapper) {
        this(jobs,artifacts,renderer,properties,mapper,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AppGraphicsTaskService(JobRepository jobs, ArtifactStore artifacts, GraphicsRenderer renderer, GraphicsProperties properties, JsonMapper mapper, CallbackRepository callbacks) {
        this.jobs = jobs; this.artifacts = artifacts; this.renderer = renderer; this.properties = properties;
        this.callbacks=callbacks;
        var pending = new ArrayList<PendingJob>();
        jobs.forEach(job -> {
            if (job.status() == JobStatus.QUEUED && job.queuedRequest() != null) {
                registerCallback(job.queuedRequest());
                pending.add(new PendingJob(job.taskId(), job.createdAt()));
            } else if (!job.status().terminal()) {
                jobs.save(job.transition(JobStatus.FAILED, null, "SERVICE_RESTARTED", "服务重启中断了任务，请使用新的 requestId 重试"));
                try { artifacts.delete(job.taskId()); } catch (Exception e) { log.warn("清理中断任务失败: {}", job.taskId(), e); }
            }
        });
        timer.setRemoveOnCancelPolicy(true);
        executor = new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), Thread.ofPlatform().name("graphics-worker-", 0).factory());
        pending.sort(Comparator.comparingLong(PendingJob::createdAt).thenComparing(PendingJob::id));
        pending.forEach(job -> enqueue(job.id()));
    }

    public synchronized GraphicsJob submit(GraphicsRequest request) {
        request.validate();
        if(request.callbackConfig()!=null && (callbacks==null || properties.apiKey()==null || properties.apiKey().isBlank()))throw new IllegalArgumentException("配置 API Key 后才可使用异步回调");
        String fingerprint = fingerprint(request);
        var existing = jobs.find(request.requestId());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) throw new TaskException(409, "IDEMPOTENCY_CONFLICT", "requestId 已被其他参数使用");
            return existing.get();
        }
        if (executor.isShutdown()) throw new TaskException(503, "SERVICE_STOPPING", "图形服务正在停止，请稍后重试");
        renderer.validate(request);
        if (!artifacts.hasCapacity()) throw new TaskException(503, "STORAGE_FULL", "存储容量不足，请稍后重试");
        long now = System.currentTimeMillis();
        GraphicsJob job = new GraphicsJob(request.requestId(), fingerprint, JobStatus.QUEUED, now, now,
                null, null, null, request);
        jobs.save(job);
        try {
            registerCallback(request);
            enqueue(job.taskId());
        } catch (RuntimeException e) {
            jobs.delete(job.taskId());
            if (request.callbackConfig() != null) callbacks.delete(job.taskId());
            throw e;
        }
        return job;
    }
    private void registerCallback(GraphicsRequest request) {
        if (request.callbackConfig() != null) callbacks.register(request.requestId(), request.callbackConfig());
    }
    private void enqueue(String id) {
        // Keep only task IDs in memory. Request bodies stay on disk until a worker starts them.
        executor.execute(() -> execute(id));
    }
    private record PendingJob(String id, long createdAt) {}

    public GraphicsJob find(String id) { return jobs.find(id).orElseThrow(() -> new TaskException(404, "TASK_NOT_FOUND", "任务不存在或已过期")); }
    public Path artifact(String id) {
        GraphicsJob job = find(id);
        if (job.status() != JobStatus.SUCCEEDED || job.artifact() == null) throw new TaskException(409, "ARTIFACT_NOT_READY", "任务尚未成功生成文件");
        Path file = artifacts.resolve(id, job.artifact().filename());
        if (!Files.isRegularFile(file)) throw new TaskException(404, "ARTIFACT_EXPIRED", "文件已过期");
        return file;
    }
    private void execute(String id) {
        Path temporary = null;
        Thread worker = Thread.currentThread(); ScheduledFuture<?> deadline = null;
        boolean started = false;
        try {
            GraphicsRequest request;
            synchronized (this) {
                if (executor.isShutdown()) return;
                GraphicsJob job = find(id);
                request = job.queuedRequest();
                jobs.save(job.transition(JobStatus.RUNNING, null, null, null));
                executing.add(id);
                started = true;
                deadline = timer.schedule(() -> timeout(id, worker), properties.taskTimeoutSeconds(), TimeUnit.SECONDS);
            }
            // Earlier jobs may have filled local storage since this request was accepted.
            if (!artifacts.hasCapacity()) {
                fail(id, "STORAGE_FULL", "存储容量不足，请稍后重试");
                return;
            }
            temporary = artifacts.createTemporary(id);
            RenderResult result;
            try (var out = new LimitedOutputStream(Files.newOutputStream(temporary), properties.maxArtifactBytes())) { result = renderer.render(request, out); }
            if (worker.isInterrupted() || find(id).status() != JobStatus.RUNNING) return;
            Artifact artifact = artifacts.publish(id, temporary, result);
            synchronized (this) {
                GraphicsJob job = find(id);
                if (job.status() == JobStatus.RUNNING) jobs.save(job.transition(JobStatus.SUCCEEDED, artifact, null, null));
            }
        } catch (Exception e) {
            log.error("图形任务失败: {}", id, e);
            fail(id, e instanceof IllegalArgumentException ? "INVALID_GRAPHICS" : "RENDER_FAILED",
                    e instanceof IllegalArgumentException ? e.getMessage() : "图形处理失败，请检查服务日志");
        } finally {
            if (started) finishExecution(id, temporary, deadline);
        }
    }
    private synchronized void fail(String id, String code, String message) {
        GraphicsJob job = find(id);
        if (!job.status().terminal()) jobs.save(job.transition(JobStatus.FAILED, null, code, message));
    }
    private void finishExecution(String id, Path temporary, ScheduledFuture<?> deadline) {
        if (deadline != null) deadline.cancel(false);
        // Cancellation does not release a worker slot until this method really exits.
        synchronized (this) { Thread.interrupted(); }
        try {
            if (temporary != null) Files.deleteIfExists(temporary);
            if (find(id).status() != JobStatus.SUCCEEDED) artifacts.delete(id);
        } catch (Exception e) { log.warn("清理任务文件失败: {}", id, e); }
        synchronized (this) { executing.remove(id); }
    }
    private synchronized void timeout(String id, Thread worker) {
        GraphicsJob job = find(id);
        if (job.status() == JobStatus.RUNNING) {
            jobs.save(job.transition(JobStatus.TIMED_OUT, null, "TASK_TIMEOUT", "任务超时，请降低尺寸或复杂度")); worker.interrupt();
        }
    }
    private String fingerprint(GraphicsRequest request) {
        try {
            // JSON object key order must not change idempotency semantics.
            Object identity=request.callbackConfig()==null?new LegacyRequest(request.requestId(),request.raster(),request.svg(),request.mes()):request;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalMapper.writeValueAsBytes(identity)));
        } catch (Exception e) { throw new IllegalArgumentException("请求无法序列化", e); }
    }
    private record LegacyRequest(String requestId,RasterSpec raster,SvgSpec svg,MesSpec mes){}
    @Scheduled(fixedDelayString = "${graphics.cleanup-interval-ms:600000}")
    public synchronized void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(properties.retentionHours());
        jobs.forEach(job -> {
            if (job.status().terminal() && job.updatedAt() < cutoff && !executing.contains(job.taskId())) {
                try { artifacts.delete(job.taskId()); jobs.delete(job.taskId());if(callbacks!=null)callbacks.delete(job.taskId()); }
                catch (Exception e) { log.warn("清理过期任务失败: {}", job.taskId(), e); }
            }
        });
    }
    public int activeCount() { return executor.getActiveCount(); }
    public int queuedCount() { return executor.getQueue().size(); }
    @PreDestroy public void close() {
        synchronized (this) {
            executor.shutdownNow();
            timer.shutdownNow();
        }
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.warn("存在未退出的图形任务，由容器终止进程"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
