package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class Day4 {

    public static void main(String[] args) throws Exception {
        OllamaClient client = new OllamaClient(
                "http://localhost:11434/api/generate",
                "llama3.2"
        );

        RiskAnalyzerService service = new RiskAnalyzerService(client);

        String architectureText = """
                Сервис заказов синхронно вызывает сервис оплат и сервис уведомлений в рамках одного пользовательского запроса.
                При недоступности любого из сервисов запрос пользователя завершается ошибкой.
                Повторные запросы клиента могут повторно создавать операции оплаты.
                Логи корреляции между сервисами отсутствуют.
                """;

        RiskReport report = service.analyze(architectureText);

        System.out.println("=== FINAL RESULT ===");
        for (int i = 0; i < report.risks().size(); i++) {
            Risk risk = report.risks().get(i);
            System.out.println((i + 1) + ". " + risk.title());
            System.out.println("   severity: " + risk.severity());
            System.out.println("   reason:   " + risk.reason());
            System.out.println("   action:   " + risk.action());
        }
    }

    static final class RiskAnalyzerService {
        private static final Logger LOG = Logger.getLogger(RiskAnalyzerService.class.getName());
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static final Validator VALIDATOR = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();

        private static final String RESPONSE_SCHEMA = """
                {
                  "type": "object",
                  "properties": {
                    "risks": {
                      "type": "array",
                      "minItems": 3,
                      "maxItems": 3,
                      "items": {
                        "type": "object",
                        "properties": {
                          "title": { "type": "string" },
                          "severity": {
                            "type": "string",
                            "enum": ["low", "medium", "high"]
                          },
                          "reason": { "type": "string" },
                          "action": { "type": "string" }
                        },
                        "required": ["title", "severity", "reason", "action"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["risks"],
                  "additionalProperties": false
                }
                """;

        private final OllamaClient client;

        RiskAnalyzerService(OllamaClient client) {
            this.client = client;
        }

        RiskReport  analyze(String architectureText) throws Exception {
            String requestId = UUID.randomUUID().toString();
            long startedAt = System.nanoTime();

            String prompt = """
                    Ты помощник Java backend-разработчика.

                    Задача:
                    Проанализируй архитектурный фрагмент и верни ровно 3 риска интеграции.

                    Требования:
                    - не придумывай факты, которых нет в тексте
                    - severity должна быть одной из: low, medium, high
                    - action должна быть конкретной и практической
                    - верни только JSON

                    JSON schema:
                    %s

                    Текст:
                    %s
                    """.formatted(RESPONSE_SCHEMA, architectureText);

            try {
                String rawHttpBody = client.generate(
                        requestId,
                        prompt,
                        MAPPER.readTree(RESPONSE_SCHEMA)
                );

                GenerateResponse apiResponse = MAPPER.readValue(rawHttpBody, GenerateResponse.class);
                RiskReport report = MAPPER.readValue(apiResponse.response(), RiskReport.class);

                validateReport(report);

                long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                LOG.info(() -> "[requestId=%s] analyze success, latencyMs=%d"
                        .formatted(requestId, latencyMs));

                return report;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new InvalidModelOutputException("Model returned invalid JSON", e);
            }
        }

        private void validateReport(RiskReport report) {
            Set<ConstraintViolation<RiskReport>> violations = VALIDATOR.validate(report);

            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .sorted()
                        .reduce((a, b) -> a + System.lineSeparator() + b)
                        .orElse("Unknown validation error");

                throw new InvalidModelOutputException("Validation failed:\n" + message);
            }

            if (report.risks().size() != 3) {
                throw new InvalidModelOutputException("Expected exactly 3 risks");
            }
        }
    }

    static final class OllamaClient {
        private static final Logger LOG = Logger.getLogger(OllamaClient.class.getName());
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final HttpClient httpClient;
        private final String endpoint;
        private final String model;
        private final Retry retry;

        OllamaClient(String endpoint, String model) {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            this.endpoint = endpoint;
            this.model = model;
            this.retry = createRetry();
        }

        String generate(String requestId, String prompt, JsonNode schemaNode) {
            Supplier<String> supplier = Retry.decorateSupplier(
                    retry,
                    () -> doGenerate(requestId, prompt, schemaNode)
            );
            return supplier.get();
        }

        private Retry createRetry() {
            RetryConfig config = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(400))
                    .retryOnException(throwable ->
                            throwable instanceof TransportException ||
                                    throwable instanceof RetryableProviderException)
                    .ignoreExceptions(
                            NonRetryableProviderException.class,
                            InvalidModelOutputException.class
                    )
                    .build();

            Retry retry = Retry.of("ollamaGenerate", config);

            retry.getEventPublisher().onRetry(event ->
                    LOG.warning(() -> "[retryAttempt=%d] reason=%s"
                            .formatted(
                                    event.getNumberOfRetryAttempts(),
                                    event.getLastThrowable() == null
                                            ? "unknown"
                                            : event.getLastThrowable().getClass().getSimpleName()
                            ))
            );

            return retry;
        }

        private String doGenerate(String requestId, String prompt, JsonNode schemaNode) {
            long startedAt = System.nanoTime();

            try {
                GenerateRequest requestBody = new GenerateRequest(
                        model,
                        prompt,
                        false,
                        schemaNode
                );

                String requestJson = MAPPER.writeValueAsString(requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                int status = response.statusCode();

                LOG.info(() -> "[requestId=%s] model=%s status=%d latencyMs=%d"
                        .formatted(requestId, model, status, latencyMs));

                if (status == 200) {
                    return response.body();
                }

                if (status == 400 || status == 404) {
                    throw new NonRetryableProviderException(
                            "Non-retryable provider error. status=%d body=%s"
                                    .formatted(status, response.body())
                    );
                }

                if (status == 429 || status == 500 || status == 502) {
                    throw new RetryableProviderException(
                            "Retryable provider error. status=%d body=%s"
                                    .formatted(status, response.body())
                    );
                }

                throw new RetryableProviderException(
                        "Unexpected provider error. status=%d body=%s"
                                .formatted(status, response.body())
                );

            } catch (ConnectException e) {
                throw new TransportException("Cannot connect to Ollama", e);
            } catch (HttpTimeoutException e) {
                throw new TransportException("Timeout during Ollama call", e);
            } catch (IOException e) {
                throw new TransportException("I/O error during Ollama call", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NonRetryableProviderException("Thread interrupted", e);
            }
        }
    }

    record GenerateRequest(
            String model,
            String prompt,
            boolean stream,
            JsonNode format
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResponse(
            String response
    ) {
    }

    record RiskReport(
            @NotEmpty(message = "risks must not be empty")
            List<@Valid Risk> risks
    ) {
    }

    record Risk(
            @NotBlank(message = "title must not be blank")
            String title,

            @NotBlank(message = "severity must not be blank")
            @Pattern(regexp = "low|medium|high", message = "severity must be low, medium or high")
            String severity,

            @NotBlank(message = "reason must not be blank")
            String reason,

            @NotBlank(message = "action must not be blank")
            String action
    ) {
    }

    static class TransportException extends RuntimeException {
        TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class RetryableProviderException extends RuntimeException {
        RetryableProviderException(String message) {
            super(message);
        }
    }

    static class NonRetryableProviderException extends RuntimeException {
        NonRetryableProviderException(String message) {
            super(message);
        }

        NonRetryableProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class InvalidModelOutputException extends RuntimeException {
        InvalidModelOutputException(String message) {
            super(message);
        }

        InvalidModelOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}