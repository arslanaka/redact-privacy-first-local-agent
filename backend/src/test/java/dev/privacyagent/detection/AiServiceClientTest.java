package dev.privacyagent.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/** Exercises the real HTTP client against a local server: wire format and error mapping. */
class AiServiceClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiServiceClient clientFor(String baseUrl) {
        var props = new PrivacyProperties(new PrivacyProperties.Storage("x"),
                new PrivacyProperties.Upload(DataSize.ofMegabytes(1)), new PrivacyProperties.Retention(1, Duration.ofMinutes(1)),
                new PrivacyProperties.AiService(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(5)),
                new PrivacyProperties.Redaction(PrivacyProperties.RedactionStyle.BLOCK),
                new PrivacyProperties.Cors(List.of()), true);
        return new AiServiceClient(RestClient.builder(), props);
    }

    private String serve(int status, String body, AtomicReference<String> seenUpgrade, AtomicReference<String> seenBody)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void sendsPlainHttp11JsonAndParsesTheResponse() throws IOException {
        var upgrade = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        String url = serve(200, """
                {"model":"laya","isMock":false,"candidatesScored":3,"processingTimeMs":12,
                 "entities":[{"text":"Anna","type":"PERSON","confidence":0.97,"start":6,"end":10,"modelLabels":["given name"]}]}
                """, upgrade, body);
        var res = clientFor(url).detect("Hello Anna");
        assertThat(upgrade.get()).as("must not attempt an h2c upgrade").isNull();
        assertThat(body.get()).isEqualTo("{\"text\":\"Hello Anna\"}");
        assertThat(res.model()).isEqualTo("laya");
        assertThat(res.entities()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("PERSON");
            assertThat(e.modelLabels()).containsExactly("given name");
        });
    }

    @Test
    void serviceUnavailableMapsToModelUnavailable() throws IOException {
        String url = serve(503, "{\"error\":\"MODEL_UNAVAILABLE\"}", new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> clientFor(url).detect("x"))
                .isInstanceOf(PrivacyAgentException.class)
                .satisfies(e -> assertThat(((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE))
                .hasMessage("Local PII model is currently unavailable.");
    }

    @Test
    void unreachableServiceMapsToModelUnavailable() {
        assertThatThrownBy(() -> clientFor("http://127.0.0.1:1").detect("x"))
                .satisfies(e -> assertThat(((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE));
        assertThat(clientFor("http://127.0.0.1:1").status().status()).isEqualTo("unavailable");
    }

    @Test
    void serverErrorMapsToAnalysisFailed() throws IOException {
        String url = serve(500, "{\"error\":\"INFERENCE_FAILED\"}", new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> clientFor(url).detect("x"))
                .satisfies(e -> assertThat(((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.ANALYSIS_FAILED));
    }

    @Test
    void statusReportsLoadingModel() throws IOException {
        String url = serve(503, "{\"status\":\"loading\",\"model\":\"laya\",\"isMock\":false}", new AtomicReference<>(),
                new AtomicReference<>());
        assertThat(clientFor(url).status().status()).isEqualTo("loading");
    }
}
