# Lazy Response Framework - Command Reference

---

## Running Tests

### Run all tests
```powershell
mvn test
```

### Run all tests in the demo module only
```powershell
mvn test -pl lazy-response-demo
```

### Run all tests in the core module only
```powershell
mvn test -pl lazy-response-core
```

### Run a specific test class
```powershell
mvn test -pl lazy-response-demo -Dtest=LatencyComparisonTest
mvn test -pl lazy-response-demo -Dtest=OrderDetailEndpointTest
mvn test -pl lazy-response-demo -Dtest=ConcurrentRequestTest
```

### Run multiple specific test classes
```powershell
mvn test -pl lazy-response-demo -Dtest="OrderDetailEndpointTest,OrderDetailFailFastEndpointTest"
```

---

## The Benchmark (Interview Demo)

Proves parallel execution is ~2x faster than sequential.
Expected output: sequential ~1010ms, actual lazy ~480-550ms, speedup ~1.9x.

```powershell
mvn test -pl lazy-response-demo -Dtest=LatencyComparisonTest
```

---

## Building

### Build everything (skip tests)
```powershell
mvn clean package -DskipTests
```

### Build and run all tests
```powershell
mvn clean verify
```

### Install core to local Maven repository (required if demo can't find core)
```powershell
mvn clean install -pl lazy-response-core
```

---

## Running the Demo App

### Start the demo application
```powershell
mvn spring-boot:run -pl lazy-response-demo
```

### Once running, useful endpoints:
| Endpoint | What it does |
|---|---|
| `POST http://localhost:8080/api/orders/detail` | The lazy endpoint |
| `GET  http://localhost:8080/lazy/graph` | Live DAG visualisation |
| `GET  http://localhost:8080/swagger-ui.html` | OpenAPI docs |
| `GET  http://localhost:8080/actuator/health` | Health check |

### Sample request (all fields)
```powershell
curl -X POST http://localhost:8080/api/orders/detail `
  -H "Content-Type: application/json" `
  -d '{
    "request": { "orderId": "ORD-001", "accountId": "ACC-001" },
    "template": {
      "order":     null,
      "account":   null,
      "payment":   null,
      "shipment":  null,
      "inventory": null,
      "loyalty":   null
    }
  }'
```

### Sample request (partial template - order and payment only)
```powershell
curl -X POST http://localhost:8080/api/orders/detail `
  -H "Content-Type: application/json" `
  -d '{
    "request": { "orderId": "ORD-001", "accountId": "ACC-001" },
    "template": {
      "order":   null,
      "payment": null
    }
  }'
```

### Trigger failure scenarios
```powershell
# Payment downstream throws (silent failure demo)
"orderId": "ORD-FAIL"

# Payment downstream times out after 1500ms
"orderId": "ORD-SLOW"

# Loyalty downstream throws (no loyalty record)
"accountId": "ACC-NOLOY"
```

---

## Git

### Check what is staged before committing
```powershell
git status --short
```

### Stage source files only (never stage target/)
```powershell
git add .gitignore
git add lazy-response-core/src/
git add lazy-response-demo/src/
```

### Untrack compiled artifacts (if they sneak in)
```powershell
git ls-files --cached | Select-String "target/" | ForEach-Object { git rm --cached $_.ToString().Trim() }
```

---

## Useful Maven Flags

| Flag | What it does |
|---|---|
| `-DskipTests` | Skip test execution |
| `-pl lazy-response-demo` | Run only in the demo module |
| `-pl lazy-response-core` | Run only in the core module |
| `-Dtest=ClassName` | Run a specific test class |
| `-X` | Debug output (verbose) |
| `clean` | Delete target/ before building |
