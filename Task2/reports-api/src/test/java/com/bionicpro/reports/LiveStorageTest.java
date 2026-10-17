package com.bionicpro.reports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bionicpro.reports.controller.ReportController;
import com.bionicpro.reports.repository.ReportRepository;
import com.bionicpro.reports.storage.ReportStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Real S3 and Nginx; synthetic owner and generations never modify CRM or ETL. */
@EnabledIfEnvironmentVariable(named = "TEST_S3_ENDPOINT", matches = ".+")
class LiveStorageTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_REPORT_SUBJECT", matches = ".+")
    void usesActualAirflowPublication() throws Exception {
        ReportRepository repository = spy(new ReportRepository("http://clickhouse:8123",
            "reports_reader", System.getenv("TEST_CLICKHOUSE_PASSWORD")));
        Jwt identity = Jwt.withTokenValue("verified").header("alg", "RS256")
            .subject(System.getenv("TEST_REPORT_SUBJECT")).build();
        ReportStorage storage = new ReportStorage(System.getenv("TEST_S3_ENDPOINT"),
            System.getenv("REPORTS_S3_ACCESS_KEY"), System.getenv("REPORTS_S3_SECRET_KEY"), "reports");
        try {
            ReportController controller = new ReportController(repository, storage);
            LinkedMultiValueMap<String, String> dates = new LinkedMultiValueMap<>();
            dates.add("from", LocalDate.now(ZoneOffset.UTC).minusDays(2).toString());
            dates.add("to", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
            Map<?, ?> report = (Map<?, ?>) controller.report(identity, dates).getBody();
            String url = report.get("url").toString();
            Path snapshot = Path.of("/verification/report-url.txt");
            if ("true".equals(System.getenv("TEST_EXPECT_REFRESH"))) {
                String oldUrl = Files.readString(snapshot);
                assertNotEquals(oldUrl, url);
                assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> authorize(controller, identity, oldUrl)).getStatusCode().value());
            }
            clearInvocations(repository);
            assertEquals(report, controller.report(identity, dates).getBody());
            verify(repository, never()).report(anyString(), any(), any(), any());
            HttpResponse<String> file = download(url, authorize(controller, identity, url));
            assertEquals(200, file.statusCode());
            assertTrue(file.body().contains("demo-client"));
            assertFalse(file.body().contains("demo-other"));
            HttpResponse<String> cached = download(url, authorize(controller, identity, url));
            assertEquals("HIT", cached.headers().firstValue("X-Report-Cache").orElseThrow());
            assertEquals(file.body(), cached.body());
            Files.writeString(snapshot, url);
        } finally {
            storage.close();
        }
    }

    @Test
    void storesReusesCachesAndInvalidatesReports() throws Exception {
        ReportRepository repository = mock(ReportRepository.class);
        JsonMapper json = JsonMapper.builder().build();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        String subject = "storage-test-" + UUID.randomUUID();
        Jwt identity = Jwt.withTokenValue("verified").header("alg", "RS256").subject(subject).build();
        when(repository.periods()).thenReturn(Map.of(day, UUID.randomUUID().toString()));
        when(repository.report(eq(subject), any(), any(), any()))
            .thenReturn(json.readTree("[{\"prosthesis_id\":\"own-device\",\"samples\":3}]"));
        ReportStorage storage = new ReportStorage(System.getenv("TEST_S3_ENDPOINT"),
            System.getenv("REPORTS_S3_ACCESS_KEY"), System.getenv("REPORTS_S3_SECRET_KEY"), "reports");
        try {
            ReportController controller = new ReportController(repository, storage);
            Map<?, ?> first = (Map<?, ?>) controller.report(identity, new LinkedMultiValueMap<>()).getBody();
            String url = first.get("url").toString();
            assertEquals(first, controller.report(identity, new LinkedMultiValueMap<>()).getBody());
            verify(repository, times(1)).report(anyString(), any(), any(), any());
            String origin = authorize(controller, identity, url);
            HttpResponse<String> miss = download(url, origin);
            assertEquals(200, miss.statusCode());
            assertEquals("MISS", miss.headers().firstValue("X-Report-Cache").orElseThrow());
            assertTrue(miss.body().contains("own-device"));
            HttpResponse<String> hit = download(url, authorize(controller, identity, url));
            assertEquals(200, hit.statusCode());
            assertEquals("HIT", hit.headers().firstValue("X-Report-Cache").orElseThrow());
            assertEquals(miss.body(), hit.body());
            String key = url.substring("/reports/files/".length());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(
                System.getenv("TEST_S3_ENDPOINT") + "/reports/" + key)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(
                "http://reports-cdn:8080/" + key)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            Jwt other = Jwt.withTokenValue("verified").header("alg", "RS256").subject("other").build();
            assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> authorize(controller, other, url)).getStatusCode().value());
            when(repository.periods()).thenReturn(Map.of(day, UUID.randomUUID().toString()));
            when(repository.report(eq(subject), any(), any(), any()))
                .thenReturn(json.readTree("[{\"prosthesis_id\":\"own-device\",\"samples\":4}]"));
            assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> authorize(controller, identity, url)).getStatusCode().value());
            Map<?, ?> updated = (Map<?, ?>) controller.report(identity, new LinkedMultiValueMap<>()).getBody();
            String newUrl = updated.get("url").toString();
            assertNotEquals(url, newUrl);
            HttpResponse<String> fresh = download(newUrl, authorize(controller, identity, newUrl));
            assertEquals(200, fresh.statusCode());
            assertEquals("MISS", fresh.headers().firstValue("X-Report-Cache").orElseThrow());
            assertTrue(fresh.body().contains("\"samples\":4"));
            when(repository.periods()).thenReturn(Map.of());
            assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> authorize(controller, identity, newUrl)).getStatusCode().value());
        } finally {
            storage.close();
        }
    }

    private String authorize(ReportController controller, Jwt identity, String url) {
        String[] parts = url.split("/");
        return controller.authorize(identity, parts[4], parts[5], parts[6],
            parts[7].replace(".json", ""), new LinkedMultiValueMap<>()).getHeaders().getFirst("X-Report-Origin");
    }

    private HttpResponse<String> download(String url, String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://reports-cdn:8080/"
            + url.substring("/reports/files/".length()))).header("X-Report-Origin", origin).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
