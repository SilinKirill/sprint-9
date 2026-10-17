package com.bionicpro.reports.controller;

import com.bionicpro.reports.repository.ReportRepository;
import com.bionicpro.reports.storage.ReportStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ReportController {
    private final ReportRepository reports;
    private final ReportStorage storage;
    private final JsonMapper json = JsonMapper.builder().build();

    public ReportController(ReportRepository reports, ReportStorage storage) {
        this.reports = reports;
        this.storage = storage;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/reports")
    public ResponseEntity<?> report(@AuthenticationPrincipal Jwt identity,
                                     @RequestParam MultiValueMap<String, String> parameters) {
        Period period = period(parameters);
        String key = key(identity.getSubject(), period);
        if (!storage.exists(key)) {
            JsonNode rows = reports.report(identity.getSubject(), period.from(), period.to(), period.batches());
            if (rows.isEmpty()) {
                throw problem(HttpStatus.NOT_FOUND, "За обработанный период нет данных о ваших протезах в CRM.");
            }
            if (rows.size() > 10000) {
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "Отчёт слишком большой. Сократите период.");
            }
            storage.save(key, json.writeValueAsBytes(Map.of("from", period.from(), "to", period.to(),
                "timezone", "UTC", "daily", rows)));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(Map.of("from", period.from(), "to", period.to(), "url", "/reports/files/" + key));
    }

    @GetMapping("/reports/files/v1/{owner}/{from}/{to}/{version}.json")
    public ResponseEntity<?> authorize(@AuthenticationPrincipal Jwt identity,
            @PathVariable String owner, @PathVariable String from, @PathVariable String to,
            @PathVariable String version, @RequestParam MultiValueMap<String, String> parameters) {
        if (!parameters.isEmpty()) throw problem(HttpStatus.BAD_REQUEST, "Параметры ссылки недопустимы.");
        // Check ownership before consulting storage, even for an already cached object.
        if (!owner.equals(hash(identity.getSubject()))) {
            throw problem(HttpStatus.NOT_FOUND, "Отчёт не найден.");
        }
        org.springframework.util.LinkedMultiValueMap<String, String> dates =
            new org.springframework.util.LinkedMultiValueMap<>();
        dates.add("from", from);
        dates.add("to", to);
        Period period = period(dates);
        String requested = "v1/" + owner + "/" + from + "/" + to + "/" + version + ".json";
        if (!requested.equals(key(identity.getSubject(), period))) {
            throw problem(HttpStatus.CONFLICT, "Отчёт обновлён. Запросите новую ссылку.");
        }
        if (!storage.exists(requested)) throw problem(HttpStatus.NOT_FOUND, "Отчёт не найден.");
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Report-Origin", storage.origin(requested)).build();
    }

    private record Period(LocalDate from, LocalDate to, Map<LocalDate, String> batches) {}

    private String key(String subject, Period period) {
        StringBuilder generations = new StringBuilder("v1\n");
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            generations.append(day).append(':').append(period.batches().get(day)).append('\n');
        }
        return "v1/" + hash(subject) + "/" + period.from() + "/" + period.to()
            + "/" + hash(generations.toString()) + ".json";
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Period period(MultiValueMap<String, String> parameters) {
        if (!Set.of("from", "to").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.containsKey("from") != parameters.containsKey("to")) {
            throw problem(HttpStatus.BAD_REQUEST, "Укажите from и to; выбор другого пользователя недоступен.");
        }
        LocalDate from = null;
        LocalDate to = null;
        if (!parameters.isEmpty()) {
            try {
                from = LocalDate.parse(parameters.getFirst("from"));
                to = LocalDate.parse(parameters.getFirst("to"));
            } catch (DateTimeParseException error) {
                throw problem(HttpStatus.BAD_REQUEST, "Формат дат: YYYY-MM-DD.");
            }
            if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 31) {
                throw problem(HttpStatus.BAD_REQUEST, "Выберите период от 1 до 31 дня.");
            }
        }
        Map<LocalDate, String> periods = reports.periods();
        if (periods.isEmpty()) {
            throw problem(HttpStatus.CONFLICT, "Airflow ещё не подготовил отчёты.");
        }
        if (from == null) {
            from = periods.keySet().iterator().next();
            to = from;
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!day.isBefore(LocalDate.now(ZoneOffset.UTC)) || !periods.containsKey(day)) {
                throw problem(HttpStatus.CONFLICT, "Период ещё не обработан Airflow: " + day + ".");
            }
        }
        return new Period(from, to, periods);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(Map.of("detail", error.getReason() == null ? "Ошибка запроса" : error.getReason()));
    }

    private ResponseStatusException problem(HttpStatus status, String detail) {
        return new ResponseStatusException(status, detail);
    }
}
