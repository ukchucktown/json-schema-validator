package io.camunda.connector.jsonschema.model;

import java.util.List;

public record ValidationResult(boolean valid, int errorCount, List<ValidationError> errors) {

  public static ValidationResult success() {
    return new ValidationResult(true, 0, List.of());
  }

  public static ValidationResult failure(List<ValidationError> errors) {
    return new ValidationResult(false, errors.size(), errors);
  }

  public String summaryMessage() {
    if (valid) {
      return "Validation succeeded";
    }
    String first =
        errors.isEmpty()
            ? ""
            : " (first: " + errors.getFirst().path() + " — " + errors.getFirst().message() + ")";
    return "Validation failed: " + errorCount + " error" + (errorCount == 1 ? "" : "s") + first;
  }
}
