package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentMultimodalRuntimeEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    Report evaluate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan,
            Map<String, String> routesByCaseId,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        validateCandidate(profile, runtimePlan);
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.CandidateCase> casesById = indexCases(profile);
        validateCaseCoverage(casesById.keySet(), routesByCaseId, resultsByCaseId);
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunksByRuntimeId =
                indexRuntimeChunks(profile, runtimePlan);

        int answerableCaseCount = (int) profile.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .count();
        int abstentionCaseCount = profile.cases().size() - answerableCaseCount;
        int goldAssetEvidenceHitCount = 0;
        int routeMismatchCount = 0;
        int permissionViolationCount = 0;
        int unknownRuntimeChunkCount = 0;
        int goldChunkMissCount = 0;
        int assetIdMismatchCount = 0;
        int assetTypeMismatchCount = 0;
        int assetLocatorMismatchCount = 0;
        int goldEvidenceMismatchCount = 0;
        Set<String> reasons = new LinkedHashSet<>();
        String knowledgeBaseId = profile.assetImportPlan().knowledgeBaseId();

        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (!candidateCase.expectedRoute().equals(routesByCaseId.get(candidateCase.caseId()))) {
                routeMismatchCount++;
            }
            for (RagRetrievalResult result : resultsByCaseId.get(candidateCase.caseId())) {
                if (!knowledgeBaseId.equals(result.getKbId())) {
                    permissionViolationCount++;
                }
                if (!chunksByRuntimeId.containsKey(result.getChunkId())) {
                    unknownRuntimeChunkCount++;
                }
            }
            if (candidateCase.shouldAbstain()) {
                continue;
            }

            G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset expectedAsset =
                    expectedAsset(profile, candidateCase);
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow expectedChunk =
                    findExpectedChunk(chunksByRuntimeId, candidateCase, expectedAsset);
            CaseEvidenceResult evidence = evaluateEvidence(
                    candidateCase,
                    expectedAsset,
                    expectedChunk,
                    resultsByCaseId.get(candidateCase.caseId()),
                    chunksByRuntimeId,
                    knowledgeBaseId
            );
            if (evidence.hit()) {
                goldAssetEvidenceHitCount++;
            } else {
                if (!evidence.goldChunkFound()) {
                    goldChunkMissCount++;
                }
                if (evidence.assetIdMismatch()) {
                    assetIdMismatchCount++;
                }
                if (evidence.assetTypeMismatch()) {
                    assetTypeMismatchCount++;
                }
                if (evidence.assetLocatorMismatch()) {
                    assetLocatorMismatchCount++;
                }
                if (evidence.goldEvidenceMismatch()) {
                    goldEvidenceMismatchCount++;
                }
            }
        }

        if (routeMismatchCount > 0) {
            reasons.add("multimodal_route_mismatch");
        }
        if (permissionViolationCount > 0) {
            reasons.add("multimodal_permission_violation");
        }
        if (unknownRuntimeChunkCount > 0) {
            reasons.add("unknown_runtime_chunk");
        }
        if (goldChunkMissCount > 0) {
            reasons.add("gold_chunk_not_retrieved");
        }
        if (assetIdMismatchCount > 0) {
            reasons.add("gold_asset_id_mismatch");
        }
        if (assetTypeMismatchCount > 0) {
            reasons.add("gold_asset_type_mismatch");
        }
        if (assetLocatorMismatchCount > 0) {
            reasons.add("gold_asset_locator_mismatch");
        }
        if (goldEvidenceMismatchCount > 0) {
            reasons.add("gold_evidence_mismatch");
        }
        if (goldAssetEvidenceHitCount != answerableCaseCount) {
            reasons.add("gold_asset_evidence_not_retrieved");
        }

        Gate gate = new Gate(
                reasons.isEmpty() ? "eligible_for_multimodal_retrieval_l2" : "inconclusive",
                List.copyOf(reasons)
        );
        return new Report(
                profile.datasetId(),
                profile.cases().size(),
                answerableCaseCount,
                abstentionCaseCount,
                goldAssetEvidenceHitCount,
                routeMismatchCount,
                permissionViolationCount,
                unknownRuntimeChunkCount,
                goldChunkMissCount,
                assetIdMismatchCount,
                assetTypeMismatchCount,
                assetLocatorMismatchCount,
                goldEvidenceMismatchCount,
                gate,
                true,
                false,
                false,
                false,
                false
        );
    }

    private void validateCandidate(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        if (profile == null || runtimePlan == null || !StringUtils.hasText(profile.datasetId())
                || !"candidate".equals(profile.status()) || profile.releaseEligible()
                || profile.cases() == null || profile.cases().isEmpty()
                || profile.assetImportPlan() == null || !runtimePlan.candidateOnly()
                || !runtimePlan.assetImportPlan().candidateOnly()
                || !profile.assetImportPlan().equals(runtimePlan.assetImportPlan())) {
            throw new IllegalArgumentException("multimodal evaluator 仅接受 candidate-only runtime plan");
        }
        Map<String, Object> metadata = profile.assetImportPlan().knowledgeBaseMetadata();
        if (!StringUtils.hasText(profile.assetImportPlan().knowledgeBaseId())
                || !Boolean.TRUE.equals(metadata.get("candidateOnly"))
                || !profile.datasetId().equals(metadata.get("datasetId"))
                || !profile.manifestSha256().equals(metadata.get("manifestSha256"))) {
            throw new IllegalArgumentException("multimodal evaluator candidate 元数据不匹配");
        }
    }

    private Map<String, G2CurrentMultimodalCandidateFixtureLoader.CandidateCase> indexCases(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile
    ) {
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.CandidateCase> casesById = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase : profile.cases()) {
            if (candidateCase == null || !StringUtils.hasText(candidateCase.caseId())
                    || casesById.put(candidateCase.caseId(), candidateCase) != null) {
                throw new IllegalArgumentException("multimodal evaluator caseId 无效或重复");
            }
        }
        return casesById;
    }

    private void validateCaseCoverage(
            Set<String> expectedCaseIds,
            Map<String, String> routesByCaseId,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        if (routesByCaseId == null || resultsByCaseId == null
                || !expectedCaseIds.equals(routesByCaseId.keySet())
                || !expectedCaseIds.equals(resultsByCaseId.keySet())) {
            throw new IllegalStateException("multimodal runtime evaluation 必须精确覆盖全部 candidate case");
        }
        for (String caseId : expectedCaseIds) {
            List<RagRetrievalResult> results = resultsByCaseId.get(caseId);
            if (results == null || results.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("multimodal runtime retrieval 结果为空或包含 null: " + caseId);
            }
        }
    }

    private Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> indexRuntimeChunks(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> expectedByLogicalId = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow row
                : profile.assetImportPlan().chunkRows()) {
            expectedByLogicalId.put(row.logicalChunkId(), row);
        }
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunksByRuntimeId = new HashMap<>();
        Set<String> logicalIds = new HashSet<>();
        if (runtimePlan.chunks() == null || runtimePlan.chunks().size() != expectedByLogicalId.size()) {
            throw new IllegalArgumentException("multimodal candidate runtime chunk coverage 不匹配");
        }
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk : runtimePlan.chunks()) {
            if (runtimeChunk == null || runtimeChunk.chunk() == null
                    || !expectedByLogicalId.containsKey(runtimeChunk.chunk().logicalChunkId())
                    || !runtimeChunk.chunk().equals(expectedByLogicalId.get(runtimeChunk.chunk().logicalChunkId()))
                    || !StringUtils.hasText(runtimeChunk.chunk().chunkId())
                    || !logicalIds.add(runtimeChunk.chunk().logicalChunkId())
                    || chunksByRuntimeId.put(runtimeChunk.chunk().chunkId(), runtimeChunk.chunk()) != null) {
                throw new IllegalArgumentException("multimodal candidate runtime chunk 映射无效或重复");
            }
        }
        return chunksByRuntimeId;
    }

    private G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset expectedAsset(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase
    ) {
        if (candidateCase.goldAsset() == null) {
            throw new IllegalStateException("multimodal answerable case 缺少 gold asset: "
                    + candidateCase.caseId());
        }
        List<G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset> matches = profile.assets().stream()
                .filter(asset -> asset.sourceDocumentId().equals(candidateCase.sourceDocumentId()))
                .filter(asset -> asset.assetType().equals(candidateCase.goldAsset().assetType()))
                .filter(asset -> asset.assetKey().equals(candidateCase.goldAsset().assetKey()))
                .filter(asset -> asset.logicalChunkId().equals(candidateCase.goldChunkLogicalId()))
                .filter(asset -> asset.locator().equals(candidateCase.goldAsset().locator()))
                .toList();
        if (matches.size() != 1
                || !matches.get(0).content().contains(candidateCase.goldEvidence())) {
            throw new IllegalStateException("multimodal candidate gold asset 与 chunk/evidence 不唯一匹配: "
                    + candidateCase.caseId());
        }
        return matches.get(0);
    }

    private G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow findExpectedChunk(
            Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunksByRuntimeId,
            G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase,
            G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset expectedAsset
    ) {
        G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow expectedChunk =
                chunksByRuntimeId.get(expectedAsset.runtimeChunkUuid());
        if (expectedChunk == null || !expectedChunk.logicalChunkId().equals(candidateCase.goldChunkLogicalId())) {
            throw new IllegalStateException("multimodal candidate gold chunk 未导入 runtime plan: "
                    + candidateCase.caseId());
        }
        return expectedChunk;
    }

    private CaseEvidenceResult evaluateEvidence(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateCase candidateCase,
            G2CurrentMultimodalCandidateFixtureLoader.CandidateAsset expectedAsset,
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow expectedChunk,
            List<RagRetrievalResult> results,
            Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunksByRuntimeId,
            String knowledgeBaseId
    ) {
        boolean goldChunkFound = false;
        boolean assetIdMismatch = false;
        boolean assetTypeMismatch = false;
        boolean assetLocatorMismatch = false;
        boolean goldEvidenceMismatch = false;
        for (RagRetrievalResult result : results) {
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow mappedChunk =
                    chunksByRuntimeId.get(result.getChunkId());
            if (!knowledgeBaseId.equals(result.getKbId()) || mappedChunk == null
                    || !expectedChunk.logicalChunkId().equals(mappedChunk.logicalChunkId())) {
                continue;
            }
            goldChunkFound = true;
            JsonNode asset = assetMetadata(result.getMetadata());
            boolean idMatches = expectedAsset.runtimeAssetUuid().equals(asset.path("id").asText(null));
            boolean typeMatches = expectedAsset.assetType().equals(asset.path("type").asText(null));
            boolean locatorMatches = OBJECT_MAPPER.valueToTree(expectedAsset.locator())
                    .equals(asset.path("locator"));
            boolean evidenceMatches = result.getContent() != null
                    && result.getContent().contains(candidateCase.goldEvidence());
            if (idMatches && typeMatches && locatorMatches && evidenceMatches) {
                return new CaseEvidenceResult(true, true, false, false, false, false);
            }
            assetIdMismatch |= !idMatches;
            assetTypeMismatch |= !typeMatches;
            assetLocatorMismatch |= !locatorMatches;
            goldEvidenceMismatch |= idMatches && typeMatches && locatorMatches && !evidenceMatches;
        }
        return new CaseEvidenceResult(
                false,
                goldChunkFound,
                assetIdMismatch,
                assetTypeMismatch,
                assetLocatorMismatch,
                goldEvidenceMismatch
        );
    }

    private JsonNode assetMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(metadata);
            JsonNode asset = root == null ? null : root.path("asset");
            return asset != null && asset.isObject() ? asset : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private record CaseEvidenceResult(
            boolean hit,
            boolean goldChunkFound,
            boolean assetIdMismatch,
            boolean assetTypeMismatch,
            boolean assetLocatorMismatch,
            boolean goldEvidenceMismatch
    ) {
    }

    record Gate(String verdict, List<String> reasons) {
        Gate {
            reasons = List.copyOf(reasons);
        }
    }

    record Report(
            String datasetId,
            int caseCount,
            int answerableCaseCount,
            int abstentionCaseCount,
            int goldAssetEvidenceHitCount,
            int routeMismatchCount,
            int permissionViolationCount,
            int unknownRuntimeChunkCount,
            int goldChunkMissCount,
            int assetIdMismatchCount,
            int assetTypeMismatchCount,
            int assetLocatorMismatchCount,
            int goldEvidenceMismatchCount,
            Gate gate,
            boolean candidateOnly,
            boolean releaseEligible,
            boolean answerCitationAccuracyMeasured,
            boolean answerQualityMeasured,
            boolean refusalMeasured
    ) {
    }
}
