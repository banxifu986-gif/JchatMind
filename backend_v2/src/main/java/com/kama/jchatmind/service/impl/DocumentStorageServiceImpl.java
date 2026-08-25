package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Service
@Slf4j
public class DocumentStorageServiceImpl implements DocumentStorageService {

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @Override
    public String saveFile(String kbId, String documentId, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空");
        }

        // 构建文件存储路径: basePath/kbId/documentId/filename
        Path kbDir = Paths.get(baseStoragePath, kbId);
        Path documentDir = kbDir.resolve(documentId);
        
        // 确保目录存在
        Files.createDirectories(documentDir);
        
        // 生成唯一文件名（使用 UUID + 原始文件名）
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        
        // 保存文件
        Path targetPath = documentDir.resolve(uniqueFilename);
        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            compensatePartialWrite(targetPath, documentDir, kbDir);
            throw e;
        }
        
        // 返回相对路径（相对于 baseStoragePath）
        String relativePath = Paths.get(kbId, documentId, uniqueFilename).toString().replace("\\", "/");
        log.info("文件保存成功: kbId={}, documentId={}, filename={}, path={}", 
                kbId, documentId, originalFilename, relativePath);
        
        return relativePath;
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path fullPath = getFilePath(filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.info("文件删除成功: {}", filePath);
            
            // 尝试删除空的父目录
            Path parentDir = fullPath.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try {
                    Files.delete(parentDir);
                    log.info("目录删除成功: {}", parentDir);
                } catch (IOException e) {
                    // 目录不为空或其他原因无法删除，忽略
                    log.debug("目录删除失败（可能不为空）: {}", parentDir);
                }
            }
        } else {
            log.warn("文件不存在，跳过删除: {}", filePath);
        }
    }

    @Override
    public Path getFilePath(String filePath) {
        return Paths.get(baseStoragePath, filePath);
    }

    @Override
    public boolean fileExists(String filePath) {
        Path fullPath = getFilePath(filePath);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }

    private void compensatePartialWrite(Path targetPath, Path documentDir, Path kbDir) {
        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException cleanupException) {
            log.warn("文件写入失败后的残留文件清理失败", cleanupException);
        }
        deleteEmptyDirectory(documentDir);
        deleteEmptyDirectory(kbDir);
    }

    private void deleteEmptyDirectory(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException cleanupException) {
            log.debug("文件写入失败后的空目录清理跳过", cleanupException);
        }
    }

    @Override
    public void deleteKnowledgeBaseDirectory(String kbId) throws IOException {
        Path baseDirectory = Paths.get(baseStoragePath).toAbsolutePath().normalize();
        Path knowledgeBaseDirectory = baseDirectory.resolve(kbId).normalize();
        if (!baseDirectory.equals(knowledgeBaseDirectory.getParent())) {
            throw new IllegalArgumentException("知识库存储路径非法");
        }
        if (!Files.exists(knowledgeBaseDirectory)) {
            return;
        }

        Files.walkFileTree(knowledgeBaseDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("知识库文件目录删除成功: kbId={}", kbId);
    }
}
