package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RagRegressionReleaseDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagRegressionReleaseDatasetLoader() {
    }

    static RagRegressionReleaseDataset load(String manifestResource, String candidateResource) throws IOException {
        RagRegressionReleaseManifest manifest;
        try (InputStream inputStream = new ClassPathResource(manifestResource).getInputStream()) {
            manifest = OBJECT_MAPPER.readValue(inputStream, RagRegressionReleaseManifest.class);
        }
        RagRegressionCandidateDataset candidate = RagRegressionCandidateDatasetLoader.load(candidateResource);
        validate(manifest, candidate, sha256(new ClassPathResource(candidateResource).getInputStream()), candidateResource);

        Map<String, RagRegressionCandidateCase> casesById = new HashMap<>();
        for (RagRegressionCandidateCase item : candidate.cases()) {
            casesById.put(item.caseId(), item);
        }
        List<RagRegressionCandidateCase> cases = manifest.includedCaseIds().stream()
                .map(casesById::get)
                .toList();
        return new RagRegressionReleaseDataset(manifest, candidate, cases);
    }

    static void validate(
            RagRegressionReleaseManifest manifest,
            RagRegressionCandidateDataset candidate,
            String actualCandidateSha256
    ) {
        validate(manifest, candidate, actualCandidateSha256, manifest.sourceCandidateResource());
    }

    private static void validate(
            RagRegressionReleaseManifest manifest,
            RagRegressionCandidateDataset candidate,
            String actualCandidateSha256,
            String candidateResource
    ) {
        if (manifest == null || candidate == null
                || !"regression-v1".equals(manifest.datasetId())
                || !"untouched_test".equals(manifest.status())
                || !"not_used_for_tuning".equals(manifest.tuningStatus())
                || !"regression-v1-candidate".equals(manifest.sourceCandidateDatasetId())
                || !"regression-v1-candidate".equals(candidate.datasetId())
                || !"candidate".equals(candidate.status())
                || !StringUtils.hasText(manifest.sourceCandidateResource())
                || !manifest.sourceCandidateResource().equals(candidateResource)
                || actualCandidateSha256 == null
                || !StringUtils.hasText(manifest.sourceCandidateSha256())
                || !actualCandidateSha256.equals(manifest.sourceCandidateSha256())
                || !manifest.sourceCandidateSha256().matches("[0-9a-f]{64}")
                || !StringUtils.hasText(manifest.frozenAt())
                || manifest.includedCaseIds() == null
                || manifest.excludedDevelopmentCaseIds() == null) {
            throw new IllegalStateException("release-v1 冻结 manifest 元数据不符合规范");
        }

        Set<String> includedCaseIds = new HashSet<>(manifest.includedCaseIds());
        Set<String> approvedCaseIds = candidate.cases().stream()
                .filter(item -> "approved".equals(item.reviewStatus()))
                .map(RagRegressionCandidateCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> allCandidateCaseIds = candidate.cases().stream()
                .map(RagRegressionCandidateCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> excludedDevelopmentCaseIds = new HashSet<>(manifest.excludedDevelopmentCaseIds());
        if (includedCaseIds.size() != manifest.includedCaseIds().size()
                || !includedCaseIds.equals(approvedCaseIds)
                || !allCandidateCaseIds.containsAll(includedCaseIds)
                || excludedDevelopmentCaseIds.size() != manifest.excludedDevelopmentCaseIds().size()
                || !java.util.Collections.disjoint(includedCaseIds, excludedDevelopmentCaseIds)) {
            throw new IllegalStateException("release-v1 必须精确冻结全部 approved case，且排除开发集 case");
        }
    }

    private static String sha256(InputStream inputStream) throws IOException {
        try (InputStream source = inputStream) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 不可用", exception);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
