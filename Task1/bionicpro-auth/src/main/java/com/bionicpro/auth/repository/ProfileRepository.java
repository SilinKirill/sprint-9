package com.bionicpro.auth.repository;

import com.bionicpro.auth.exception.AuthException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public final class ProfileRepository {
    private final String connectionUrl;
    private final JsonMapper json;

    public ProfileRepository(Path file, JsonMapper json) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.connectionUrl = "jdbc:sqlite:" + file;
        this.json = json;
        try (Connection connection = DriverManager.getConnection(connectionUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS profiles "
                    + "(sub TEXT PRIMARY KEY, consent TEXT NOT NULL, profile TEXT, updated INTEGER NOT NULL)");
        }
    }

    public Map<String, Object> read(String subject) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileConsent", "pending");
        result.put("profile", null);
        try (Connection connection = DriverManager.getConnection(connectionUrl);
             PreparedStatement statement = connection.prepareStatement("SELECT consent, profile FROM profiles WHERE sub = ?")) {
            statement.setString(1, subject);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    String savedProfile = row.getString(2);
                    result.put("profileConsent", row.getString(1));
                    result.put("profile", savedProfile == null ? null : json.readValue(savedProfile, Map.class));
                }
            }
            return result;
        } catch (Exception error) {
            throw new AuthException(503, "Профиль временно недоступен");
        }
    }

    public void save(String subject, boolean accepted, Map<String, Object> profile, long now) {
        String sql = "INSERT INTO profiles VALUES (?, ?, ?, ?) ON CONFLICT(sub) DO UPDATE SET "
                + "consent=excluded.consent, profile=excluded.profile, updated=excluded.updated";
        try (Connection connection = DriverManager.getConnection(connectionUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subject);
            statement.setString(2, accepted ? "accepted" : "declined");
            statement.setString(3, profile == null ? null : json.writeValueAsString(profile));
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (Exception error) {
            throw new AuthException(503, "Не удалось сохранить согласие");
        }
    }
}
