package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessResult;

public interface HarnessInterceptor {
    void beforeExecution(HarnessContext context, HarnessResult result);

    void afterExecution(HarnessContext context, String toolResult);

    void onError(HarnessContext context, Exception exception);

    int getOrder();
}
