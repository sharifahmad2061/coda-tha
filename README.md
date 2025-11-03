# Load Balancer with Configurable REST API Backend

A production-ready load balancer implementation in Kotlin demonstrating Domain-Driven Design principles, complete observability with OpenTelemetry, and runtime-configurable backend services for testing resilience patterns.

## 🎯 Features

### Load Balancer (`com.sahmad.loadbalancer`)
- ✅ **Load Balancing Strategy**: Round Robin
- ✅ **Health Monitoring**: Active health checks every 5 seconds
- ✅ **Resilience**: Timeouts, retries, graceful degradation
- ✅ **Domain-Driven Design**: Clear bounded contexts, aggregates, value objects
- ✅ **Structured Logging**: JSON logs with trace context
- ✅ **Distributed Tracing**: Automatic trace propagation with OpenTelemetry
- ✅ **Metrics**: Auto-instrumented with OpenTelemetry

### REST API Backend (`com.sahmad.restapi`)
- ✅ **Runtime Configurable Delay**: Test slow backend scenarios
- ✅ **Single Endpoint**: `POST /{path...}` accepts any path
- ✅ **Echo Response**: Returns request body as-is
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
```zsh
java -version
# Should show version 21 or higher
```

## 🚀 Quick Start

### Step 1: Clone and Navigate
```zsh
cd $PWD  # Or wherever you cloned the repo
```

### Step 2: Download OpenTelemetry Java Agent
```zsh
# Download the latest OTel Java Agent
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o opentelemetry-javaagent.jar

# Verify download
ls -lh opentelemetry-javaagent.jar
```

### Step 3: Build Docker Images
```zsh
# Build REST API Docker image using Ktor's Jib plugin
cd rest-api
./gradlew jibDockerBuild --no-configuration-cache
cd ..

# Build Load Balancer Docker image
cd load-balancer
./gradlew jibDockerBuild --no-configuration-cache
cd ..

# Verify the images were created
docker images | grep -E "rest-api|load-balancer"
# You should see:
# rest-api        1.0.0   ...
# load-balancer   1.0.0   ...
```

**Note:** If you get a "Cannot run program 'docker'" error, stop the Gradle daemon and try again:
```zsh
./gradlew --stop
./gradlew jibDockerBuild --no-configuration-cache
```

### Step 4: Start All Services

```zsh
# Start everything: observability stack + load-balancer + 3 backend instances
docker-compose up -d

# Verify all services are running
docker-compose ps

# Services will be available at:
# - Load Balancer:  http://localhost:8080
# - Grafana:        http://localhost:3000
# - Prometheus:     http://localhost:9090
# - Loki:           http://localhost:3100
# - Tempo:          http://localhost:3200
# - OTLP gRPC:      localhost:4317
# - Backend 1:      http://localhost:9001 (internal: backend-1:8080)
# - Backend 2:      http://localhost:9002 (internal: backend-2:8080)
# - Backend 3:      http://localhost:9003 (internal: backend-3:8080)
```


### Step 5: Test the Setup

**Send a request through the load balancer:**
```zsh
curl -X POST http://localhost:8080/hello \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello from client!"}'
```

**Expected response:**
```json
{
  "message": "Hello from client!"
}
```

### Step 6: View Telemetry in Grafana

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
```

## 🎮 Testing Scenarios

### Scenario 1: Normal Load Balancing
```zsh
# Send 6 requests and see round-robin distribution
for i in {1..6}; do
  curl -X POST http://localhost:8080/test \
    -H "Content-Type: application/json" \
    -d "{\"request\": $i}"
  echo ""
done

# Check load balancer logs to see distribution
docker logs load-balancer | grep "Routing request"
```

### Scenario 2: Configure Slow Backend
```zsh
# Make backend-2 slow (2 second delay)
curl -X POST http://localhost:9002/config/delay \
  -H "Content-Type: application/json" \
  -d '{"delayMs": 2000}'

# Send requests - load balancer will wait for slow backend
for i in {1..3}; do
  curl -X POST http://localhost:8080/test \
    -H "Content-Type: application/json" \
    -d '{"test": true}'
  echo ""
done

# View in logs
docker logs load-balancer | grep "latency"
```

### Scenario 3: Backend Timeout and Retry
```zsh
# Make backend-1 very slow (exceeds 5s timeout)
curl -X POST http://localhost:9001/config/delay \
  -H "Content-Type: application/json" \
  -d '{"delayMs": 6000}'

# Send request - will timeout on backend-1 and retry on another backend
curl -X POST http://localhost:8080/test \
  -H "Content-Type: application/json" \
  -d '{"test": "timeout"}'

# View timeout and retry in logs
docker logs load-balancer | grep -E "timeout|retry"
```

### Scenario 4: Distributed Tracing
```zsh
# Send one request
curl -X POST http://localhost:8080/hello -d '{"trace": "test"}'

# In Grafana (http://localhost:3000):
# 1. Go to Explore
# 2. Select Tempo
# 3. Click "Search" tab
# 4. Select service "load-balancer"
# 5. Click "Run query"
# 6. Click on any trace to see:
#    - Load balancer incoming request span
#    - HTTP client outgoing request span  
#    - Backend processing span
```

## 📊 API Reference

### Load Balancer Endpoints

#### Forward Request (Load Balanced)
```zsh
POST http://localhost:8080/{any-path}
Content-Type: application/json

# Forwards to one of the healthy backend nodes
```

#### View All Nodes
```zsh
GET http://localhost:8080/admin/nodes

# Response:
[
  {
    "id": "backend-1",
    "endpoint": "backend-1:8080",
    "health": "HEALTHY"
  },
  ...
]
```

#### View Metrics
```zsh
GET http://localhost:8080/metrics

# Response includes:
# - Total nodes
# - Available nodes
# - Node health details
```

#### Health Check
```zsh
GET http://localhost:8080/health
```

### REST API Backend Endpoints

#### Configure Delay
```zsh
POST http://localhost:9001/config/delay
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
```zsh
GET http://localhost:9001/config

# Response:
{
  "delayMs": 3000
}
```

#### Process Request (Any Path)
```zsh
POST http://localhost:9001/{any-path}
Content-Type: application/json

{
  "your": "data"
}

# Response: Returns the same body
{
  "your": "data"
}
```

#### Health Check
```zsh
GET http://localhost:9001/health
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
```zsh
-Dotel.exporter.otlp.endpoint=http://localhost:4318
# No protocol flag needed, defaults to http/protobuf
```

**Option 2: Use gRPC protocol explicitly:**
```zsh
-Dotel.exporter.otlp.endpoint=http://localhost:4317 \
-Dotel.exporter.otlp.protocol=grpc
```

**Option 3: Use gRPC with grpc:// scheme:**
```zsh
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
```zsh
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
```zsh
# Restart in correct order
docker-compose restart otel-collector
sleep 5
docker-compose restart backend-1 backend-2 backend-3
```

**Verify backends are working:**
```zsh
curl http://localhost:9001/health  # Should return {"status": "healthy"}
```

If backends respond to health checks, the errors can be ignored. See `OTEL_CONNECTION_ERRORS.md` for details.

### Issue: "cannot serialize object" when building Docker image
**Solution:** Use the `--no-configuration-cache` flag:
```zsh
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
```zsh
# Clean and rebuild
./gradlew clean build --refresh-dependencies

# Check Java version
java -version  # Should be 21+
```

### Issue: Port already in use
**Solution:**
```zsh
# Find process using port
lsof -ti:8080

# Kill process
kill -9 <PID>
```

---

**Built with ❤️ using Kotlin, OpenTelemetry, and the Grafana Stack**



