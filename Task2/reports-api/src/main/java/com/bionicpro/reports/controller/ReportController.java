package com.bionicpro.reports.controller;

import com.bionicpro.reports.repository.ReportRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
public class ReportController {
    private final ReportRepository reports;

    public ReportController(ReportRepository reports) {
        this.reports = reports;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/reports")
    public ResponseEntity<?> report(@AuthenticationPrincipal Jwt identity,
                                     @RequestParam MultiValueMap<String, String> parameters) {
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
        JsonNode rows = reports.report(identity.getSubject(), from, to, periods);
        if (rows.isEmpty()) {
            throw problem(HttpStatus.NOT_FOUND, "За обработанный период нет данных о ваших протезах в CRM.");
        }
        if (rows.size() > 10000) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "Отчёт слишком большой. Сократите период.");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + from + "-" + to + ".json")
            .body(Map.of("from", from, "to", to, "timezone", "UTC", "daily", rows));
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
