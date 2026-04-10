    package org.example;

    import java.io.IOException;
    import java.net.URI;
    import java.net.http.HttpClient;
    import java.net.http.HttpRequest;
    import java.net.http.HttpResponse;
    import java.time.Duration;

    //TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
    // click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
    public class Day1 {
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        private Day1() {
        }

        public static void main(String[] args) throws IOException, InterruptedException {
            String requestBody = """
                {
                  "model": "llama3.2",
                  "stream": false,
                  "messages": [
                    {
                      "role": "user",
                      "content": "Сколько запросов в памяти ты держишь"
                    }
                  ]
                }
                """;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/chat"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("HTTP status: " + response.statusCode());
            System.out.println("Response body:");
            System.out.println(response.body());
        }
    }