package com.craftstudio.imgutil.infra.storage;

import com.aliyun.oss.*;
import com.aliyun.oss.model.ObjectMetadata;
import com.craftstudio.imgutil.domain.graphics.RenderResult;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@DependsOn("dataDirectoryLock")
public class FileArtifactStore implements ArtifactStore {
    private static final Pattern ARTIFACT_FILENAME = Pattern.compile(
            "(?:result|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.(?:png|svg)");
    private final GraphicsProperties properties;
    private final Path root, artifacts, temporary;
    private final OSS oss;
    public FileArtifactStore(GraphicsProperties properties) throws Exception {
        this.properties = properties;
        root = Path.of(properties.dataDirectory()).toAbsolutePath(); artifacts = root.resolve("artifacts"); temporary = root.resolve("tmp");
        Files.createDirectories(artifacts); Files.createDirectories(temporary);
        try (var leftovers = Files.newDirectoryStream(temporary)) { for (Path p : leftovers) Files.deleteIfExists(p); }
        if (properties.oss() != null && properties.oss().enabled()) {
            ClientBuilderConfiguration config = new ClientBuilderConfiguration();
            config.setConnectionTimeout(5000); config.setSocketTimeout(15000); config.setMaxErrorRetry(1);
            oss = new OSSClientBuilder().build(properties.oss().endpoint(), properties.oss().accessKeyId(), properties.oss().accessKeySecret(), config);
        } else oss = null;
    }
    @Override public Path createTemporary(String id) throws Exception { validate(id); return Files.createTempFile(temporary, id + "-", ".part"); }
    @Override public Artifact publish(String id, Path source, RenderResult result) throws Exception {
        return publishFile(id, source, result, true);
    }
    @Override public Artifact publishNamed(String id, String name, Path source, RenderResult result) throws Exception {
        if (!name.matches("[a-z][a-z-]{0,39}") || !result.extension().equals("png")) throw new IllegalArgumentException("零件素材名无效");
        return publishFile(id, source, result, false);
    }
    private Artifact publishFile(String id, Path source, RenderResult result, boolean primaryArtifact) throws Exception {
        validate(id);
        String filename = UUID.randomUUID() + "." + result.extension();
        Path directory = artifacts.resolve(id); Files.createDirectories(directory);
        Path target = directory.resolve(filename); Files.move(source, target);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(target)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n); }
        String objectName = null;
        if (oss != null && result.publishToOss()) {
            objectName = key(id, filename);
            ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentType(result.mediaType()); metadata.setContentLength(Files.size(target));
            try (var input = Files.newInputStream(target)) { oss.putObject(properties.oss().bucket(), objectName, input, metadata); }
        }
        return new Artifact(filename, result.mediaType(), Files.size(target), HexFormat.of().formatHex(digest.digest()),
                result.width(), result.height(), result.unit(), primaryArtifact ? "/api/graphics/tasks/" + id + "/artifact" : null,
                objectName, result.assets());
    }
    @Override public Path resolve(String id, String filename) {
        validate(id);
        if (!ARTIFACT_FILENAME.matcher(filename).matches()) throw new IllegalArgumentException("文件名无效");
        return artifacts.resolve(id).resolve(filename);
    }
    @Override public void delete(String id) throws Exception {
        validate(id);
        // Remote objects are owned by MES and retained. Configure an OSS lifecycle separately.
        Path directory = artifacts.resolve(id);
        if (Files.exists(directory)) {
            try (var files = Files.newDirectoryStream(directory)) { for (Path p : files) Files.deleteIfExists(p); }
            Files.deleteIfExists(directory);
        }
    }
    @Override public boolean hasCapacity() {
        try (var files = Files.walk(root)) {
            long used = 0, reserve = Math.multiplyExact((long) properties.maxArtifactBytes(), properties.workers() + 1L);
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path p = iterator.next(); if (Files.isRegularFile(p)) used += Files.size(p);
                if (used > properties.maxStorageBytes() - reserve) return false;
            }
            return Files.getFileStore(root).getUsableSpace() > reserve;
        } catch (Exception e) { return false; }
    }
    private String key(String id, String filename) {
        String prefix = properties.oss().prefix();
        return (prefix == null || prefix.isBlank() ? "imgutil/" : prefix.replaceAll("/+$", "") + "/") + id + "/" + filename;
    }
    private void validate(String id) { if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw new IllegalArgumentException("任务编号无效"); }
    @PreDestroy public void close() { if (oss != null) oss.shutdown(); }
}
