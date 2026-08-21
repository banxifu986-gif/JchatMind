package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.model.entity.IngestionTask;

public interface IngestionTaskProcessor {

    void process(IngestionTask task);
}
