package ai.beatmind.generation;

import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * S3-compatible storage: Cloudflare R2 in the cloud, SeaweedFS locally. Same settings as the
 * workers ({@code S3_ENDPOINT}, {@code S3_BUCKET} and keys). The client is made on first use.
 */
@Component
class S3AudioStore implements AudioStore {

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private volatile S3Client client;

    S3AudioStore(@Value("${beatmind.storage.endpoint:}") String endpoint,
                 @Value("${beatmind.storage.bucket:beatmind-audio}") String bucket,
                 @Value("${beatmind.storage.access-key:}") String accessKey,
                 @Value("${beatmind.storage.secret-key:}") String secretKey,
                 @Value("${beatmind.storage.region:auto}") String region) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    @Override
    public Optional<Audio> open(String key) {
        try {
            ResponseInputStream<GetObjectResponse> in = client().getObject(b -> b.bucket(bucket).key(key));
            GetObjectResponse meta = in.response();
            String type = meta.contentType() == null ? "application/octet-stream" : meta.contentType();
            return Optional.of(new Audio(in, type, meta.contentLength() == null ? -1 : meta.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    private S3Client client() {
        S3Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    var builder = S3Client.builder()
                            .httpClient(UrlConnectionHttpClient.create())
                            .region(Region.of(region))
                            .forcePathStyle(true)
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(accessKey, secretKey)));
                    if (!endpoint.isBlank()) {
                        builder.endpointOverride(URI.create(endpoint));
                    }
                    client = builder.build();
                }
                c = client;
            }
        }
        return c;
    }
}
