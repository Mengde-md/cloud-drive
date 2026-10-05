package com.mengde.clouddrive.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;

public final class AgentToolSession {
    private final RagIndex index;
    private final long owner;
    private RagIndex.Retrieval retrieval = new RagIndex.Retrieval(0, List.of());

    public AgentToolSession(RagIndex index, long owner) {
        if (owner <= 0) throw new AiFailure(400, "RAG_OWNER_INVALID");
        this.index = index;
        this.owner = owner;
    }
    public String execute(ToolExecutionRequest request) {
        AgentToolCatalog.Arguments args = AgentToolCatalog.validate(request.name(), request.arguments());
        if (args.name().equals(AgentToolCatalog.LIST)) return bounded(AgentToolCatalog.write(index.list(owner)));
        retrieval = index.search(owner, args.query());
        return bounded(AgentToolCatalog.write(retrieval));
    }
    private String bounded(String output) {
        if (output.length() > 12000) throw new AiFailure(502, "AGENT_TOOL_OUTPUT_INVALID");
        return output;
    }
    public RagIndex.Retrieval retrieval() { return retrieval; }
    public void requireCurrent() {
        if (retrieval.revision() > 0) index.requireCurrent(owner, retrieval.revision());
    }
}
