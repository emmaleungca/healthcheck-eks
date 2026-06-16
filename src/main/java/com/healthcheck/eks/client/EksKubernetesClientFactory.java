package com.healthcheck.eks.client;

import com.healthcheck.eks.auth.EksTokenProvider;
import com.healthcheck.eks.config.AppConfig;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;

import java.io.IOException;
import java.util.Base64;

public final class EksKubernetesClientFactory {

    private EksKubernetesClientFactory() {
    }

    public static ApiClient create(AppConfig config) throws IOException {
        Region region = Region.of(config.awsRegion());
        var credentialsProvider = DefaultCredentialsProvider.create();

        try (EksClient eksClient = EksClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()) {

            Cluster cluster = eksClient.describeCluster(DescribeClusterRequest.builder()
                            .name(config.clusterName())
                            .build())
                    .cluster();

            byte[] caBytes = Base64.getDecoder().decode(cluster.certificateAuthority().data());
            String token = new EksTokenProvider(credentialsProvider, config.awsRegion(), config.clusterName())
                    .generateToken();

            ApiClient client = new ClientBuilder()
                    .setBasePath(cluster.endpoint())
                    .setCertificateAuthority(caBytes)
                    .setVerifyingSsl(true)
                    .build();

            client.setApiKeyPrefix("Bearer");
            client.setApiKey(token);

            return client;
        }
    }
}
