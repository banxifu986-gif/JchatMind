package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.DocumentImageAssetCandidate;
import com.kama.jchatmind.model.dto.VisualAssetSummaryItem;
import com.kama.jchatmind.model.entity.DocumentAssetExtraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DocumentAssetExtractionMapper {
    int insert(DocumentAssetExtraction extraction);

    int markActive(
            @Param("extractionId") String extractionId,
            @Param("caption") String caption,
            @Param("ocrText") String ocrText,
            @Param("summary") String summary,
            @Param("visualKind") String visualKind,
            @Param("confidence") Double confidence,
            @Param("rawResult") String rawResult,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int markFailed(
            @Param("extractionId") String extractionId,
            @Param("rawResult") String rawResult,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int supersedeActiveByAssetId(
            @Param("assetId") String assetId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    Integer selectMaxAttemptCount(@Param("assetId") String assetId);

    boolean hasActiveVisualAsset(@Param("kbIds") List<String> kbIds);

    List<VisualAssetSummaryItem> selectActiveVisualAssetSummaries(
            @Param("kbIds") List<String> kbIds,
            @Param("limit") int limit
    );

    int resetStalePending(
            @Param("timeoutMinutes") int timeoutMinutes,
            @Param("now") LocalDateTime now
    );

    List<DocumentImageAssetCandidate> selectImageAssetsWithoutActiveExtraction(
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit
    );
}