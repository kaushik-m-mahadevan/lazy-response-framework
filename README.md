# Lazy Response Framework

A Spring Boot library that turns a complex aggregation API into a DAG-driven, parallel, template-filtered response - with zero orchestration code in your business logic.

---

## The Problem

Most aggregation APIs fetch everything on every request and let the caller pick what it needs. This means:

- A checkout page that needs only `payment` and `shipment` still triggers all six downstream calls
- Downstream services are called sequentially by default - latency compounds
- Every new client use case means a new endpoint, or a fatter response nobody fully uses

## The Solution

The client declares a **template** - the exact fields it wants. The framework builds only the necessary execution path from a pre-compiled dependency graph, runs independent branches in parallel, and skips everything else. If one downstream fails, you still get the rest of the response with a structured error record.

```
Client sends:
{
  "request":  { "orderId": "ORD-001", "accountId": "ACC-001" },
  "template": { "payment": null, "shipment": null }
}

Framework executes:
  order (required parent) → payment
                          → shipment

Framework skips:
  account, loyalty, inventory  (not needed for this template)
```

---

## Proven Latency Improvement

For a 6-downstream order detail API with real network latency simulation:

```
Sequential (no framework) : 1010ms   (sum of all downstream latencies)
Lazy parallel (framework) :  ~520ms  (bottleneck of each parallel stage)
Speedup                   :  ~1.93x
```

Run this yourself:
```powershell
mvn test -pl lazy-response-demo -Dtest=LatencyComparisonTest
```

---

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.lazyresponse</groupId>
    <artifactId>lazy-response-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

No `@EnableSomething` needed. The framework registers itself via Spring Boot autoconfiguration.

### 2. Write your downstreams

```java
@Service
public class OrderDetailDownstreams {

    @Downstream(
        id        = "order",
        fields    = {"id", "status", "total"},
        chainTimeout = 2000
    )
    public OrderResponse fetchOrder(ExecutionContext ctx) {
        OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
        return orderService.fetch(req.getOrderId());
    }

    @Downstream(
        id        = "payment",
        fields    = {"status", "method", "amount"},
        dependsOn = {"order"},
        timeout   = 1500,
        defaults  = {
            @Default(field = "status", value = "unknown")
        }
    )
    public PaymentResponse fetchPayment(ExecutionContext ctx) {
        OrderResponse order = ctx.get("order", OrderResponse.class);
        return paymentService.fetch(order.getId());
    }
}
```

### 3. Annotate your controller

```java
@LazyAggregator
@RequestMapping("/api/orders")
public class OrderDetailController {

    @LazyResponse(downstreams = {OrderDetailDownstreams.class})
    @PostMapping("/detail")
    public ResponseEntity<?> getOrderDetail(OrderDetailRequest request) {
        return null; // Framework-owned. This body never executes.
    }
}
```

### 4. Call it

```json
POST /api/orders/detail
{
  "request":  { "orderId": "ORD-001" },
  "template": {
    "order":   null,
    "payment": null
  }
}
```

Response:
```json
{
  "data": {
    "order":   { "id": "ORD-001", "status": "SHIPPED", "total": 4299.00 },
    "payment": { "status": "PAID", "method": "CARD", "amount": 4299.00 }
  },
  "meta": {
    "partial":  false,
    "errors":   [],
    "warnings": []
  }
}
```

---

## Key Concepts

### Dependency Graph (DAG)

Downstreams declare their dependencies via `dependsOn`. The framework compiles these into a directed acyclic graph at startup. Independent nodes run in parallel. Dependent nodes wait only for their direct parents.

```
account  ──→  loyalty
order    ──→  payment
         ──→  shipment
         ──→  inventory
```

If the graph has a cycle, the application refuses to start with a descriptive error.

### ExecutionContext

Every downstream receives an `ExecutionContext`. Use it to:

```java
// Get the original HTTP request body
OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);

// Get the result of a completed parent downstream
OrderResponse order = ctx.get("order", OrderResponse.class);
```

### Template

The client controls what gets executed. Requesting `payment` automatically pulls in `order` (its parent) even if `order` is not in the template. Transitive dependencies are resolved silently. Unknown keys are dropped silently - no error.

### Failure Handling

Two strategies, set in config:

**`silent` (default):** Failed downstreams return `@Default` values for fields that have them, `null` for fields that don't. `meta.partial` is set to `true`. The request always returns 200.

**`fail-fast`:** Any downstream failure aborts the entire request immediately and returns an error response.

### Meta Block

Every response includes a `meta` block:

```json
"meta": {
  "partial":  true,
  "errors": [
    { "downstream": "payment", "field": "amount", "reason": "exception", "elapsedMs": 12 }
  ],
  "warnings": [
    { "downstream": "loyalty", "elapsedMs": 2800, "threshold": 2000 }
  ]
}
```

- `errors` - downstreams that failed (exception or timeout), per field
- `warnings` - downstreams that succeeded but exceeded the soft warning threshold

---

## Configuration

All settings have sensible defaults. Override only what you need:

```yaml
lazy-response:
  failure-strategy: silent       # silent | fail-fast
  timeout:
    global: 3000                 # ms - floor timeout for all downstreams
    warning-threshold: 0         # ms - soft threshold for meta.warnings (0 = disabled)
  executor:
    pool-size: 20                # thread pool size
  graph:
    enabled: true                # set false to hide /lazy/graph in production
```

---

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /lazy/graph` | Live visualisation of the registered dependency graph |
| `GET /actuator/health` | Health indicator showing framework status |

---

## Running the Demo

```powershell
# Start the demo app
mvn spring-boot:run -pl lazy-response-demo

# Run the benchmark (proves parallel speedup)
mvn test -pl lazy-response-demo -Dtest=LatencyComparisonTest

# Run all tests
mvn test
```

See `docs/commands.md` for the full command reference.

---

## Design Decisions

**Why AOP?** The consumer writes zero orchestration code. Business logic stays in downstream methods. Removing the framework is as simple as removing the annotations and wiring the methods yourself.

**Why a sealed registry?** The DAG is validated once at startup. If the app boots, the graph is valid - forever. Dynamic registration would require re-validating under live traffic with no safe recovery path if a cycle is introduced.

**Why a semaphore?** A thread pool alone can deadlock when stage N needs more threads than are currently free and stage N-1 threads are blocking waiting for N to complete. Acquiring `maxWidth` permits upfront guarantees the widest stage can always start.

**Why BeanPostProcessor for discovery?** Classpath scanning finds raw classes. BeanPostProcessor gives you the live Spring bean - proxied, decorated, and ready to invoke. This ensures `@Transactional`, `@Cacheable`, and Feign client downstreams all work correctly.

---

## Project Structure

```
lazy-response-framework/
├── lazy-response-core/     # The framework library
│   └── src/main/java/com/lazyresponse/
│       ├── annotation/     # @LazyResponse, @Downstream, @Default, @LazyAggregator
│       ├── interceptor/    # LazyResponseAspect (AOP entry point)
│       ├── executor/       # LazyResponseOrchestrator, ExecutionPlanner
│       ├── graph/          # DependencyGraph, LazyGraphController
│       ├── registry/       # DownstreamRegistry, DownstreamRegistryBeanPostProcessor
│       ├── context/        # ExecutionContext
│       └── autoconfigure/ # LazyResponseAutoConfiguration
├── lazy-response-demo/     # Demo app + integration tests
└── docs/
    └── commands.md         # Command reference
```
