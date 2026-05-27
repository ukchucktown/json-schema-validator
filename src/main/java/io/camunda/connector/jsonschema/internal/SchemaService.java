package io.camunda.connector.jsonschema.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.jsonschema.model.ValidateRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SchemaService.class);
  private static final long URL_TTL_MILLIS = 5L * 60 * 1000;

  private final ObjectMapper mapper;
  private final HttpClient http;
  private final ConcurrentHashMap<String, JsonSchema> compileCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CachedText> urlCache = new ConcurrentHashMap<>();

  public SchemaService() {
    this(new ObjectMapper(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  public SchemaService(ObjectMapper mapper, HttpClient http) {
    this.mapper = mapper;
    this.http = http;
  }

  public JsonNode toNode(Object data) {
    if (data == null) {
      return NullNode.getInstance();
    }
    if (data instanceof JsonNode jn) {
      return jn;
    }
    return mapper.valueToTree(data);
  }

  public JsonSchema loadSchema(ValidateRequest req) {
    String source = req.schemaSource();
    boolean allowExternal = Boolean.TRUE.equals(req.allowExternalRefs());
    if ("inline".equalsIgnoreCase(source)) {
      return loadInline(req.schema(), allowExternal);
    }
    if ("url".equalsIgnoreCase(source)) {
      return loadFromUrl(req.schemaUrl(), allowExternal);
    }
    throw new ConnectorException(
        "BAD_SCHEMA_SOURCE",
        "Unknown schema source '" + source + "'. Expected 'inline' or 'url'.");
  }

  private JsonSchema loadInline(Object inline, boolean allowExternal) {
    if (inline == null || (inline instanceof String s && s.isBlank())) {
      throw new ConnectorException(
          "MISSING_SCHEMA", "Inline schema is required when schema source is 'inline'.");
    }
    JsonNode schemaNode = parseInline(inline);
    return compile(schemaNode, allowExternal);
  }

  private JsonNode parseInline(Object inline) {
    try {
      if (inline instanceof String s) {
        return mapper.readTree(s);
      }
      return mapper.valueToTree(inline);
    } catch (Exception e) {
      throw new ConnectorException("BAD_SCHEMA", "Inline schema is not valid JSON: " + e.getMessage(), e);
    }
  }

  private JsonSchema loadFromUrl(String url, boolean allowExternal) {
    if (url == null || url.isBlank()) {
      throw new ConnectorException(
          "MISSING_SCHEMA_URL", "Schema URL is required when schema source is 'url'.");
    }
    SsrfGuard.checkUrl(url);
    String text = fetchWithCache(url);
    JsonNode node;
    try {
      node = mapper.readTree(text);
    } catch (Exception e) {
      throw new ConnectorException(
          "BAD_SCHEMA", "Schema fetched from " + url + " is not valid JSON: " + e.getMessage(), e);
    }
    return compile(node, allowExternal);
  }

  private String fetchWithCache(String url) {
    long now = System.currentTimeMillis();
    CachedText cached = urlCache.get(url);
    if (cached != null && (now - cached.fetchedAtMillis) < URL_TTL_MILLIS) {
      return cached.text;
    }
    String text = fetch(url);
    urlCache.put(url, new CachedText(text, now));
    LOGGER.info("Fetched schema from URL {}", url);
    return text;
  }

  private String fetch(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new ConnectorException(
            "SCHEMA_FETCH_FAILED",
            "GET " + url + " returned HTTP " + resp.statusCode());
      }
      return resp.body();
    } catch (ConnectorException e) {
      throw e;
    } catch (Exception e) {
      throw new ConnectorException(
          "SCHEMA_FETCH_FAILED", "Failed to fetch schema from " + url + ": " + e.getMessage(), e);
    }
  }

  private JsonSchema compile(JsonNode schemaNode, boolean allowExternal) {
    if (!allowExternal) {
      rejectExternalRefs(schemaNode);
    }
    String hash = hashOf(schemaNode);
    return compileCache.computeIfAbsent(hash, k -> compileFresh(schemaNode));
  }

  private JsonSchema compileFresh(JsonNode schemaNode) {
    SpecVersion.VersionFlag version;
    try {
      version = SpecVersionDetector.detectOptionalVersion(schemaNode, false).orElse(SpecVersion.VersionFlag.V202012);
    } catch (Exception e) {
      throw new ConnectorException("BAD_SCHEMA", "Cannot determine schema spec version: " + e.getMessage(), e);
    }
    LOGGER.info("Compiling schema (spec={})", version);
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(version);
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    try {
      return factory.getSchema(schemaNode, config);
    } catch (Exception e) {
      throw new ConnectorException("BAD_SCHEMA", "Failed to compile schema: " + e.getMessage(), e);
    }
  }

  private static void rejectExternalRefs(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if ("$ref".equals(field.getKey()) && field.getValue().isTextual()) {
          String ref = field.getValue().asText();
          if (!ref.startsWith("#")) {
            throw new ConnectorException(
                "EXTERNAL_REFS_DISABLED",
                "Schema contains an external $ref ('"
                    + ref
                    + "') but 'Allow external $ref resolution' is off.");
          }
        }
        rejectExternalRefs(field.getValue());
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        rejectExternalRefs(child);
      }
    }
  }

  private String hashOf(JsonNode node) {
    try {
      byte[] bytes = mapper.writeValueAsBytes(node);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot hash schema for caching", e);
    }
  }

  public int compileCacheSize() {
    return compileCache.size();
  }

  private record CachedText(String text, long fetchedAtMillis) {}
}
