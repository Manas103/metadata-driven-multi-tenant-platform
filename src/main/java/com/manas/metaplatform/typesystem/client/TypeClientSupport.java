package com.manas.metaplatform.typesystem.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one hand-written HTTP + JSON helper every generated Java client calls into. Not
 * generated itself, on purpose: exactly as a real codegen tool (OpenAPI Generator, gRPC)
 * emits many small per-type files against one shared runtime support library, the 40
 * generated {@code <Type>Client} classes under {@code generated-clients/java/} are thin and
 * call this class for the actual HTTP work, so the generator only has to emit the parts that
 * differ per type (the model shape and the URL path segment).
 */
public final class TypeClientSupport {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public TypeClientSupport(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public <T> T postForObject(String path, Object body, Class<T> responseType) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new TypeClientException("POST " + path + " returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return mapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public <T> T getForObject(String path, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new TypeClientException("GET " + path + " returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return mapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public <T> T[] getForArray(String path, Class<T[]> arrayType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new TypeClientException("GET " + path + " returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return mapper.readValue(response.body(), arrayType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String buildFilterQueryString(Map<String, Object> equalityFilters) {
        if (equalityFilters == null || equalityFilters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> entry : equalityFilters.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public <T> Map<String, Object> toMap(T typed) {
        return mapper.convertValue(typed, Map.class);
    }
}
