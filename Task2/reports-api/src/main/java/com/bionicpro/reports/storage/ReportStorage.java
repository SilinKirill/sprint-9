package com.bionicpro.reports.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import jakarta.annotation.PreDestroy;

@Component
public class ReportStorage {
    private final S3Client client;
    private final S3Presigner signer;
    private final String bucket;

    public ReportStorage(@Value("${S3_ENDPOINT:http://minio:9000}") String endpoint,
                         @Value("${S3_ACCESS_KEY}") String accessKey,
                         @Value("${S3_SECRET_KEY}") String secretKey,
                         @Value("${S3_BUCKET:reports}") String bucket) {
        this.bucket = bucket;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
            .credentialsProvider(credentials).serviceConfiguration(configuration)
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(20))
                .apiCallAttemptTimeout(Duration.ofSeconds(8))).build();
        signer = S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
            .credentialsProvider(credentials).serviceConfiguration(configuration).build();
    }

    public boolean exists(String key) {
        try {
            client.headObject(request -> request.bucket(bucket).key(key));
            return true;
        } catch (S3Exception error) {
            // HEAD does not always include an S3 error code. A missing bucket is a setup error;
            // the subsequent PUT still fails closed rather than returning a broken link.
            if (error.statusCode() == 404) return false;
            throw unavailable();
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    public void save(String key, byte[] body) {
        try {
            client.putObject(request -> request.bucket(bucket).key(key).contentType("application/json")
                .cacheControl("max-age=86400"), RequestBody.fromBytes(body));
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    // Used only between API, BFF and the private CDN; never sent to the browser.
    public String origin(String key) {
        return signer.presignGetObject(request -> request.signatureDuration(Duration.ofMinutes(1))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())).url().toString();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3-хранилище недоступно. Повторите позже.");
    }

    @PreDestroy
    public void close() {
        client.close();
        signer.close();
    }
}
