package com.bionicpro.reports.repository;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReportRepository {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String url;
    private final String user;
    private final String password;

    public ReportRepository(@Value("${CLICKHOUSE_URL:http://clickhouse:8123}") String url,
                            @Value("${CLICKHOUSE_USER:reports_reader}") String user,
                            @Value("${CLICKHOUSE_PASSWORD}") String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Map<LocalDate, String> periods() {
        JsonNode rows = query("""
            SELECT report_date, argMax(toString(batch_id), version) AS batch_id
            FROM reports.completed_periods
            GROUP BY report_date ORDER BY report_date DESC LIMIT 3660
            FORMAT JSON
            """, Map.of());
        Map<LocalDate, String> periods = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            periods.put(LocalDate.parse(row.get("report_date").asText()), row.get("batch_id").asText());
        }
        return periods;
    }

    public JsonNode report(String subject, LocalDate from, LocalDate to, Map<LocalDate, String> periods) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("subject", subject);
        parameters.put("from", from.toString());
        parameters.put("to", to.toString());
        List<String> batches = new ArrayList<>();
        int index = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String key = "batch" + index++;
            parameters.put(key, periods.get(day));
            batches.add("{" + key + ":UUID}");
        }
        return query("""
            SELECT report_date, client_id, display_name, prosthesis_id, model,
                   samples, successful_movements, avg_response_ms, min_battery_pct
            FROM reports.daily_reports
            WHERE user_id = {subject:String}
              AND report_date BETWEEN {from:Date} AND {to:Date}
              AND batch_id IN (%s)
            ORDER BY report_date, prosthesis_id
            LIMIT 10001 FORMAT JSON
            """.formatted(String.join(",", batches)), parameters);
    }

    private JsonNode query(String sql, Map<String, String> parameters) {
        StringBuilder target = new StringBuilder(url).append("/?wait_end_of_query=1&max_execution_time=10");
        parameters.forEach((key, value) -> target.append("&param_").append(key).append("=")
            .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.toString()))
            .timeout(Duration.ofSeconds(15))
            .header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
            .POST(HttpRequest.BodyPublishers.ofString(sql)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OLAP request failed");
            }
            JsonNode data = json.readTree(response.body()).get("data");
            if (data == null || !data.isArray()) {
                throw new IOException("Invalid OLAP response");
            }
            return data;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException error) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Хранилище отчётов недоступно. Повторите позже.");
    }
}
