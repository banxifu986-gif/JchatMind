package com.kama.jchatmind.agent.harness;

public final class HarnessConstants {

    public static final String ATTRIBUTE_APPROVAL_REQUEST_ID = "approvalRequestId";

    /** ToolContext 中携带 run 级元数据（HarnessRunMetadata）的键。 */
    public static final String TOOL_CONTEXT_RUN_METADATA = "harnessRunMetadata";

    /** ToolContext 中携带 per-call 上下文队列（签名 -> Queue&lt;HarnessContext&gt;）的键。 */
    public static final String TOOL_CONTEXT_CALL_CONTEXTS = "harnessCallContexts";

    private HarnessConstants() {
    }
}
