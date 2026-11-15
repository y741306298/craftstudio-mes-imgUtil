package com.craftstudio.imgutil.domain.task;

import java.util.Optional;
import java.util.function.Consumer;

public interface JobRepository {
    Optional<GraphicsJob> find(String id);
    void save(GraphicsJob job);
    void delete(String id);
    void forEach(Consumer<GraphicsJob> action);
}
