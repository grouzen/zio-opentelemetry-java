package dev.zio.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

public final class DistributionResource {
  public static final AttributeKey<String> DISTRIBUTION_NAME =
      AttributeKey.stringKey("telemetry.distro.name");

  public static final AttributeKey<String> DISTRIBUTION_VERSION =
      AttributeKey.stringKey("telemetry.distro.version");

  private static final String NAME = "zio-opentelemetry-java";
  private static final Resource INSTANCE = buildResource();

  private DistributionResource() {}

  public static Resource get() {
    return INSTANCE;
  }

  static Resource buildResource() {
    return Resource.create(
        Attributes.of(DISTRIBUTION_NAME, NAME, DISTRIBUTION_VERSION, implementationVersion()));
  }

  private static String implementationVersion() {
    String version = DistributionResource.class.getPackage().getImplementationVersion();
    return version != null ? version : "unspecified";
  }
}
