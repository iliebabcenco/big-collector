package org.big.bigcollector.service.pipeline;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.OpenAiConfig;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class EmbeddingService {

    private static final EmbeddingModel MODEL = EmbeddingModel.TEXT_EMBEDDING_3_SMALL;

    private final OpenAIClient openAIClient;
    private final OpenAiConfig openAiConfig;

    public EmbeddingService(OpenAIClient openAIClient, OpenAiConfig openAiConfig) {
        this.openAIClient = openAIClient;
        this.openAiConfig = openAiConfig;
    }

    public float[] generateEmbedding(String title, String description) {
        if (!openAiConfig.isConfigured()) {
            log.warn("OpenAI not configured, cannot generate embedding");
            return null;
        }

        String text = title + ". " + description;
        // Truncate to ~8000 chars to stay well within token limits
        if (text.length() > 8000) {
            text = text.substring(0, 8000);
        }

        try {
            EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                    .model(MODEL)
                    .input(text)
                    .build();

            CreateEmbeddingResponse response = openAIClient.embeddings().create(params);

            List<Float> embeddingList = response.data().getFirst().embedding();
            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                embedding[i] = embeddingList.get(i);
            }

            log.debug("Generated embedding with {} dimensions", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage());
            return null;
        }
    }
}
