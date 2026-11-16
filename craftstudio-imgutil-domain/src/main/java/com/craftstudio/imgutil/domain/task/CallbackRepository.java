package com.craftstudio.imgutil.domain.task;

import com.craftstudio.imgutil.domain.graphics.CallbackConfig;
import java.util.Optional;
import java.util.function.Consumer;

public interface CallbackRepository {
    void register(String id,CallbackConfig config);
    Optional<CallbackDelivery> find(String id);
    void save(CallbackDelivery delivery);
    void delete(String id);
    void forEach(Consumer<CallbackDelivery> action);
}
