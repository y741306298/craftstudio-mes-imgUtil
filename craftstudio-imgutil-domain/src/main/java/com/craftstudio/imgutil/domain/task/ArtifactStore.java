package com.craftstudio.imgutil.domain.task;

import com.craftstudio.imgutil.domain.graphics.RenderResult;
import java.nio.file.Path;

public interface ArtifactStore {
    Path createTemporary(String id) throws Exception;
    Artifact publish(String id, Path source, RenderResult result) throws Exception;
    Path resolve(String id, String filename);
    void delete(String id) throws Exception;
    boolean hasCapacity();
}
