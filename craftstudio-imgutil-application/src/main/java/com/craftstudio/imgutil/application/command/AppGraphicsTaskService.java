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
        jobs.forEach(job -> {
            if (!job.status().terminal()) {
                jobs.save(job.transition(JobStatus.FAILED, null, "SERVICE_RESTARTED", "服务重启中断了任务，请使用新的 requestId 重试"));
                try { artifacts.delete(job.taskId()); } catch (Exception e) { log.warn("清理中断任务失败: {}", job.taskId(), e); }
            }
        });
        timer.setRemoveOnCancelPolicy(true);
        executor = new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("graphics-worker-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
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
        renderer.validate(request);
        if (!artifacts.hasCapacity()) throw new TaskException(503, "STORAGE_FULL", "存储容量不足，请稍后重试");
        long now = System.currentTimeMillis();
        GraphicsJob job = new GraphicsJob(request.requestId(), fingerprint, JobStatus.QUEUED, now, now, null, null, null);
        jobs.save(job);
        try {
            if(request.callbackConfig()!=null)callbacks.register(job.taskId(),request.callbackConfig());
            executor.execute(() -> execute(request));
        }
        catch (RejectedExecutionException e) { jobs.delete(job.taskId());if(callbacks!=null)callbacks.delete(job.taskId());throw new TaskException(429, "QUEUE_FULL", "图形任务队列已满，请稍后重试"); }
        catch (RuntimeException e) { jobs.delete(job.taskId());if(callbacks!=null)callbacks.delete(job.taskId());throw e; }
        return job;
    }
    public GraphicsJob find(String id) { return jobs.find(id).orElseThrow(() -> new TaskException(404, "TASK_NOT_FOUND", "任务不存在或已过期")); }
    public Path artifact(String id) {
        GraphicsJob job = find(id);
        if (job.status() != JobStatus.SUCCEEDED || job.artifact() == null) throw new TaskException(409, "ARTIFACT_NOT_READY", "任务尚未成功生成文件");
        Path file = artifacts.resolve(id, job.artifact().filename());
        if (!Files.isRegularFile(file)) throw new TaskException(404, "ARTIFACT_EXPIRED", "文件已过期");
        return file;
    }
    private void execute(GraphicsRequest request) {
        String id = request.requestId(); Path temporary = null;
        Thread worker = Thread.currentThread(); ScheduledFuture<?> deadline = null;
        try {
            synchronized (this) { executing.add(id); jobs.save(find(id).transition(JobStatus.RUNNING, null, null, null)); }
            deadline = timer.schedule(() -> timeout(id, worker), properties.taskTimeoutSeconds(), TimeUnit.SECONDS);
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
            synchronized (this) {
                GraphicsJob job = find(id);
                if (!job.status().terminal()) jobs.save(job.transition(JobStatus.FAILED, null,
                        e instanceof IllegalArgumentException ? "INVALID_GRAPHICS" : "RENDER_FAILED",
                        e instanceof IllegalArgumentException ? e.getMessage() : "图形处理失败，请检查服务日志"));
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
            // Cancellation does not release a worker slot until this method really exits.
            synchronized (this) { Thread.interrupted(); }
            try {
                if (temporary != null) Files.deleteIfExists(temporary);
                if (find(id).status() != JobStatus.SUCCEEDED) artifacts.delete(id);
            } catch (Exception e) { log.warn("清理任务文件失败: {}", id, e); }
            synchronized (this) { executing.remove(id); }
        }
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
        executor.shutdownNow(); timer.shutdownNow();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.warn("存在未退出的图形任务，由容器终止进程"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
