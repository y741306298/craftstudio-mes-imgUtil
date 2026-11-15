package com.craftstudio.imgutil.infra.persistence;

import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.DependsOn;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.Optional;
import java.util.function.Consumer;

/** Single-instance persistent job manifests; no unbounded in-memory result map. */
@Repository
@DependsOn("dataDirectoryLock")
public class FileJobRepository implements JobRepository {
    private final Path directory;
    private final JsonMapper mapper;
    public FileJobRepository(GraphicsProperties properties, JsonMapper mapper) throws Exception {
        this.directory = Path.of(properties.dataDirectory()).toAbsolutePath().resolve("jobs");
        this.mapper = mapper; Files.createDirectories(directory);
    }
    private Path path(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw new IllegalArgumentException("任务编号无效");
        return directory.resolve(id + ".json");
    }
    @Override public Optional<GraphicsJob> find(String id) {
        Path path = path(id);
        if (!Files.exists(path)) return Optional.empty();
        try { return Optional.of(mapper.readValue(path.toFile(), GraphicsJob.class)); }
        catch (Exception e) { throw new IllegalStateException("读取任务失败", e); }
    }
    @Override public void save(GraphicsJob job) {
        Path target = path(job.taskId()), temporary = directory.resolve(job.taskId() + ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), job);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { throw new IllegalStateException("保存任务失败", e); }
    }
    @Override public void delete(String id) {
        try { Files.deleteIfExists(path(id)); }
        catch (Exception e) { throw new IllegalStateException("清理任务失败", e); }
    }
    @Override public void forEach(Consumer<GraphicsJob> action) {
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                GraphicsJob job = mapper.readValue(file.toFile(), GraphicsJob.class); action.accept(job);
            }
        } catch (Exception e) { throw new IllegalStateException("遍历任务失败", e); }
    }
}
