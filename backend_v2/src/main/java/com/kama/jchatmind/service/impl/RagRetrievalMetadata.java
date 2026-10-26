package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

final class RagRetrievalMetadata {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static String extractRetrievableTitle(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode titleNode = root.get("retrievableTitle");
            if (titleNode == null || !titleNode.isTextual()) {
                titleNode = root.get("title");
            }
            return titleNode != null && titleNode.isTextual() ? titleNode.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    static String extractMetadataText(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    static Integer extractMetadataInt(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.canConvertToInt() ? node.asInt() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

}
