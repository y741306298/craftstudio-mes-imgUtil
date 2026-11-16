package com.craftstudio.imgutil.interfaces.api;

import com.craftstudio.imgutil.application.command.AppGraphicsTaskService;
import com.craftstudio.imgutil.domain.graphics.*;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/graphics")
public class GraphicsController {
    private final AppGraphicsTaskService tasks;
    private final GraphicsProperties properties;
    private final CallbackRepository callbacks;
    public GraphicsController(AppGraphicsTaskService tasks, GraphicsProperties properties, CallbackRepository callbacks) { this.tasks = tasks; this.properties = properties;this.callbacks=callbacks; }
    public record JobView(String taskId, JobStatus status, long createdAt, long updatedAt, Artifact artifact, String errorCode, String message) {
        static JobView of(GraphicsJob job) { return new JobView(job.taskId(), job.status(), job.createdAt(), job.updatedAt(), job.artifact(), job.errorCode(), job.message()); }
    }
    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<JobView>> submit(@RequestBody GraphicsRequest request) {
        GraphicsJob job = tasks.submit(request);
        return ResponseEntity.status(job.status().terminal() ? 200 : 202)
                .location(URI.create("/api/graphics/tasks/" + job.taskId())).body(ApiResponse.success(JobView.of(job)));
    }
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<JobView> find(@PathVariable String taskId) { return ApiResponse.success(JobView.of(tasks.find(taskId))); }
    @GetMapping("/tasks/{taskId}/callback")
    public ApiResponse<Map<String,Object>> callback(@PathVariable String taskId){
        tasks.find(taskId);
        return callbacks.find(taskId).map(d->ApiResponse.<Map<String,Object>>success(Map.of("status",d.status(),"attempts",d.attempts(),"nextAttemptAt",d.nextAttemptAt(),"lastError",d.lastError()==null?"":d.lastError())))
                .orElseGet(()->ApiResponse.success(Map.of("status","NOT_CONFIGURED")));
    }
    @GetMapping("/tasks/{taskId}/artifact")
    public ResponseEntity<FileSystemResource> artifact(@PathVariable String taskId) throws Exception {
        Path path = tasks.artifact(taskId); Artifact artifact = tasks.find(taskId).artifact();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.mediaType())).contentLength(java.nio.file.Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(artifact.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox; default-src 'none'")
                .cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));
    }
    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        Map<String, Object> capabilities = new java.util.LinkedHashMap<>(Map.of("raster", RasterSpec.Kind.values(), "svg", SvgSpec.Kind.values(),
                "pixelLimitsEnabled", false, "maxDpi", properties.maxDpi(),
                "activeTasks", tasks.activeCount(), "queuedTasks", tasks.queuedCount(),
                "fonts", GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(),"mesCompatibilityVersion",1,"callbackProtocolVersion",1));
        capabilities.put("spliceMarksBatchVersion", 1);
        capabilities.put("splicePieceVersion", 1);
        return ApiResponse.success(capabilities);
    }
}
