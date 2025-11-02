# Load Balancer with Configurable REST API Backend

A production-ready load balancer implementation in Kotlin demonstrating Domain-Driven Design principles, complete observability with OpenTelemetry, and runtime-configurable backend services for testing resilience patterns.

## 🎯 Features

### Load Balancer (`com.sahmad.loadbalancer`)
- ✅ **Load Balancing Strategies**: Round Robin & Weighted Round Robin
- ✅ **Circuit Breaker**: 3-state pattern (CLOSED → OPEN → HALF_OPEN)
- ✅ **Health Monitoring**: Active health checks every 10 seconds
- ✅ **Resilience**: Timeouts, retries, graceful degradation
- ✅ **Domain-Driven Design**: Clear bounded contexts, aggregates, value objects
- ✅ **Structured Logging**: JSON logs with Logstash Logback Encoder
- ✅ **Distributed Tracing**: Automatic trace propagation with OpenTelemetry
- ✅ **Custom Metrics**: Business-specific counters for monitoring

### REST API Backend (`com.sahmad.restapi`)
- ✅ **Runtime Configurable Delay**: Test slow backend scenarios
- ✅ **Single Endpoint**: `POST /{path...}` accepts any path
- ✅ **Echo Response**: Returns request details with metadata
- ✅ **Structured Logging**: JSON logs with trace context
- ✅ **Health Check**: Standard `/health` endpoint
- ✅ **Fixed Port**: 8080 (Docker maps to different host ports)

### Observability Stack
- ✅ **OpenTelemetry Collector**: Receives telemetry from all services
- ✅ **Grafana**: Unified dashboard (http://localhost:3000)
- ✅ **Loki**: Log aggregation with label-based filtering
- ✅ **Prometheus**: Metrics collection and querying
- ✅ **Tempo**: Distributed tracing storage

## 📋 Prerequisites

- **Java 21** (JDK 21 or higher)
- **Docker & Docker Compose**
- **curl** (for testing)

Verify Java version:
```bash
java -version
# Should show version 21 or higher
```

## 🚀 Quick Start

### Step 1: Clone and Navigate
```bash
cd $PWD  # Or wherever you cloned the repo
```

### Step 2: Download OpenTelemetry Java Agent
```bash
# Download the latest OTel Java Agent
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar

# Verify download
ls -lh opentelemetry-javaagent.jar
```

### Step 3: Build REST API Docker Image First
```bash
# Build Docker image using Ktor's Jib plugin (needed before starting docker-compose)
cd rest-api && \
./gradlew buildImage --no-configuration-cache && \
cd ..
```

# Verify the image was created
```bash
docker images | grep rest-api
# You should see: rest-api   latest   ...
```

### Step 4: Build Projects
```bash
# Build Load Balancer
cd load-balancer
./gradlew clean build
cd ..

# REST API already built in Step 3
```

### Step 5: Start All Services

```bash
# Start everything: observability stack + 3 backend instances
docker-compose down  # Stop any existing services
docker-compose up -d

# Verify all services are running
docker-compose ps

# Services will be available at:
# - Grafana:      http://localhost:3000
# - Prometheus:   http://localhost:9090
# - Loki:         http://localhost:3100
# - Tempo:        http://localhost:3200
# - OTLP gRPC:    localhost:4317
# - Backend 1:    http://localhost:9001
# - Backend 2:    http://localhost:9002
# - Backend 3:    http://localhost:9003
```

### Step 6: Run Load Balancer

**Option 1: Using HTTP/protobuf (default, port 4318):**
```bash
cd load-balancer
java -javaagent:../opentelemetry-javaagent.jar \
  -Dotel.service.name=load-balancer \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -Dotel.logs.exporter=otlp \
  -jar build/libs/load-balancer-1.0.0.jar
```

**Option 2: Using gRPC (port 4317):**
```bash
cd load-balancer
java -javaagent:../opentelemetry-javaagent.jar \
  -Dotel.service.name=load-balancer \
  -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
  -Dotel.exporter.otlp.protocol=grpc \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=otlp \
  -Dotel.logs.exporter=otlp \
  -jar build/libs/load-balancer-1.0.0.jar
```

**Note:** The key difference is:
- HTTP: Port 4318, no protocol flag needed (or use `-Dotel.exporter.otlp.protocol=http/protobuf`)
- gRPC: Port 4317, must add `-Dotel.exporter.otlp.protocol=grpc`

### Step 7: Test the Setup

**Send a request through the load balancer:**
```bash
curl -X POST http://localhost:8080/hello \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello from client!"}'
```

**Expected response:**
```json
{
  "message": "Request processed successfully",
  "path": "/hello",
  "receivedBody": "{\"message\": \"Hello from client!\"}",
  "delayApplied": 0
}
```

### Step 8: View Telemetry in Grafana

1. Open Grafana: http://localhost:3000
2. Go to **Explore**
3. Select **Loki** as datasource
4. Query examples:

```logql
# All logs from load balancer
{service="load-balancer"}

# All logs from backends
{service=~"backend-.*"}

# Logs with trace correlation
{service="load-balancer"} | json | trace_id != ""

# Circuit breaker events
{service="load-balancer"} | json | circuit_breaker_state="OPEN"
```

## 🎮 Testing Scenarios

### Scenario 1: Normal Load Balancing
```bash
# Send 20 requests and see round-robin distribution
for i in {1..20}; do
  curl -X POST http://localhost:8080/test \
    -H "Content-Type: application/json" \
    -d "{\"request\": $i}"
  echo ""
done

# View in Loki
{service="load-balancer"} | json | component="load-balancer"
```

### Scenario 2: Configure Slow Backend
```bash
# Make backend-2 slow (6 second delay)
curl -X POST http://localhost:8080/config/delay \
  -H "Content-Type: application/json" \
  -d '{"delayMs": 6000}'

# Send request - load balancer will timeout after 5s
curl -X POST http://localhost:8080/test \
  -H "Content-Type: application/json" \
  -d '{"test": true}'

# View timeout in Loki
{service="load-balancer"} | json | node_id="node-2"

# Check circuit breaker opened
{service="load-balancer"} | json | circuit_breaker_state="OPEN"
```

### Scenario 3: Backend Recovery
```bash
# Reset backend-2 delay
curl -X POST http://localhost:8080/config/delay \
  -H "Content-Type: application/json" \
  -d '{"delayMs": 0}'

# Wait ~30 seconds for circuit breaker timeout
# Send requests - backend-2 will be tried again
for i in {1..10}; do
  curl -X POST http://localhost:8080/test -d '{"test": '$i'}'
done

# View recovery in Loki
{service="load-balancer"} | json | circuit_breaker_state="HALF_OPEN"
{service="load-balancer"} | json | circuit_breaker_state="CLOSED"
```

### Scenario 4: Distributed Tracing
```bash
# Send one request
curl -X POST http://localhost:8080/hello -d '{"trace": "test"}'

# Copy trace_id from logs or response
# Then in Grafana:
# 1. Go to Explore
# 2. Select Tempo
# 3. Search for the trace_id
# 4. You'll see:
#    - Load balancer incoming request span
#    - HTTP client outgoing request span
#    - Backend processing span

# Or query logs by trace_id in Loki:
{trace_id="<your-trace-id>"}
# This shows ALL logs from all services for that single request!
```

## 🔧 Running with Gradle (Development Mode)

### Option 1: Without OpenTelemetry Agent
```bash
# Load Balancer
cd load-balancer && ./gradlew run

# REST API
cd rest-api && ./gradlew run
```

**Note:** Without the OTel agent, you won't get automatic trace propagation, but structured logging will still work with custom MDC attributes.

### Option 2: Configure Gradle to Use OTel Agent

Add to `build.gradle.kts` in `application` block:
```kotlin
application {
    mainClass.set("com.sahmad.loadbalancer.presentation.ApplicationKt")
    
    // Add JVM arguments for OTel agent
    applicationDefaultJvmArgs = listOf(
        "-javaagent:../opentelemetry-javaagent.jar",
        "-Dotel.service.name=load-balancer",
        "-Dotel.exporter.otlp.endpoint=http://localhost:4318",
        "-Dotel.traces.exporter=otlp",
        "-Dotel.metrics.exporter=otlp",
        "-Dotel.logs.exporter=otlp"
    )
}
```

Then run:
```bash
./gradlew run
```

## 📊 API Reference

### Load Balancer Endpoints

#### Forward Request (Load Balanced)
```bash
POST http://localhost:8080/{any-path}
Content-Type: application/json

# Forwards to one of the healthy backend nodes
```

#### View All Nodes
```bash
GET http://localhost:8080/admin/nodes

# Response:
[
  {
    "id": "node-1",
    "endpoint": "http://localhost:9001",
    "weight": 1,
    "health": "HEALTHY",
    "circuitBreaker": "CLOSED",
    "activeConnections": 0
  },
  ...
]
```

#### Add Node
```bash
POST http://localhost:8080/admin/nodes
Content-Type: application/json

{
  "id": "node-4",
  "host": "localhost",
  "port": 9004,
  "weight": 2
}
```

#### Delete Node
```bash
DELETE http://localhost:8080/admin/nodes/{node-id}
```

#### View Metrics
```bash
GET http://localhost:8080/metrics

# Response includes:
# - Total nodes
# - Available nodes
# - Node health details
```

#### Health Check
```bash
GET http://localhost:8080/health
```

### REST API Backend Endpoints

#### Configure Delay
```bash
POST http://localhost:8080/config/delay
Content-Type: application/json

{
  "delayMs": 3000
}

# Response:
{
  "message": "Delay configured",
  "delayMs": 3000
}
```

#### Get Configuration
```bash
GET http://localhost:8080/config

# Response:
{
  "delayMs": 3000
}
```

#### Process Request (Any Path)
```bash
POST http://localhost:8080/{any-path}
Content-Type: application/json

{
  "your": "data"
}

# Response:
{
  "message": "Request processed successfully",
  "path": "/your-path",
  "receivedBody": "{\"your\": \"data\"}",
  "delayApplied": 3000
}
```

#### Health Check
```bash
GET http://localhost:8080/health
```

## 🐳 Managing Services with Docker Compose

All services (observability stack + backends) are now in the main `docker-compose.yaml` file.

### Start All Services
```bash
docker-compose up -d
```

### Stop All Services
```bash
docker-compose down
```

### View Logs
```bash
# All services
docker-compose logs -f

# Specific service
docker logs backend-1 -f
docker logs otel-collector -f
docker logs loki -f
```

### Restart a Single Service
```bash
docker-compose restart backend-2
docker-compose restart otel-collector
```

### Rebuild and Restart After Code Changes
```bash
# Rebuild REST API Docker image
cd rest-api && ./gradlew buildImage --no-configuration-cache && cd ..

# Restart backends with new image
docker-compose up -d --force-recreate backend-1 backend-2 backend-3
```

### View Service Status
```bash
docker-compose ps
```

## 🧹 Cleanup

### Stop Everything
```bash
# Stop all services (observability + backends)
docker-compose down

# Stop load balancer (Ctrl+C in terminal if running)
```

### Complete Cleanup
```bash
# Remove all containers and networks
docker-compose down

# Remove Docker image
docker rmi rest-api:latest

# Remove Docker volumes (if any)
docker-compose down -v
```

## 🔄 Alternative: Run Backends Without Docker

If you prefer not to use Docker for backends (for development/debugging), you'll need to modify `Application.kt` to accept a PORT environment variable. See the "Running with Gradle" section below.

## 📝 Log Query Examples

### Grafana Loki Queries

```logql
# All services
{service=~"load-balancer|backend-.*"}

# Errors only
{service="load-balancer"} | json | level="ERROR"

# Health check logs
{service="load-balancer"} | json | component="health-check"

# Specific node logs
{service="load-balancer"} | json | node_id="node-1"

# Circuit breaker events
{service="load-balancer"} | json | circuit_breaker_state!=""

# Slow backend requests
{service=~"backend-.*"} | json | delayApplied > 0

# Requests with specific trace
{trace_id="<your-trace-id>"}

# Failed requests
{service="load-balancer"} |= "failed"

# Requests by path
{service=~"backend-.*"} | json | path="/hello"
```

## 🛠️ Troubleshooting

### Issue: "OTLP exporter endpoint port is likely incorrect" warning
**Warning:**
```
OTLP exporter endpoint port is likely incorrect for protocol version "http/protobuf". 
The endpoint http://localhost:4317 has port 4317. 
Typically, the "http/protobuf" version of OTLP uses port 4318.
```

**Explanation:** The OpenTelemetry agent defaults to HTTP/protobuf protocol when you use `http://` in the endpoint. But port 4317 is for gRPC, not HTTP.

**Solutions:**

**Option 1: Use HTTP protocol (recommended for simplicity):**
```bash
-Dotel.exporter.otlp.endpoint=http://localhost:4318
# No protocol flag needed, defaults to http/protobuf
```

**Option 2: Use gRPC protocol explicitly:**
```bash
-Dotel.exporter.otlp.endpoint=http://localhost:4317 \
-Dotel.exporter.otlp.protocol=grpc
```

**Option 3: Use gRPC with grpc:// scheme:**
```bash
-Dotel.exporter.otlp.endpoint=grpc://localhost:4317
# Protocol auto-detected from scheme
```

**Quick Reference:**
| Protocol | Port | Endpoint | Extra Flag |
|----------|------|----------|------------|
| HTTP/protobuf | 4318 | `http://localhost:4318` | None needed |
| gRPC | 4317 | `http://localhost:4317` | `-Dotel.exporter.otlp.protocol=grpc` |
| gRPC | 4317 | `grpc://localhost:4317` | None needed |

### Issue: "Failed to open the file opentelemetry-javaagent.jar: Is a directory"
**Solution:** The file exists as a directory. Remove it and download again:
```bash
rm -rf opentelemetry-javaagent.jar
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar
```

### Issue: "Connection refused" when accessing backends
**Solution:** Make sure all backend instances are running on correct ports (9001, 9002, 9003).

### Issue: "Connection reset" errors from OpenTelemetry agent
**Error:**
```
[otel.javaagent] ERROR io.opentelemetry.exporter.internal.http.HttpExporter - 
Failed to export logs/metrics. Connection reset
```

**Solution:** These are usually transient startup errors. The OTel agent will retry automatically.

**Quick fix:**
```bash
# Restart in correct order
docker-compose restart otel-collector
sleep 5
docker-compose restart backend-1 backend-2 backend-3
```

**Verify backends are working:**
```bash
curl http://localhost:9001/health  # Should return {"status": "healthy"}
```

If backends respond to health checks, the errors can be ignored. See `OTEL_CONNECTION_ERRORS.md` for details.

### Issue: "cannot serialize object" when building Docker image
**Solution:** Use the `--no-configuration-cache` flag:
```bash
./gradlew buildImage --no-configuration-cache
```
This is required because the Ktor plugin's Jib integration is not yet compatible with Gradle's configuration cache.

### Issue: No logs in Grafana Loki
**Solution:** 
1. Check OTLP collector is running: `docker-compose ps`
2. Verify OTel agent jar path in run command
3. Check collector logs: `docker-compose logs otel-collector`

### Issue: No traces in Tempo
**Solution:**
1. Verify OTel agent is attached (check startup logs)
2. Ensure `traceparent` header is being propagated
3. Check Tempo is receiving data: `docker-compose logs tempo`

### Issue: Gradle build fails
**Solution:**
```bash
# Clean and rebuild
./gradlew clean build --refresh-dependencies

# Check Java version
java -version  # Should be 21+
```

### Issue: Port already in use
**Solution:**
```bash
# Find process using port
lsof -ti:8080

# Kill process
kill -9 <PID>
```

## 📚 Key Technologies

- **Kotlin 2.2.20** - Modern JVM language
- **Ktor 3.0.3** - Async HTTP server/client
- **OpenTelemetry Java Agent** - Automatic instrumentation
- **Logstash Logback Encoder 8.0** - Structured JSON logging
- **Grafana Stack** - Observability platform
- **Docker Compose** - Container orchestration

## 🎯 What Makes This Special

### 1. Zero Manual Trace Extraction
The OpenTelemetry Java Agent automatically:
- Injects `trace_id` and `span_id` into SLF4J MDC
- Propagates trace context via HTTP headers
- Creates spans for all HTTP operations
- Links parent-child spans across services

### 2. Industry-Standard Structured Logging
Using Logstash Logback Encoder:
- Automatic JSON formatting
- MDC integration out of the box
- No custom logging framework needed

### 3. Complete Observability
- **Logs**: Structured JSON in Loki with trace correlation
- **Metrics**: Custom business metrics in Prometheus
- **Traces**: Distributed traces in Tempo
- All automatically correlated by `trace_id`

### 4. Domain-Driven Design
- Clear bounded contexts (Routing, Health Monitoring, Metrics)
- Proper aggregates and value objects
- Domain events for state changes
- Repository pattern for persistence abstraction

## 📖 Documentation

- `COMPLETE_SETUP_GUIDE.md` - Detailed setup and usage guide
- `PROJECT_STATUS.md` - Current implementation status
- `READY_FOR_DEMO.md` - Quick reference for demonstrations
- `ARCHITECTURE.md` - DDD architecture documentation
- `rest-api/README.md` - REST API specific documentation

## 🎉 Next Steps

1. **Start the observability stack**: `docker-compose up -d`
2. **Download OTel agent**: See Step 2 above
3. **Build both projects**: See Step 4 above
4. **Run backends and load balancer**: See Steps 5-6 above
5. **Test and explore in Grafana**: http://localhost:3000

## 🤝 Contributing

This is a demo/interview project showcasing:
- Production-ready load balancing
- Resilience patterns (Circuit Breaker)
- Domain-Driven Design principles
- Complete observability with OpenTelemetry
- Structured logging best practices

## 📄 License

This is a portfolio/interview demonstration project.

---

**Built with ❤️ using Kotlin, OpenTelemetry, and the Grafana Stack**

