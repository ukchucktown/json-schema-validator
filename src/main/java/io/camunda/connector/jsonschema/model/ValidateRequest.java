package io.camunda.connector.jsonschema.model;

import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyCondition;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotEmpty;

public record ValidateRequest(
    @TemplateProperty(
        group = "input",
        label = "Data",
        description =
            "The JSON value to validate. A FEEL expression returning the value, or a literal.",
        type = PropertyType.String,
        feel = FeelMode.optional,
        defaultValue = "=data")
        Object data,
    @NotEmpty
    @TemplateProperty(
        group = "schema",
        label = "Schema source",
        description = "Where the JSON Schema comes from.",
        type = PropertyType.Dropdown,
        choices = {
          @DropdownPropertyChoice(label = "Inline", value = "inline"),
          @DropdownPropertyChoice(label = "URL", value = "url")
        },
        defaultValue = "inline")
        String schemaSource,
    @TemplateProperty(
        group = "schema",
        label = "Schema",
        description =
            "Inline JSON Schema. A FEEL expression returning the schema object, or a pasted JSON literal.",
        type = PropertyType.Text,
        feel = FeelMode.optional,
        optional = true,
        condition = @PropertyCondition(property = "schemaSource", equals = "inline"))
        Object schema,
    @TemplateProperty(
        group = "schema",
        label = "Schema URL",
        description = "HTTP(S) URL pointing to the JSON Schema document.",
        type = PropertyType.String,
        optional = true,
        condition = @PropertyCondition(property = "schemaSource", equals = "url"))
        String schemaUrl,
    @TemplateProperty(
        group = "schema",
        label = "Allow external $ref resolution",
        description =
            "If on, $refs to other documents are followed. Default off — same-document refs only.",
        type = PropertyType.Boolean,
        defaultValue = "false",
        defaultValueType = DefaultValueType.Boolean,
        optional = true)
        Boolean allowExternalRefs) {}
