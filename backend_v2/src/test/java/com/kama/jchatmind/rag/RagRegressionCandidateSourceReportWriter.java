package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class RagRegressionCandidateSourceReportWriter {

    private final ObjectMapper objectMapper;

    RagRegressionCandidateSourceReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path outputPath, Object report) throws IOException {
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
    }
}
