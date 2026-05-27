package io.camunda.connector.jsonschema.integration;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ElementSelectors.byId;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = {TestConnectorRuntimeApplication.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@CamundaSpringProcessTest
public class JsonSchemaValidatorConnectorIntegrationTest {

  private static final Map<String, Object> SCHEMA =
      Map.of(
          "type", "object",
          "required", List.of("name", "age"),
          "properties",
              Map.of(
                  "name", Map.of("type", "string"),
                  "age", Map.of("type", "integer", "minimum", 0)));

  @Autowired private CamundaClient client;

  @Test
  void validData_reachesOkEnd() {
    var instance =
        client
            .newCreateInstanceCommand()
            .bpmnProcessId("json-schema-validator-test-process")
            .latestVersion()
            .variables(
                Map.of("data", Map.of("name", "Alice", "age", 30), "schema", SCHEMA))
            .send()
            .join();

    assertThatProcessInstance(instance).hasCompletedElements(byId("OkEnd")).isCompleted();
  }

  @Test
  void invalidData_takesBoundaryErrorPath() {
    var instance =
        client
            .newCreateInstanceCommand()
            .bpmnProcessId("json-schema-validator-test-process")
            .latestVersion()
            .variables(Map.of("data", Map.of("name", "Alice"), "schema", SCHEMA))
            .send()
            .join();

    assertThatProcessInstance(instance)
        .hasCompletedElements(byId("ValidationFailedEnd"))
        .isCompleted();
  }
}
