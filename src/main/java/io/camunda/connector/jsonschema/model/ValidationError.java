package io.camunda.connector.jsonschema.model;

public record ValidationError(String path, String keyword, String message, String schemaPath) {}
