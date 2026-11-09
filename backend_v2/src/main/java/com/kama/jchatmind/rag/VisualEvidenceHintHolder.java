package com.kama.jchatmind.rag;

/**
 * 单轮对话内的视觉证据提示，由意图决策写入、KnowledgeTools 读取。
 */
public class VisualEvidenceHintHolder {

    private volatile Boolean requiresVisualEvidence;

    public void set(Boolean requiresVisualEvidence) {
        this.requiresVisualEvidence = requiresVisualEvidence;
    }

    public Boolean get() {
        return requiresVisualEvidence;
    }
}