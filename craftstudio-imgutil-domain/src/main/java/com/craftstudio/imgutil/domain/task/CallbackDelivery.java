package com.craftstudio.imgutil.domain.task;

import com.craftstudio.imgutil.domain.graphics.CallbackConfig;

public record CallbackDelivery(String taskId,CallbackConfig config,int attempts,long nextAttemptAt,
                               String status,String lastError) {
    public static CallbackDelivery pending(String id,CallbackConfig config){return new CallbackDelivery(id,config,0,0,"PENDING",null);}
}
