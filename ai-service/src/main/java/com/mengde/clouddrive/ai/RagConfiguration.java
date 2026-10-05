package com.mengde.clouddrive.ai;

import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfiguration {
    @Bean RagIndex ragIndex(@Value("${clouddrive.rag.enabled:false}") boolean enabled) {
        return new RagIndex(enabled, enabled ? new AllMiniLmL6V2QuantizedEmbeddingModel(Runnable::run) : null);
    }
    @Bean RagService ragService(RagIndex index, SummaryService models) { return new RagService(index, models); }
}
