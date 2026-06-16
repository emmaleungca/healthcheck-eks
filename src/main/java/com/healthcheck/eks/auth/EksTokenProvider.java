package com.healthcheck.eks.auth;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Generates EKS Kubernetes bearer tokens compatible with {@code aws eks get-token}.
 * The stock {@code EKSAuthentication} class URL-encodes the presigned URL before base64,
 * which EKS rejects with 401.
 */
public final class EksTokenProvider {

    private static final int TOKEN_TTL_SECONDS = 60;

    private final AwsCredentialsProvider credentialsProvider;
    private final String region;
    private final String clusterName;
    private final URI stsEndpoint;

    public EksTokenProvider(AwsCredentialsProvider credentialsProvider, String region, String clusterName) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
        this.clusterName = clusterName;
        this.stsEndpoint = URI.create("https://sts." + region + ".amazonaws.com/");
    }

    public String generateToken() {
        SdkHttpRequest request = SdkHttpRequest.builder()
                .uri(stsEndpoint)
                .method(SdkHttpMethod.GET)
                .putHeader("x-k8s-aws-id", clusterName)
                .putRawQueryParameter("Action", "GetCallerIdentity")
                .putRawQueryParameter("Version", "2011-06-15")
                .build();

        AwsV4HttpSigner signer = AwsV4HttpSigner.create();
        SignedRequest signedRequest = signer.sign(builder -> builder
                .identity(credentialsProvider.resolveCredentials())
                .request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "sts")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4HttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.of(TOKEN_TTL_SECONDS, ChronoUnit.SECONDS)));

        String presignedUrl = signedRequest.request().getUri().toString();
        String encodedUrl = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(presignedUrl.getBytes(StandardCharsets.UTF_8));
        return "k8s-aws-v1." + encodedUrl;
    }
}
