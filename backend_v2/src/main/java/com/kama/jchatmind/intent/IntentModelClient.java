package com.kama.jchatmind.intent;

@FunctionalInterface
public interface IntentModelClient {

    String classify(String query);

    default String classify(String query, String visualAssetSummary) {
        return classify(query);
    }
}
