package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public final class RagService {
    private final RagIndex index;
    private final SummaryService models;
    private final RagAnswerValidator validator = new RagAnswerValidator();
    private final ObjectMapper json = new ObjectMapper();

    public RagService(RagIndex index, SummaryService models) { this.index = index; this.models = models; }

    public Result ask(long owner, String question, boolean consent) {
        if (!consent) throw new AiFailure(400, "RAG_CONSENT_REQUIRED");
        RagIndex.Retrieval retrieval = index.search(owner, question);
        if (retrieval.hits().isEmpty()) return new Result("REFUSED", "NO_EVIDENCE", "", List.of(), null, null, retrieval.revision());
        String prompt;
        try {
            prompt = json.writeValueAsString(Map.of("question", question, "evidence", retrieval.hits().stream()
                    .map(h -> Map.of("id", h.id(), "text", h.text())).toList()));
        } catch (Exception failure) { throw new AiFailure(500, "RAG_SERIALIZATION_FAILED"); }
        SummaryService.Generated generated = models.answer(owner, prompt);
        RagAnswerValidator.Answer answer = validator.validate(generated.response(), retrieval.hits());
        index.requireCurrent(owner, retrieval.revision());
        return new Result(answer.status(), answer.status().equals("REFUSED") ? "MODEL_REFUSED" : null,
                answer.answer(), answer.citations(), generated.requestId(), generated.usage(), retrieval.revision());
    }

    public record Result(String status, String reason, String answer, List<RagAnswerValidator.Citation> citations,
                         String requestId, AiCost usage, long corpusRevision) { }
}
