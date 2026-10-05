package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class CloudDriveMcpServer {
    private CloudDriveMcpServer() { }
    public static void main(String[] args) {
        OutputStream protocol = System.out;
        System.setOut(System.err);
        try {
            McpRagBackend backend = new McpRagBackend(System.getenv("CLOUDDRIVE_MCP_AI_URL"), System.getenv("CLOUDDRIVE_MCP_TOKEN"));
            CountDownLatch eof = new CountDownLatch(1);
            InputStream input = new FilterInputStream(System.in) {
                @Override public int read() throws IOException { int n = super.read(); if (n < 0) eof.countDown(); return n; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int n = super.read(bytes, offset, length); if (n < 0) eof.countDown(); return n;
                }
            };
            var mapper = new JacksonMcpJsonMapper(new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16000).build()).build()));
            var transport = new StdioServerTransportProvider(mapper, input, protocol, 65536);
            var builder = McpServer.sync(transport).jsonMapper(mapper).serverInfo("clouddrive-readonly-rag", "day32")
                    .requestTimeout(Duration.ofSeconds(10)).validateToolInputs(true)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
            for (AgentToolCatalog.Definition definition : AgentToolCatalog.definitions()) {
                var tool = McpSchema.Tool.builder(definition.name(), definition.inputSchema()).description(definition.description())
                        .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).openWorldHint(false).build()).build();
                builder.toolCall(tool, (exchange, request) -> {
                    try {
                        String result = backend.execute(definition.name(), AgentToolCatalog.write(
                                request.arguments() == null ? Map.of() : request.arguments()));
                        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(result))).isError(false).build();
                    } catch (AiFailure failure) {
                        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(failure.getMessage()))).isError(true).build();
                    }
                });
            }
            var server = builder.build();
            try { eof.await(); }
            finally { server.close(); }
        } catch (Exception failure) {
            System.err.println("MCP_START_OR_TRANSPORT_FAILED");
            System.exit(1);
        }
    }
}
