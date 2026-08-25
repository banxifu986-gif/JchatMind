package com.kama.jchatmind.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageServiceImplTest {

    @TempDir
    Path storageDirectory;

    @Test
    void shouldDeleteOnlyRequestedKnowledgeBaseDirectoryAndTreatMissingDirectoryAsComplete() throws Exception {
        Path deletedKnowledgeBaseDirectory = storageDirectory.resolve("kb-owned");
        Path retainedKnowledgeBaseDirectory = storageDirectory.resolve("kb-retained");
        Files.createDirectories(deletedKnowledgeBaseDirectory.resolve("document-1"));
        Files.writeString(deletedKnowledgeBaseDirectory.resolve("document-1/file.md"), "content");
        Files.createDirectories(retainedKnowledgeBaseDirectory);
        Files.writeString(retainedKnowledgeBaseDirectory.resolve("keep.md"), "content");
        DocumentStorageServiceImpl storageService = storageService();

        storageService.deleteKnowledgeBaseDirectory("kb-owned");
        storageService.deleteKnowledgeBaseDirectory("kb-owned");

        assertThat(Files.exists(deletedKnowledgeBaseDirectory)).isFalse();
        assertThat(Files.exists(retainedKnowledgeBaseDirectory.resolve("keep.md"))).isTrue();
    }

    @Test
    void shouldRejectKnowledgeBaseDirectoryOutsideConfiguredStorageRoot() {
        DocumentStorageServiceImpl storageService = storageService();

        assertThatThrownBy(() -> storageService.deleteKnowledgeBaseDirectory("../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("知识库存储路径非法");
    }

    private DocumentStorageServiceImpl storageService() {
        DocumentStorageServiceImpl storageService = new DocumentStorageServiceImpl();
        ReflectionTestUtils.setField(storageService, "baseStoragePath", storageDirectory.toString());
        return storageService;
    }
}
