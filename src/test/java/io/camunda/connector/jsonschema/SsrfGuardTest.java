package io.camunda.connector.jsonschema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.jsonschema.internal.SsrfGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SsrfGuardTest {

  @AfterEach
  void restore() {
    System.clearProperty("connector.jsonschema.ssrfGuard.disabled");
  }

  @Test
  void rejectsFileScheme() {
    assertThatThrownBy(() -> SsrfGuard.checkUrl("file:///etc/passwd"))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("file://");
  }

  @Test
  void rejectsLoopbackByDefault() {
    assertThatThrownBy(() -> SsrfGuard.checkUrl("http://127.0.0.1/schema"))
        .isInstanceOf(ConnectorException.class)
        .hasFieldOrPropertyWithValue("errorCode", "SSRF_BLOCKED");
  }

  @Test
  void allowsLoopbackWhenGuardDisabled() {
    System.setProperty("connector.jsonschema.ssrfGuard.disabled", "true");
    assertThatCode(() -> SsrfGuard.checkUrl("http://127.0.0.1/schema")).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingScheme() {
    assertThatThrownBy(() -> SsrfGuard.checkUrl("example.com/schema"))
        .isInstanceOf(ConnectorException.class);
  }
}
