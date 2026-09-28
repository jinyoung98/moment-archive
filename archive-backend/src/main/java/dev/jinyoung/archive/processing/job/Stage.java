package dev.jinyoung.archive.processing.job;

/** 처리 단계. schema.md §5.6 processing_jobs.stage. 단계 분리 이유는 같은 절. */
public enum Stage {
    PROBE, THUMB, DERIVE
}
