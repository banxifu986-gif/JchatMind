package com.kama.jchatmind.intent;

@FunctionalInterface
public interface IntentModelClient {

    String classify(String query);
}
