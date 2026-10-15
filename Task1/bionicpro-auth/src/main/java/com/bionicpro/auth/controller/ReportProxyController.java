package com.bionicpro.auth.controller;

import com.bionicpro.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportProxyController {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiUrl;

    public ReportProxyController(@Value("${REPORTS_API_URL:http://reports-api:8001}") String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @GetMapping("/reports")
    public ResponseEntity<?> report(HttpServletRequest request,
                                    @RequestParam MultiValueMap<String, String> parameters) {
        if (!Set.of("from", "to").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.size() != 1)) {
            return failure(400, "Допустимы только даты from и to.");
        }
        AuthService.Ticket ticket = (AuthService.Ticket) request.getAttribute(AuthService.Ticket.class.getName());
        StringBuilder target = new StringBuilder(apiUrl).append("/reports?");
        parameters.forEach((key, values) -> target.append(key).append("=")
            .append(URLEncoder.encode(values.getFirst(), StandardCharsets.UTF_8)).append("&"));
        HttpRequest upstream = HttpRequest.newBuilder(URI.create(target.toString()))
            .timeout(Duration.ofSeconds(40))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ticket.session().getAccessToken())
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(upstream, HttpResponse.BodyHandlers.ofByteArray());
            // BFF has already authenticated the session. An upstream rejection is
            // a service error, not evidence that the browser session has ended.
            if (response.statusCode() == 401) {
                return failure(502, "Сервис отчётов отклонил запрос. Повторите позже.");
            }
            // Forward only report metadata, never upstream cookies or authentication headers.
            ResponseEntity.BodyBuilder result = ResponseEntity.status(response.statusCode())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON);
            response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION)
                .ifPresent(value -> result.header(HttpHeaders.CONTENT_DISPOSITION, value));
            return result.body(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure(503, "Сервис отчётов недоступен. Повторите позже.");
        } catch (IOException error) {
            return failure(503, "Сервис отчётов недоступен. Повторите позже.");
        }
    }

    private ResponseEntity<?> failure(int status, String detail) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(Map.of("detail", detail));
    }
}
