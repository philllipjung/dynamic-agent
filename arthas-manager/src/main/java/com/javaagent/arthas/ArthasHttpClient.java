package com.javaagent.arthas;

import com.javaagent.commons.AgentConstants;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP Client for Arthas API
 * Arthas provides HTTP API on port 8561 by default
 */
public class ArthasHttpClient {

    private static final int DEFAULT_ARTHAS_HTTP_PORT = 8561;
    private String host;
    private int port;

    public ArthasHttpClient() {
        this(AgentConstants.DEFAULT_ARTHAS_HOST, DEFAULT_ARTHAS_HTTP_PORT);
    }

    public ArthasHttpClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Execute Arthas command via HTTP API
     */
    public String executeCommand(String command) throws IOException {
        String urlStr = String.format("http://%s:%d/api", host, port);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Build request body
            String jsonBody = String.format("{\"action\":\"exec\",\"command\":\"%s\"}",
                    command.replace("\"", "\\\""));

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return readResponse(conn.getInputStream());
            } else {
                return "ERROR: HTTP " + responseCode + " - " + readResponse(conn.getErrorStream());
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Get Arthas session status
     */
    public String getStatus() throws IOException {
        String urlStr = String.format("http://%s:%d/api", host, port);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return readResponse(conn.getInputStream());
            } else {
                return "ERROR: HTTP " + responseCode;
            }
        } finally {
            conn.disconnect();
        }
    }

    private String readResponse(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }
}
