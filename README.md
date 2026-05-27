# JSON Schema Validator — Camunda 8 Connector

A custom outbound connector for Camunda 8 that validates a JSON value from
your process against a [JSON Schema](https://json-schema.org/) and returns a
structured result you can branch on.

Built with the Camunda Connectors SDK 8.9 and
[networknt/json-schema-validator](https://github.com/networknt/json-schema-validator).
Supports JSON Schema drafts 04 / 06 / 07 / 2019-09 / 2020-12 — auto-detected
from the schema's `$schema` keyword.

---

## What it does

One operation: **`validate`**. Given a JSON value (typically a process
variable) and a schema (inline or URL-loaded), it returns:

```json
{
  "valid": false,
  "errorCount": 2,
  "errors": [
    {
      "path": "/order/items/0/quantity",
      "keyword": "minimum",
      "message": "must have a minimum value of 1",
      "schemaPath": "https://json-schema.org/.../properties/items/items/properties/quantity/minimum"
    },
    {
      "path": "/order/customerEmail",
      "keyword": "format",
      "message": "must be a valid email",
      "schemaPath": "..."
    }
  ]
}
```

The connector itself never throws on invalid data — it always returns the
result. Decide downstream whether validation failure is a normal branch (XOR
gateway) or an exception (BPMN error via an `errorExpression` task header).

---

## Installation

### 1. Element template — make the Modeler aware of the connector

Build the project to generate the template:

```bash
mvn clean package
```

The template lands at `element-templates/json-schema-validator.json`.

**Desktop Modeler**: Preferences → Element Templates → Add directory →
point at `element-templates/`. The Modeler picks up the template on diagram
reopen.

**Web Modeler**: in your project, **Create new → Upload files** → select
`json-schema-validator.json` → **Publish**.

### 2. Connector runtime — make the connector executable

The shaded jar is at `target/json-schema-validator-0.1.0-SNAPSHOT.jar`.

**Self-managed**: drop the jar into the connector runtime's
`/opt/camunda/connectors/` (or equivalent), restart.

**Local development**: use the bundled
[`LocalConnectorRuntime`](src/test/java/io/camunda/connector/jsonschema/LocalConnectorRuntime.java).
Point `src/test/resources/application.properties` at your local Zeebe, then:

```bash
mvn test-compile exec:java \
  -Dexec.mainClass=io.camunda.connector.jsonschema.LocalConnectorRuntime \
  -Dexec.classpathScope=test
```

**SaaS**: not yet supported as a managed runtime — use the Hybrid /
self-managed runtime pattern (deploy the jar to your own connector runtime
pointed at a SaaS cluster).

---

## Usage

After applying the **JSON Schema Validator** template to a service task, the
properties panel shows two groups.

### Input

| Property | Description | Example |
|---|---|---|
| **Data** | The JSON value to validate. FEEL expression that resolves to a Map / List / scalar. | `=order` |

### Schema

| Property | Description |
|---|---|
| **Schema source** | Dropdown: `Inline` or `URL`. |
| **Schema** *(when source = inline)* | The JSON Schema as a FEEL expression returning the schema object, or a pasted JSON literal. |
| **Schema URL** *(when source = url)* | HTTPS URL pointing at a JSON Schema document. |
| **Allow external `$ref` resolution** | Off (default): only same-document `#/...` refs allowed. On: nested HTTP(S) refs are followed. |

### Output

The connector writes a [`ValidationResult`](src/main/java/io/camunda/connector/jsonschema/model/ValidationResult.java)
into the process variable named in the standard **Result variable** field
(default `validationResult`).

```feel
validationResult.valid          // boolean
validationResult.errorCount     // number
validationResult.errors[1]      // first error (FEEL is 1-indexed)
validationResult.summaryMessage // human-readable summary
```

### Branching on the result

The natural BPMN pattern is an XOR gateway on
`=validationResult.valid` — one path for valid, one for invalid. See
[`src/test/resources/bpmn/operation-connector-test-process.bpmn`](src/test/resources/bpmn/operation-connector-test-process.bpmn)
for a working example.

### Raising a BPMN error on invalid data

If you want validation failure to escape as a BPMN error catchable by a
boundary event, add an `errorExpression` task header on the service task:

```feel
=if not(validationResult.valid)
  then bpmnError("SCHEMA_VALIDATION_FAILED", validationResult.summaryMessage)
  else null
```

The runtime evaluates `errorExpression` after the connector returns; a
non-null result becomes a BPMN error.

---

## Security

### `$ref` resolution policy

By default, only **same-document** refs (`#/$defs/Address`) are allowed.
Schemas containing external refs (`https://schemas.example.com/...`,
`file://...`, relative paths) are rejected at compile time with
`EXTERNAL_REFS_DISABLED`.

To allow external refs, enable **Allow external `$ref` resolution** on the
service task. Then HTTP(S) refs are fetched. `file://` is never allowed.

### SSRF guard

When the **Schema URL** source is used, the URL is checked before fetch:

- `file://` is rejected.
- Any host that resolves to a loopback (`127.0.0.0/8`, `::1`), link-local,
  private RFC1918 (`10/8`, `172.16/12`, `192.168/16`), AWS metadata
  (`169.254.169.254`), or multicast address is rejected with
  `SSRF_BLOCKED`.

This is intentional — without it, a user-controlled schema URL becomes an
internal-network probe.

If your schema registry lives inside a private network, disable the guard at
the runtime level:

```bash
# JVM system property
-Dconnector.jsonschema.ssrfGuard.disabled=true

# or environment variable
CONNECTOR_JSONSCHEMA_SSRF_GUARD_DISABLED=true
```

The guard is **off** by default for local-network schema registries, **on**
by default for any internet-facing connector runtime — chosen by the
operator, not by the process author.

> **Note**: the SSRF guard runs only against the root `Schema URL`. Nested
> `$ref`s fetched after the toggle is on are not re-checked. Only enable
> external refs against schema sources you trust.

---

## Caching

To avoid recompiling and refetching the same schema on every job:

- **Compile cache**: keyed by SHA-256 of the schema content. Same schema
  bytes → same compiled validator. No eviction (cardinality is naturally
  bounded by deployed processes). Persists for the lifetime of the JVM.
- **URL fetch cache**: keyed by URL. 5-minute TTL — long enough to absorb
  high-throughput processes, short enough that schema updates eventually
  propagate. Restart the runtime to force a refresh sooner.

Both caches live in plain `ConcurrentHashMap`s — no Caffeine or other
caching library, no extra jars.

---

## Errors

Errors that surface as `ConnectorException` (→ incidents in Operate):

| Code | When |
|---|---|
| `MISSING_SCHEMA` | Schema source is `inline` but the **Schema** field is empty. |
| `MISSING_SCHEMA_URL` | Schema source is `url` but the **Schema URL** field is empty. |
| `BAD_SCHEMA` | Inline schema is not valid JSON, or the URL returned non-JSON, or the schema is structurally invalid. |
| `BAD_SCHEMA_SOURCE` | The **Schema source** value is neither `inline` nor `url`. |
| `BAD_SCHEMA_URL` | The **Schema URL** has no scheme, a bad scheme, or an unresolvable host. |
| `SCHEMA_FETCH_FAILED` | HTTP fetch returned non-2xx, timed out, or threw I/O. |
| `SSRF_BLOCKED` | Schema URL resolved to a blocked address. |
| `EXTERNAL_REFS_DISABLED` | Schema contains a non-`#` `$ref` and external resolution is off. |

These represent **operational** failures (misconfiguration, registry
outage). They are not retried by the runtime — a human resolves the
incident.

Errors in the data being validated (the normal "this payload is invalid"
case) do **not** raise an exception. They populate the `errors` array on
the result.

---

## Building from source

Requires Java 21 and Maven 3.9+.

```bash
mvn clean verify
```

Produces:
- `target/json-schema-validator-0.1.0-SNAPSHOT.jar` — shaded jar with
  Jackson and networknt relocated under `io.camunda.connector.jsonschema.shaded.*`
- `element-templates/json-schema-validator.json` — regenerated element
  template

The element template is **always regenerated** from `@ElementTemplate` /
`@TemplateProperty` annotations on the Java source. Do not edit it by
hand — your changes will be wiped on the next build.

### Tests

```bash
mvn test
```

- 15 unit tests covering keyword variety, multi-error collection, URL
  source happy/sad paths, `$ref` policy, cache reuse, malformed inputs.
- 4 SSRF guard tests.
- 2 integration tests via `@CamundaSpringProcessTest` spinning up an
  embedded Camunda + connector runtime.

---

## Compatibility

| Component | Version |
|---|---|
| Camunda Connectors SDK | 8.9.x |
| Camunda runtime | 8.9.x (self-managed or SaaS) |
| Java | 21 |
| JSON Schema drafts | 04 / 06 / 07 / 2019-09 / 2020-12 (default 2020-12) |

The SDK's minor must match the connector runtime's minor — running an 8.9
connector jar in an 8.8 runtime surfaces as deserialization errors or
"unknown header" incidents.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
