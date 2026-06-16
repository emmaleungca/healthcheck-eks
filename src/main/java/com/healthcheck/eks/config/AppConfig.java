package com.healthcheck.eks.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

public final class AppConfig {

    private final String awsRegion;
    private final String clusterName;
    private final Optional<String> namespace;

    private AppConfig(String awsRegion, String clusterName, Optional<String> namespace) {
        this.awsRegion = awsRegion;
        this.clusterName = clusterName;
        this.namespace = namespace;
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        String region = firstNonBlank(
                System.getenv("AWS_REGION"),
                System.getenv("AWS_DEFAULT_REGION"),
                properties.getProperty("aws.region")
        );
        String clusterName = firstNonBlank(
                System.getenv("EKS_CLUSTER_NAME"),
                properties.getProperty("eks.cluster.name")
        );
        String namespace = firstNonBlank(
                System.getenv("KUBERNETES_NAMESPACE"),
                properties.getProperty("kubernetes.namespace")
        );

        if (region == null || region.isBlank()) {
            throw new IllegalStateException("aws.region must be set in application.properties or AWS_REGION env var");
        }
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalStateException("eks.cluster.name must be set in application.properties or EKS_CLUSTER_NAME env var");
        }

        Optional<String> namespaceFilter = (namespace == null || namespace.isBlank())
                ? Optional.empty()
                : Optional.of(namespace.trim());

        return new AppConfig(region.trim(), clusterName.trim(), namespaceFilter);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public String awsRegion() {
        return awsRegion;
    }

    public String clusterName() {
        return clusterName;
    }

    public Optional<String> namespace() {
        return namespace;
    }
}
