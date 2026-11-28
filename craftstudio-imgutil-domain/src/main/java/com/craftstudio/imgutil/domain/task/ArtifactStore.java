package com.craftstudio.imgutil.domain.task;

import com.craftstudio.imgutil.domain.graphics.RenderResult;
import java.nio.file.Path;

public interface ArtifactStore {
    Path createTemporary(String id) throws Exception;
    Artifact publish(String id, Path source, RenderResult result) throws Exception;
    default Artifact publishNamed(String id, String name, Path source, RenderResult result) throws Exception {
        throw new UnsupportedOperationException("存储实现不支持零件素材");
    }
    Path resolve(String id, String filename);
    void delete(String id) throws Exception;
}
