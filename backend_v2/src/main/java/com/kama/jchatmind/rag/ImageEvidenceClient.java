package com.kama.jchatmind.rag;

public interface ImageEvidenceClient {
    String analyze(String prompt, byte[] pngBytes);
}