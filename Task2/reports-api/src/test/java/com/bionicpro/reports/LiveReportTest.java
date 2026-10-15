package com.bionicpro.reports;

import static org.junit.jupiter.api.Assertions.*;

import com.bionicpro.reports.controller.ReportController;
import com.bionicpro.reports.repository.ReportRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Runs against the demo data prepared by the real Airflow DAG. */
@EnabledIfEnvironmentVariable(named = "TEST_CLICKHOUSE_URL", matches = ".+")
class LiveReportTest {
    @Test
    void readsActualPublishedMetricsAndFiltersOwner() {
        ReportRepository repository = new ReportRepository(System.getenv("TEST_CLICKHOUSE_URL"),
            "reports_reader", "reports_reader_demo");
        String subject = System.getenv("TEST_REPORT_SUBJECT");
        assertNotNull(subject);
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Map<LocalDate, String> periods = repository.periods();
        assertTrue(periods.containsKey(day));
        JsonNode rows = repository.report(subject, day, day, periods);
        assertEquals(2, rows.size());
        for (JsonNode row : rows) {
            assertEquals("demo-client", row.get("client_id").asText());
            assertEquals(3, row.get("samples").asLong());
            assertEquals(2, row.get("successful_movements").asLong());
            assertEquals(90, row.get("avg_response_ms").asDouble());
            assertEquals(80, row.get("min_battery_pct").asDouble());
        }
        JsonNode other = repository.report("demo-other-subject", day, day, periods);
        assertEquals(2, other.size());
        assertEquals("demo-other", other.get(0).get("client_id").asText());
        assertTrue(repository.report("not-a-crm-user", day, day, periods).isEmpty());

        ReportController controller = new ReportController(repository);
        Jwt identity = Jwt.withTokenValue("validated-by-security-layer").header("alg", "RS256").subject(subject).build();
        LinkedMultiValueMap<String, String> dates = new LinkedMultiValueMap<>();
        dates.add("from", day.toString());
        dates.add("to", day.toString());
        ResponseEntity<?> response = controller.report(identity, dates);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        dates.set("from", day.plusDays(1).toString());
        dates.set("to", day.plusDays(1).toString());
        ResponseStatusException notReady = assertThrows(ResponseStatusException.class,
            () -> controller.report(identity, dates));
        assertEquals(409, notReady.getStatusCode().value());
    }
}
