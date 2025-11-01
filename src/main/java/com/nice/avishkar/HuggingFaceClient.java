package com.nice.avishkar;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class HuggingFaceClient {

    private static final String API_URL = "https://router.huggingface.co/v1/chat/completions";
    private static final String HF_API_KEY = System.getenv("HF_TOKEN");

    /**
     * Generate a travel summary using HuggingFace API
     * @param routeDescription Description of the route
     * @return AI-generated summary (≤ 60 words)
     */
    public static String generateTravelSummary(String routeDescription) {
        if (HF_API_KEY == null) {
            System.err.println("⚠ HF_TOKEN environment variable not set!");
            return "Summary generation failed: API key not configured";
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(API_URL);
            post.setHeader("Authorization", "Bearer " + HF_API_KEY);
            post.setHeader("Content-Type", "application/json");

            // SIMPLIFIED PROMPT - avoids tool-calling
            String prompt = "Summarize this travel route in 60 words or less:\n" + routeDescription;

            // Construct JSON payload
            String jsonBody = String.format("{\n" +
                "  \"model\": \"meta-llama/Llama-3.1-8B-Instruct:scaleway\",\n" +
                "  \"messages\": [\n" +
                "    {\n" +
                "      \"role\": \"user\",\n" +
                "      \"content\": \"%s\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"max_tokens\": 100,\n" +
                "  \"temperature\": 0.5\n" +
                "}", escapeJson(prompt));

            post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                
                // Parse response
                JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                
                if (jsonResponse.has("choices") && jsonResponse.getAsJsonArray("choices").size() > 0) {
                    String summary = jsonResponse
                        .getAsJsonArray("choices")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content")
                        .getAsString()
                        .trim();
                    
                    return truncateTo60Words(summary);
                } else {
                    return "Unable to generate summary";
                }
            }
        } catch (Exception e) {
            System.err.println("Error calling HuggingFace API: " + e.getMessage());
            return "Summary generation failed";
        }
    }

    /**
     * Truncate summary to 60 words maximum
     */
    private static String truncateTo60Words(String text) {
        String[] words = text.split("\\s+");
        if (words.length <= 60) {
            return text;
        }
        
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            truncated.append(words[i]).append(" ");
        }
        return truncated.toString().trim() + "...";
    }

    /**
     * Escape special characters for JSON
     */
    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
