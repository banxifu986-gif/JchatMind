package com.kama.jchatmind.intent;

public enum IntentDecisionSource {
    RULE,
    LIGHTWEIGHT_MODEL,
    LLM_FALLBACK,
    RULE_AND_MODEL
}
