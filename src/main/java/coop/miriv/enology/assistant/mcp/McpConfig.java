package coop.miriv.enology.assistant.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider depositTools(DepositMcpTools tools) {
        return ToolCallbackProvider.from(ToolCallbacks.from(tools));
    }
}
