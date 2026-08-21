package com.kama.jchatmind.ingestion;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class IngestionTaskStateMachine {

    public enum Status {
        QUEUED,
        RUNNING,
        RETRYING,
        FAILED,
        DEAD_LETTER,
        CANCELLED,
        SUCCEEDED
    }

    public enum Event {
        START,
        SUCCEED,
        CANCEL,
        RETRY,
        REQUEUE,
        FAIL,
        DEAD_LETTER,
        MANUAL_RETRY
    }

    public Status transition(Status currentStatus, Event event) {
        Objects.requireNonNull(currentStatus, "任务状态不能为空");
        Objects.requireNonNull(event, "任务事件不能为空");

        return switch (currentStatus) {
            case QUEUED -> switch (event) {
                case START -> Status.RUNNING;
                case CANCEL -> Status.CANCELLED;
                default -> reject(currentStatus, event);
            };
            case RUNNING -> switch (event) {
                case SUCCEED -> Status.SUCCEEDED;
                case RETRY -> Status.RETRYING;
                case FAIL -> Status.FAILED;
                case DEAD_LETTER -> Status.DEAD_LETTER;
                default -> reject(currentStatus, event);
            };
            case RETRYING -> switch (event) {
                case REQUEUE -> Status.QUEUED;
                case DEAD_LETTER -> Status.DEAD_LETTER;
                case CANCEL -> Status.CANCELLED;
                default -> reject(currentStatus, event);
            };
            case FAILED, DEAD_LETTER -> switch (event) {
                case MANUAL_RETRY -> Status.QUEUED;
                default -> reject(currentStatus, event);
            };
            case CANCELLED, SUCCEEDED -> reject(currentStatus, event);
        };
    }

    private Status reject(Status currentStatus, Event event) {
        throw new IllegalStateException("不允许任务从 " + currentStatus + " 执行事件 " + event);
    }
}
