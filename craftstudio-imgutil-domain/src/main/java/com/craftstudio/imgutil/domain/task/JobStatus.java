package com.craftstudio.imgutil.domain.task;

public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT;
    public boolean terminal() { return this != QUEUED && this != RUNNING; }
}
