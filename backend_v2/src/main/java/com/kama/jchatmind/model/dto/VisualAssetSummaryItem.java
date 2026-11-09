package com.kama.jchatmind.model.dto;

import lombok.Data;

@Data
public class VisualAssetSummaryItem {
    private String visualKind;

    private String locator;

    private String caption;

    private String entityTypes;
}