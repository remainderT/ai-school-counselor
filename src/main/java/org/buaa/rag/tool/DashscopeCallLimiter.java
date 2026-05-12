package org.buaa.rag.tool;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

/**
 * Limits concurrent DashScope HTTP calls during offline experiments.
 */
@Component
public class DashscopeCallLimiter {

    private static final int MAX_CONCURRENT_CALLS = 2;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_CALLS, true);

    public <T> T call(Callable<T> callable) throws Exception {
        semaphore.acquire();
        try {
            return callable.call();
        } finally {
            semaphore.release();
        }
    }
}
