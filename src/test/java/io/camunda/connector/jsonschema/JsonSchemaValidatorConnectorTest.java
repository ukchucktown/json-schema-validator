package io.camunda.connector.jsonschema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.jsonschema.internal.SchemaService;
import io.camunda.connector.jsonschema.model.ValidationError;
import io.camunda.connector.jsonschema.model.ValidationResult;
import io.camunda.connector.runtime.core.outbound.operation.ConnectorOperations;
import io.camunda.connector.runtime.core.outbound.operation.OutboundConnectorOperationFunction;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.camunda.connector.validation.impl.DefaultValidationProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonSchemaValidatorConnectorTest {

  private static final Map<String, Object> PERSON_SCHEMA =
      Map.of(
          "type", "object",
          "required", List.of("name", "age"),
          "properties",
              Map.of(
                  "name", Map.of("type", "string"),
                  "age", Map.of("type", "integer", "minimum", 0),
                  "email", Map.of("type", "string", "format", "email")));

  private SchemaService schemas;
  private OutboundConnectorFunction function;

  @BeforeAll
  static void disableSsrfGuard() {
    System.setProperty("connector.jsonschema.ssrfGuard.disabled", "true");
  }

  @AfterAll
  static void enableSsrfGuard() {
    System.clearProperty("connector.jsonschema.ssrfGuard.disabled");
  }

  @BeforeEach
  void setUp() {
    schemas = new SchemaService();
    var connector = new JsonSchemaValidatorConnector(schemas);
    var operations =
        ConnectorOperations.from(connector, new ObjectMapper(), new DefaultValidationProvider());
    function = new OutboundConnectorOperationFunction(operations);
  }

  @Test
  void validData_returnsValidResult() {
    var data = Map.of("name", "Alice", "age", 30);
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isTrue();
    assertThat(result.errorCount()).isZero();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void invalidData_missingRequired() {
    var data = Map.of("name", "Alice");
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).extracting(ValidationError::keyword).contains("required");
  }

  @Test
  void invalidData_typeMismatch() {
    var data = Map.of("name", "Alice", "age", "thirty");
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).extracting(ValidationError::keyword).contains("type");
    assertThat(result.errors()).extracting(ValidationError::path).contains("/age");
  }

  @Test
  void invalidData_minimum() {
    var data = Map.of("name", "Alice", "age", -1);
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).extracting(ValidationError::keyword).contains("minimum");
  }

  @Test
  void invalidData_formatEmail() {
    var data = Map.of("name", "Alice", "age", 30, "email", "not-an-email");
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).extracting(ValidationError::keyword).contains("format");
    assertThat(result.errors()).extracting(ValidationError::path).contains("/email");
  }

  @Test
  void invalidData_collectsMultipleErrors() {
    var data = Map.of("age", -1, "email", "nope");
    var result = invoke(inline(data, PERSON_SCHEMA));
    assertThat(result.valid()).isFalse();
    assertThat(result.errorCount()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void inlineSchema_asJsonString_works() {
    var schemaJson = "{\"type\":\"object\",\"required\":[\"x\"]}";
    var vars = base();
    vars.put("data", Map.of("x", 1));
    vars.put("schemaSource", "inline");
    vars.put("schema", schemaJson);
    var result = invoke(vars);
    assertThat(result.valid()).isTrue();
  }

  @Test
  void urlSchema_happyPath() throws IOException {
    try (var server = startStubServer("/person", schemaJsonBody(), 200)) {
      var url = "http://127.0.0.1:" + server.port() + "/person";
      var vars = base();
      vars.put("data", Map.of("name", "Alice", "age", 30));
      vars.put("schemaSource", "url");
      vars.put("schemaUrl", url);
      var result = invoke(vars);
      assertThat(result.valid()).isTrue();
    }
  }

  @Test
  void urlSchema_non2xx_throwsConnectorException() throws IOException {
    try (var server = startStubServer("/person", "not found", 404)) {
      var url = "http://127.0.0.1:" + server.port() + "/person";
      var vars = base();
      vars.put("data", Map.of("name", "Alice", "age", 30));
      vars.put("schemaSource", "url");
      vars.put("schemaUrl", url);
      assertThatThrownBy(() -> invoke(vars))
          .isInstanceOf(ConnectorException.class)
          .hasFieldOrPropertyWithValue("errorCode", "SCHEMA_FETCH_FAILED");
    }
  }

  @Test
  void urlSchema_invalidJson_throwsConnectorException() throws IOException {
    try (var server = startStubServer("/person", "not json at all {{{", 200)) {
      var url = "http://127.0.0.1:" + server.port() + "/person";
      var vars = base();
      vars.put("data", Map.of("name", "Alice", "age", 30));
      vars.put("schemaSource", "url");
      vars.put("schemaUrl", url);
      assertThatThrownBy(() -> invoke(vars))
          .isInstanceOf(ConnectorException.class)
          .hasFieldOrPropertyWithValue("errorCode", "BAD_SCHEMA");
    }
  }

  @Test
  void externalRefDisabled_rejectsExternalRef() {
    var schemaWithExternalRef = Map.of("$ref", "https://example.com/common.json#/Person");
    var vars = base();
    vars.put("data", Map.of());
    vars.put("schemaSource", "inline");
    vars.put("schema", schemaWithExternalRef);
    vars.put("allowExternalRefs", false);
    assertThatThrownBy(() -> invoke(vars))
        .isInstanceOf(ConnectorException.class)
        .hasFieldOrPropertyWithValue("errorCode", "EXTERNAL_REFS_DISABLED");
  }

  @Test
  void sameDocumentRef_allowedWhenExternalRefsDisabled() {
    var schema =
        Map.of(
            "$defs",
                Map.of("name", Map.of("type", "string")),
            "type", "object",
            "properties", Map.of("name", Map.of("$ref", "#/$defs/name")));
    var vars = base();
    vars.put("data", Map.of("name", "Alice"));
    vars.put("schemaSource", "inline");
    vars.put("schema", schema);
    vars.put("allowExternalRefs", false);
    var result = invoke(vars);
    assertThat(result.valid()).isTrue();
  }

  @Test
  void malformedInlineSchemaString_throwsConnectorException() {
    var vars = base();
    vars.put("data", Map.of());
    vars.put("schemaSource", "inline");
    vars.put("schema", "this is not json");
    assertThatThrownBy(() -> invoke(vars))
        .isInstanceOf(ConnectorException.class)
        .hasFieldOrPropertyWithValue("errorCode", "BAD_SCHEMA");
  }

  @Test
  void missingInlineSchema_throwsConnectorException() {
    var vars = base();
    vars.put("data", Map.of());
    vars.put("schemaSource", "inline");
    assertThatThrownBy(() -> invoke(vars))
        .isInstanceOf(ConnectorException.class)
        .hasFieldOrPropertyWithValue("errorCode", "MISSING_SCHEMA");
  }

  @Test
  void schemaCache_sameContentReusesCompiledValidator() {
    var data = Map.of("name", "Alice", "age", 30);
    invoke(inline(data, PERSON_SCHEMA));
    int afterFirst = schemas.compileCacheSize();
    invoke(inline(data, PERSON_SCHEMA));
    int afterSecond = schemas.compileCacheSize();
    assertThat(afterFirst).isEqualTo(1);
    assertThat(afterSecond).isEqualTo(1);
  }

  // ---------- helpers ----------

  private ValidationResult invoke(Map<String, Object> variables) {
    Object out = rawInvoke(variables);
    assertThat(out).isInstanceOf(ValidationResult.class);
    return (ValidationResult) out;
  }

  private Object rawInvoke(Map<String, Object> variables) {
    var context =
        OutboundConnectorContextBuilder.create()
            .variables(variables)
            .header("operation", "validate")
            .build();
    try {
      return function.execute(context);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Object> base() {
    return new HashMap<>();
  }

  private static Map<String, Object> inline(Object data, Object schema) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("data", data);
    v.put("schemaSource", "inline");
    v.put("schema", schema);
    return v;
  }

  private static String schemaJsonBody() {
    try {
      return new ObjectMapper().writeValueAsString(PERSON_SCHEMA);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private StubServer startStubServer(String path, String body, int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, payload.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
          }
        });
    server.start();
    return new StubServer(server);
  }

  private record StubServer(HttpServer server) implements AutoCloseable {
    int port() {
      return server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
