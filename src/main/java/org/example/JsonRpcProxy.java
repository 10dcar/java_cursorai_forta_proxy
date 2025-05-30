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
import java.util.concurrent.atomic.AtomicInteger;

//curl -X POST 127.0.0.1:8080  -d "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_blockNumber\",\"params\":[]}"
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

    // Cache configuration: 1000 entries, 5 minutes expiration
    private static final Cache<String, String> responseCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(1))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new JsonRpcHandler());

        executorService = java.util.concurrent.Executors.newFixedThreadPool(10);
        server.setExecutor(executorService); // Set executor BEFORE start

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

                // Read the request body
                String requestBody = new String(exchange.getRequestBody().readAllBytes());
                
                // Parse the JSON-RPC request to get method and params for cache key
                JsonNode jsonRequest = objectMapper.readTree(requestBody);
                //System.out.println("Received request: " + jsonRequest);
                String cacheKey = generateCacheKey(jsonRequest);
                
                // Try to get from cache
                String cachedResponse = responseCache.getIfPresent(cacheKey);
                if (cachedResponse != null) {
                    sendResponse(exchange, 200, cachedResponse);
                    return;
                }

                // Forward request to upstream server with load balancing
                String response = forwardRequest(requestBody);
                
                // Cache the response
                responseCache.put(cacheKey, response);
                
                // Send response back to client
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\": {\"code\": -32603, \"message\": \"Internal error\"}}");
            }
        }

        private String forwardRequest(String requestBody) throws IOException {
            // Simple round-robin load balancing
            int serverIndex = requestCounter.getAndIncrement() % UPSTREAM_URLS.size();
            String targetUrl = UPSTREAM_URLS.get(serverIndex);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost httpPost = new HttpPost(targetUrl);
                httpPost.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
                
                return client.execute(httpPost, response -> 
                    EntityUtils.toString(response.getEntity()));
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }

        private String generateCacheKey(JsonNode jsonRequest) {
            // Create cache key from method and params
            String method = jsonRequest.get("method").asText();
            JsonNode params = jsonRequest.path("params");
            return method + "-" + params.toString();
        }
    }
} 