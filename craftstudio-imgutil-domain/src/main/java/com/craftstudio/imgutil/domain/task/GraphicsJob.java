package com.craftstudio.imgutil.domain.task;

import com.craftstudio.imgutil.domain.graphics.GraphicsRequest;

public record GraphicsJob(String taskId, String fingerprint, JobStatus status, long createdAt,
                          long updatedAt, Artifact artifact, String errorCode, String message,
                          GraphicsRequest queuedRequest) {
    public GraphicsJob(String taskId, String fingerprint, JobStatus status, long createdAt,
                       long updatedAt, Artifact artifact, String errorCode, String message) {
        this(taskId, fingerprint, status, createdAt, updatedAt, artifact, errorCode, message, null);
    }

    public GraphicsJob transition(JobStatus next, Artifact result, String code, String detail) {
        return new GraphicsJob(taskId, fingerprint, next, createdAt, System.currentTimeMillis(), result, code, detail);
    }
}
