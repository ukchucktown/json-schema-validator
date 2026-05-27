package io.camunda.connector.jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import io.camunda.connector.api.annotation.Operation;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.annotation.Variable;
import io.camunda.connector.api.outbound.OutboundConnectorProvider;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import io.camunda.connector.jsonschema.internal.SchemaService;
import io.camunda.connector.jsonschema.model.ValidateRequest;
import io.camunda.connector.jsonschema.model.ValidationError;
import io.camunda.connector.jsonschema.model.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OutboundConnector(name = "JSON Schema Validator", type = "io.camunda:json-schema-validator:1")
@ElementTemplate(
    id = "io.camunda.connector.json-schema-validator.v1",
    name = "JSON Schema Validator",
    version = 1,
    description = "Validates a JSON value against a JSON Schema.",
    icon = "icon.svg",
    documentationRef =
        "https://docs.camunda.io/docs/components/connectors/custom-built-connectors/connector-sdk/",
    defaultResultVariable = "validationResult",
    propertyGroups = {
      @PropertyGroup(id = "input", label = "Input"),
      @PropertyGroup(id = "schema", label = "Schema")
    })
public class JsonSchemaValidatorConnector implements OutboundConnectorProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(JsonSchemaValidatorConnector.class);

  private final SchemaService schemaService;

  public JsonSchemaValidatorConnector() {
    this(new SchemaService());
  }

  public JsonSchemaValidatorConnector(SchemaService schemaService) {
    this.schemaService = schemaService;
  }

  @Operation(id = "validate", name = "Validate")
  public ValidationResult validate(@Variable ValidateRequest request) {
    JsonNode data = schemaService.toNode(request.data());
    JsonSchema schema = schemaService.loadSchema(request);

    Set<ValidationMessage> messages = schema.validate(data);
    ValidationResult result = toResult(messages);

    LOGGER.debug(
        "Validation complete: valid={}, errorCount={}", result.valid(), result.errorCount());

    return result;
  }

  private static ValidationResult toResult(Set<ValidationMessage> messages) {
    if (messages.isEmpty()) {
      return ValidationResult.success();
    }
    List<ValidationError> errors = new ArrayList<>(messages.size());
    for (ValidationMessage m : messages) {
      errors.add(
          new ValidationError(
              m.getInstanceLocation() == null ? "" : m.getInstanceLocation().toString(),
              m.getType(),
              m.getMessage(),
              m.getSchemaLocation() == null ? null : m.getSchemaLocation().toString()));
    }
    return ValidationResult.failure(errors);
  }
}
