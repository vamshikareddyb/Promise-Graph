package dev.promisegraph.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Timeouts and retries per kind of activity. */
final class RetryPolicies {

    private RetryPolicies() {}

    static final RetryOptions IO_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .setMaximumAttempts(10)
            .build();

    static final RetryOptions MODEL_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(60))
            .setMaximumAttempts(5)
            .setDoNotRetry("ModelRequestInvalid")
            .build();

    static ActivityOptions io(Duration timeout) {
        return ActivityOptions.newBuilder().setStartToCloseTimeout(timeout).setRetryOptions(IO_RETRY).build();
    }

    static ActivityOptions model(String taskQueue) {
        return ActivityOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(MODEL_RETRY)
                .build();
    }
}
