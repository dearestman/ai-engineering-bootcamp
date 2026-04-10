    package org.example;

    import java.io.IOException;
    import java.net.URI;
    import java.net.http.HttpClient;
    import java.net.http.HttpRequest;
    import java.net.http.HttpResponse;
    import java.time.Duration;

    //TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
    // click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
    public class Day2 {
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        public static void main(String[] args) throws IOException, InterruptedException {
            String architectureText = """
                Сервис заказов синхронно вызывает сервис оплат и сервис уведомлений в рамках одного пользовательского запроса.
                При недоступности любого из сервисов запрос пользователя завершается ошибкой.
                Повторные запросы клиента могут повторно создавать операции оплаты.
                Логи корреляции между сервисами отсутствуют.
                """;

            String[] prompts = {
                    """
                    Проанализируй этот текст и скажи риски.
    
                    Текст:
                    %s
                    """.formatted(architectureText),

                    """
                    Ты помощник Java backend-разработчика.
                    Проанализируй текст архитектурного решения.
                    Найди 3 основных риска интеграции.
                    Пиши кратко и по делу.
                    Для каждого риска дай:
                    1. название
                    2. почему это риск
                    3. одно практическое действие
    
                    Ответ — в виде нумерованного списка.
    
                    Текст:
                    %s
                    """.formatted(architectureText),

                    """
                    Ты помощник Java backend-разработчика.
    
                    Задача:
                    Проанализировать текст архитектурного решения и вернуть 3 главных риска интеграции.
    
                    Контекст:
                    Мне нужен результат для технического README, не для общего обсуждения.
    
                    Требования:
                    - выдели ровно 3 риска
                    - не повторяй одну и ту же мысль разными словами
                    - не пиши вводные фразы
                    - не придумывай факты, которых нет в тексте
                    - если данных недостаточно, явно укажи это
    
                    Формат ответа:
                    1. Риск: <краткое название>
                       Почему: <1-2 предложения>
                       Что сделать: <1 практическое действие>
    
                    Текст:
                    %s
                    """.formatted(architectureText)
            };

            for (int i = 0; i < prompts.length; i++) {
                System.out.println("=== PROMPT V" + (i + 1) + " ===");
                String response = callModel("llama3.2", prompts[i]);
                System.out.println(response);
                System.out.println();
            }
        }

        private static String callModel(String model, String prompt) throws IOException, InterruptedException {
            String requestBody = """
                {
                  "model": "%s",
                  "stream": false,
                  "messages": [
                    {
                      "role": "user",
                      "content": %s
                    }
                  ]
                }
                """.formatted(model, toJsonString(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/chat"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        }

        private static String toJsonString(String value) {
            return "\"" + value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n") + "\"";
        }
    }