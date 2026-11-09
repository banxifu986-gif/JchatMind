package com.kama.jchatmind.ingestion;

public interface DocumentImageExtractionClient {
    String extract(String prompt, byte[] pngBytes);

    String visionModel();
}
