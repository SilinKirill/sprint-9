package com.bionicpro.reports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bionicpro.reports.config.SecurityConfig;
import com.bionicpro.reports.controller.ReportController;
import com.bionicpro.reports.repository.ReportRepository;
import com.bionicpro.reports.storage.ReportStorage;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

class ReportSecurityTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReportRepository repository = mock(ReportRepository.class);
    private final ReportStorage storage = mock(ReportStorage.class);
    private final LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    private RSAKey key;
    private HttpServer jwks;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @Configuration
    @EnableWebMvc
    static class Web {}

    @BeforeEach
    void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/certs", exchange -> {
            byte[] body = json.writeValueAsBytes(Map.of("keys", List.of(key.toPublicJWK().toJSONObject())));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("reportRepository", repository);
            factory.registerSingleton("reportStorage", storage);
            factory.registerSingleton("jwtDecoder", new SecurityConfig().jwtDecoder(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs", "https://issuer", "bionicpro-auth"));
        });
        context.register(Web.class, ReportController.class, SecurityConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Map<LocalDate, String> periods = new LinkedHashMap<>();
        periods.put(yesterday, "batch");
        periods.put(yesterday.minusDays(2), "older-batch");
        when(repository.periods()).thenReturn(periods);
        when(repository.report(anyString(), any(), any(), any())).thenReturn(json.readTree("[]"));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (jwks != null) jwks.stop(0);
    }

    private String token(String subject, String corruption) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(corruption.equals("issuer") ? "https://wrong" : "https://issuer")
            .audience(corruption.equals("audience") ? "wrong" : "bionicpro-auth")
            .subject(subject).issueTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(corruption.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(120)))
            .claim("azp", corruption.equals("azp") ? "wrong" : "bionicpro-auth")
            .claim("typ", corruption.equals("id-token") ? "ID" : "Bearer");
        if (!corruption.equals("no-roles")) {
            claims.claim("realm_access", Map.of("roles", corruption.equals("yandex-roles")
                ? List.of("offline_access", "default-roles-reports-realm", "uma_authorization")
                : List.of("user")));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .type(JOSEObjectType.JWT).keyID("test").build(), claims.build());
        jwt.sign(new RSASSASigner(corruption.equals("signature") ? new RSAKeyGenerator(2048).generate() : key));
        return jwt.serialize();
    }

    @Test
    void anonymousCannotReadReport() throws Exception {
        assertEquals(401, mvc.perform(get("/reports")).andReturn().getResponse().getStatus());
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "signature", "azp", "id-token"})
    void invalidTokenCannotReadReport(String corruption) throws Exception {
        assertEquals(401, mvc.perform(get("/reports").header("Authorization", "Bearer " + token("alice", corruption)))
            .andReturn().getResponse().getStatus());
        verifyNoInteractions(repository);
    }

    @Test
    void subjectComesOnlyFromVerifiedJwt() throws Exception {
        when(repository.report(eq("alice"), any(), any(), any()))
            .thenReturn(json.readTree("[{\"prosthesis_id\":\"alice-device\"}]"));
        String body = mvc.perform(get("/reports").header("Authorization", "Bearer " + token("alice", "")))
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("/reports/files/v1/"));
        assertFalse(body.contains("bob-device"));
        verify(repository).report(eq("alice"), eq(yesterday), eq(yesterday), any());
        assertEquals(404, mvc.perform(get("/reports").header("Authorization", "Bearer " + token("bob", "")))
            .andReturn().getResponse().getStatus());
        verify(repository).report(eq("bob"), eq(yesterday), eq(yesterday), any());
        assertEquals(400, mvc.perform(get("/reports").param("user_id", "bob")
            .header("Authorization", "Bearer " + token("alice", ""))).andReturn().getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yandex-roles", "no-roles"})
    void externalUserCanReadOnlyOwnReportWithoutLocalRoles(String roleClaims) throws Exception {
        String authorization = "Bearer " + token("yandex-user", roleClaims);
        // A valid external account without a CRM record gets a data error, not 401.
        assertEquals(404, mvc.perform(get("/reports").header("Authorization", authorization))
            .andReturn().getResponse().getStatus());
        verify(repository).report(eq("yandex-user"), eq(yesterday), eq(yesterday), any());

        when(repository.report(eq("yandex-user"), any(), any(), any()))
            .thenReturn(json.readTree("[{\"prosthesis_id\":\"own-yandex-device\"}]"));
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(get("/reports")
            .header("Authorization", authorization)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("/reports/files/v1/"));
        assertEquals(400, mvc.perform(get("/reports").param("user_id", "alice")
            .header("Authorization", authorization)).andReturn().getResponse().getStatus());
    }

    @Test
    void missingDayInsidePeriodAndFutureAreNotReady() throws Exception {
        for (LocalDate from : List.of(yesterday.minusDays(2), yesterday.plusDays(1))) {
            assertEquals(409, mvc.perform(get("/reports").param("from", from.toString())
                .param("to", from.isBefore(yesterday) ? yesterday.toString() : from.toString())
                .header("Authorization", "Bearer " + token("alice", ""))).andReturn().getResponse().getStatus());
        }
        verify(repository, never()).report(anyString(), any(), any(), any());
        when(repository.periods()).thenReturn(Map.of());
        assertEquals(409, mvc.perform(get("/reports").header("Authorization", "Bearer " + token("alice", "")))
            .andReturn().getResponse().getStatus());
    }

    @Test
    void invalidRangesAreRejectedBeforeReadingOlap() throws Exception {
        for (String query : List.of("from=bad&to=bad", "from=2026-01-01", "from=2026-02-01&to=2026-01-01",
                "from=2026-01-01&to=2026-02-01", "from=2026-01-01&from=2026-01-02&to=2026-01-02")) {
            assertEquals(400, mvc.perform(get("/reports?" + query)
                .header("Authorization", "Bearer " + token("alice", ""))).andReturn().getResponse().getStatus());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void reusesS3AndChecksOwnerAndGenerationBeforeDownload() throws Exception {
        when(repository.report(eq("alice"), any(), any(), any()))
            .thenReturn(json.readTree("[{\"prosthesis_id\":\"alice-device\"}]"));
        String authorization = "Bearer " + token("alice", "");
        String first = mvc.perform(get("/reports").header("Authorization", authorization))
            .andReturn().getResponse().getContentAsString();
        String url = json.readTree(first).get("url").asText();
        String key = url.substring("/reports/files/".length());
        verify(storage).save(eq(key), any(byte[].class));
        when(storage.exists(key)).thenReturn(true);
        when(storage.origin(key)).thenReturn("http://minio:9000/reports/" + key + "?signature=private");
        String second = mvc.perform(get("/reports").header("Authorization", authorization))
            .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        verify(repository, times(1)).report(anyString(), any(), any(), any());
        assertEquals(204, mvc.perform(get(url).header("Authorization", authorization))
            .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get(url)).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get(url).header("Authorization", "Bearer " + token("bob", "")))
            .andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get(url).param("user_id", "alice").header("Authorization", authorization))
            .andReturn().getResponse().getStatus());
        when(repository.periods()).thenReturn(Map.of(yesterday, "new-batch"));
        assertEquals(409, mvc.perform(get(url).header("Authorization", authorization))
            .andReturn().getResponse().getStatus());
        String updated = mvc.perform(get("/reports").header("Authorization", authorization))
            .andReturn().getResponse().getContentAsString();
        assertNotEquals(first, updated);
        verify(repository, times(2)).report(anyString(), any(), any(), any());
        when(repository.periods()).thenReturn(Map.of());
        assertEquals(409, mvc.perform(get(url).header("Authorization", authorization))
            .andReturn().getResponse().getStatus());
    }
}
