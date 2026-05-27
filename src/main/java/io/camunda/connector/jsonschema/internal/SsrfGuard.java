package io.camunda.connector.jsonschema.internal;

import io.camunda.connector.api.error.ConnectorException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public final class SsrfGuard {

  private static final String DISABLE_PROPERTY = "connector.jsonschema.ssrfGuard.disabled";

  private SsrfGuard() {}

  public static void checkUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new ConnectorException("BAD_SCHEMA_URL", "Schema URL is not a valid URI: " + url, e);
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new ConnectorException("BAD_SCHEMA_URL", "Schema URL must include a scheme: " + url);
    }
    String s = scheme.toLowerCase();
    if (s.equals("file")) {
      throw new ConnectorException("BAD_SCHEMA_URL", "file:// URLs are not allowed for schemas.");
    }
    if (!s.equals("http") && !s.equals("https")) {
      throw new ConnectorException(
          "BAD_SCHEMA_URL", "Unsupported scheme '" + scheme + "' for schema URL " + url);
    }
    if (isGuardDisabled()) {
      return;
    }
    String host = uri.getHost();
    if (host == null) {
      throw new ConnectorException("BAD_SCHEMA_URL", "Schema URL is missing a host: " + url);
    }
    try {
      for (InetAddress addr : InetAddress.getAllByName(host)) {
        if (isBlocked(addr)) {
          throw new ConnectorException(
              "SSRF_BLOCKED",
              "Schema URL resolves to a blocked address ("
                  + addr.getHostAddress()
                  + "). Set system property "
                  + DISABLE_PROPERTY
                  + "=true to override.");
        }
      }
    } catch (UnknownHostException e) {
      throw new ConnectorException(
          "BAD_SCHEMA_URL", "Schema URL host cannot be resolved: " + host, e);
    }
  }

  private static boolean isGuardDisabled() {
    return Boolean.parseBoolean(System.getProperty(DISABLE_PROPERTY, "false"))
        || Boolean.parseBoolean(System.getenv().getOrDefault("CONNECTOR_JSONSCHEMA_SSRF_GUARD_DISABLED", "false"));
  }

  private static boolean isBlocked(InetAddress addr) {
    return addr.isLoopbackAddress()
        || addr.isLinkLocalAddress()
        || addr.isSiteLocalAddress()
        || addr.isAnyLocalAddress()
        || addr.isMulticastAddress();
  }
}
