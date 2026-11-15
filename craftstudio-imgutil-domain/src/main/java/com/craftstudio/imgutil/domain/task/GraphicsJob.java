package com.craftstudio.imgutil.domain.task;

public record GraphicsJob(String taskId, String fingerprint, JobStatus status, long createdAt,
                          long updatedAt, Artifact artifact, String errorCode, String message) {
    public GraphicsJob transition(JobStatus next, Artifact result, String code, String detail) {
        return new GraphicsJob(taskId, fingerprint, next, createdAt, System.currentTimeMillis(), result, code, detail);
    }
}
