package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

final class RagRetrievalSignals {
    static RagRetrievalResult copyResult(RagRetrievalResult source) {
        RagRetrievalResult copy = new RagRetrievalResult();
        copy.setChunkId(source.getChunkId());
        copy.setKbId(source.getKbId());
        copy.setDocId(source.getDocId());
        copy.setContent(source.getContent());
        copy.setMetadata(source.getMetadata());
        copy.setDistance(source.getDistance());
        copy.setRank(source.getRank());
        copy.setRrfScore(source.getRrfScore());
        copy.setVectorRank(source.getVectorRank());
        copy.setVectorDistance(source.getVectorDistance());
        copy.setTitleBm25Rank(source.getTitleBm25Rank());
        copy.setTitleBm25Score(source.getTitleBm25Score());
        copy.setContentBm25Rank(source.getContentBm25Rank());
        copy.setContentBm25Score(source.getContentBm25Score());
        copy.setRerankScore(source.getRerankScore());
        copy.setRetrievalProvenance(source.getRetrievalProvenance() == null
                ? null
                : List.copyOf(source.getRetrievalProvenance()));
        return copy;
    }

    static void mergeSignals(RagRetrievalResult merged, RagRetrievalResult incoming) {
        if (incoming.getRetrievalProvenance() != null) {
            LinkedHashSet<String> provenance = new LinkedHashSet<>();
            if (merged.getRetrievalProvenance() != null) {
                provenance.addAll(merged.getRetrievalProvenance());
            }
            provenance.addAll(incoming.getRetrievalProvenance());
            merged.setRetrievalProvenance(List.copyOf(provenance));
        }
        if (!StringUtils.hasText(merged.getContent()) && StringUtils.hasText(incoming.getContent())) {
            merged.setContent(incoming.getContent());
        }
        if (!StringUtils.hasText(merged.getMetadata()) && StringUtils.hasText(incoming.getMetadata())) {
            merged.setMetadata(incoming.getMetadata());
        }
        if (incoming.getVectorRank() != null
                && (merged.getVectorRank() == null || incoming.getVectorRank() < merged.getVectorRank())) {
            merged.setVectorRank(incoming.getVectorRank());
            merged.setVectorDistance(incoming.getVectorDistance());
            merged.setDistance(incoming.getVectorDistance());
        }
        if (incoming.getTitleBm25Rank() != null
                && (merged.getTitleBm25Rank() == null || incoming.getTitleBm25Rank() < merged.getTitleBm25Rank())) {
            merged.setTitleBm25Rank(incoming.getTitleBm25Rank());
            merged.setTitleBm25Score(incoming.getTitleBm25Score());
        }
        if (incoming.getContentBm25Rank() != null
                && (merged.getContentBm25Rank() == null || incoming.getContentBm25Rank() < merged.getContentBm25Rank())) {
            merged.setContentBm25Rank(incoming.getContentBm25Rank());
            merged.setContentBm25Score(incoming.getContentBm25Score());
        }
        if (isBetterPrimaryDistance(primaryDistance(incoming), primaryDistance(merged))) {
            merged.setDistance(primaryDistance(incoming));
        }
    }

    static Double primaryDistance(RagRetrievalResult result) {
        if (result.getVectorDistance() != null) {
            return result.getVectorDistance();
        }
        return result.getDistance();
    }

    static boolean isBetterPrimaryDistance(Double candidate, Double existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        return candidate < existing;
    }

    static int safeRank(Integer rank) {
        return rank == null || rank <= 0 ? Integer.MAX_VALUE : rank;
    }

}
