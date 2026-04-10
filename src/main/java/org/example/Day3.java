package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class Day3 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

    public static void main(String[] args) throws Exception {
        String architectureText = """
                апфапв
                """;

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

        GenerateRequest request = new GenerateRequest(
                "llama3.2",
                prompt,
                false,
                MAPPER.readTree(RESPONSE_SCHEMA)
        );

        String rawHttpBody = callOllama(request);

        GenerateResponse apiResponse = MAPPER.readValue(rawHttpBody, GenerateResponse.class);
        RiskReport report = MAPPER.readValue(apiResponse.response(), RiskReport.class);

        validate(report);

        System.out.println("=== VALID REPORT ===");
        for (int i = 0; i < report.risks().size(); i++) {
            Risk risk = report.risks().get(i);
            System.out.println((i + 1) + ". " + risk.title());
            System.out.println("   severity: " + risk.severity());
            System.out.println("   reason:   " + risk.reason());
            System.out.println("   action:   " + risk.action());
        }
    }

    private static String callOllama(GenerateRequest request) throws IOException, InterruptedException {
        String requestJson = MAPPER.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/generate"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unexpected HTTP status: " + response.statusCode()
                    + ", body=" + response.body());
        }

        return response.body();
    }

    private static void validate(RiskReport report) {
        Set<ConstraintViolation<RiskReport>> violations = VALIDATOR.validate(report);

        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + System.lineSeparator() + b)
                    .orElse("Unknown validation error");

            throw new IllegalStateException("Validation failed:\n" + message);
        }

        if (report.risks().size() != 3) {
            throw new IllegalStateException("Validation failed: expected exactly 3 risks");
        }
    }

    public record GenerateRequest(
            String model,
            String prompt,
            boolean stream,
            Object format
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateResponse(
            String response
    ) {
    }

    public record RiskReport(
            @NotEmpty(message = "risks must not be empty")
            List<Risk> risks
    ) {
    }

    public record Risk(
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
}