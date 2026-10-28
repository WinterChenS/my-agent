package com.myagent.agui.connector;

import com.myagent.agui.AguiProperties;
import com.myagent.agui.connector.mail.MailConnectorProvider;
import com.myagent.connectorconfig.ConnectorProbePort;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 连接器的 Spring 装配：每个 Provider 一个 Bean + 一个「测试连接」适配器。
 *
 * <p>加一种连接器时，**这里是第二处也是最后一处**改动（第一处是 {@code ConnectorTypeRegistry}
 * 里的类型描述）：注册 Provider Bean 即可，工具注册与前端表单都会自动跟着走。
 */
@Configuration
public class ConnectorConfiguration {

    @Bean
    public MailConnectorProvider mailConnectorProvider(
            ConnectorService connectorService,
            AguiProperties aguiProperties,
            @Value("${myagent.connector.mail.timeout-connect-ms:10000}") int connectTimeoutMs,
            @Value("${myagent.connector.mail.timeout-read-ms:30000}") int readTimeoutMs,
            @Value("${myagent.connector.mail.max-attachment-mb:20}") int maxAttachmentMb,
            @Value("${myagent.connector.mail.max-attachments-total-mb:50}") int maxAttachmentsTotalMb,
            @Value("${myagent.connector.mail.max-body-chars:40000}") int maxBodyChars,
            @Value("${myagent.connector.inbound.max-batch:20}") int maxBatch) {
        Path workspace = Path.of(aguiProperties.workspaceRoot());
        return new MailConnectorProvider(
                connectorService,
                workspace,
                connectTimeoutMs,
                readTimeoutMs,
                Math.max(maxAttachmentMb, 1) * 1024L * 1024L,
                Math.max(maxAttachmentsTotalMb, 1) * 1024L * 1024L,
                Math.max(maxBodyChars, 1000),
                Math.max(maxBatch, 1));
    }

    /** 让 {@code ConnectorService.test/testDraft} 能探测邮箱（不把 AgentScope 类型带进配置包）。 */
    @Bean
    public ConnectorProbePort mailConnectorProbePort(MailConnectorProvider provider) {
        return new ConnectorProbePort() {
            @Override
            public String type() {
                return provider.type();
            }

            @Override
            public ConnectorTestResult probe(Map<String, String> config) {
                return provider.probe(config);
            }
        };
    }
}
