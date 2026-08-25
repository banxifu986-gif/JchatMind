package com.kama.jchatmind.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageServiceDeletionContractTest {

    @Test
    void shouldExposeIdempotentKnowledgeBaseDirectoryDeletion() throws Exception {
        Method method = DocumentStorageService.class.getMethod("deleteKnowledgeBaseDirectory", String.class);

        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(method.isDefault()).isTrue();
    }

    @Test
    void shouldRejectDirectoryDeletionWhenStorageImplementationDoesNotOverrideIt() {
        DocumentStorageService storageService = new DocumentStorageService() {
            @Override
            public String saveFile(String kbId, String documentId, org.springframework.web.multipart.MultipartFile file) {
                return null;
            }

            @Override
            public void deleteFile(String filePath) {
            }

            @Override
            public java.nio.file.Path getFilePath(String filePath) {
                return null;
            }

            @Override
            public boolean fileExists(String filePath) {
                return false;
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> storageService.deleteKnowledgeBaseDirectory("kb-owned")
        ).isInstanceOf(UnsupportedOperationException.class);
    }
}
