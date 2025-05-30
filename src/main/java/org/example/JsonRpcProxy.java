package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class JsonRpcProxy {
    private static final int PORT = 8080;
    private static final List<String> UPSTREAM_URLS = Arrays.asList(
            "https://optimism-mainnet.blastapi.io/ef55e341-ee73-4566-9c05-b4a0d09b6045",
            "https://optimism-mainnet.blastapi.io/b699a618-d878-4223-ab41-27fef4856223"
    );

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final CloseableHttpClient httpClient = HttpClients.createDefault();
    private static java.util.concurrent.ExecutorService executorService;

    // Cache configuration: 1000 entries, 1 second expiration
    private static final Cache<String, String> responseCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(1))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new JsonRpcHandler());

        executorService = java.util.concurrent.Executors.newFixedThreadPool(10);
        server.setExecutor(executorService);

        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook triggered. Cleaning up...");

            server.stop(0);
            System.out.println("HTTP server stopped.");

            responseCache.invalidateAll();
            System.out.println("Cache cleared.");

            try {
                httpClient.close();
                System.out.println("HTTP client closed.");
            } catch (IOException e) {
                System.err.println("Error closing HTTP client: " + e.getMessage());
                e.printStackTrace();
            }

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.out.println("Executor did not terminate in time. Forcing shutdown...");
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        System.out.println("Executor did not terminate after forced shutdown.");
                    }
                }
            } catch (InterruptedException ie) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            System.out.println("Executor service shut down.");

            System.out.println("Cleanup completed successfully.");
        }));

        System.out.println("Server started on port " + PORT);
    }

    static class JsonRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }

                String requestBody = new String(exchange.getRequestBody().readAllBytes());
                JsonNode jsonRequest = objectMapper.readTree(requestBody);
                String cacheKey = generateCacheKey(jsonRequest);

                String cachedResponse = responseCache.getIfPresent(cacheKey);
                if (cachedResponse != null) {
                    sendResponse(exchange, 200, cachedResponse);
                    return;
                }

                String response = forwardRequest(requestBody);
                responseCache.put(cacheKey, response);
                sendResponse(exchange, 200, response);

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\": {\"code\": -32603, \"message\": \"Internal error\"}}");
            }
        }

        private String forwardRequest(String requestBody) throws IOException {
            int serverIndex = requestCounter.getAndIncrement() % UPSTREAM_URLS.size();
            String targetUrl = UPSTREAM_URLS.get(serverIndex);

            HttpPost httpPost = new HttpPost(targetUrl);
            httpPost.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            return httpClient.execute(httpPost, response ->
                    EntityUtils.toString(response.getEntity()));
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }

        private String generateCacheKey(JsonNode jsonRequest) {
            String method = jsonRequest.get("method").asText();
            JsonNode params = jsonRequest.path("params");
            return method + "-" + params.toString();
        }
    }
}