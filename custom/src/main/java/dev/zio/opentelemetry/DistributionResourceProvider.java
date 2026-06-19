package dev.zio.opentelemetry;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;

public final class DistributionResourceProvider implements ResourceProvider {

  @Override
  public Resource createResource(ConfigProperties config) {
    return DistributionResource.get();
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }
}
