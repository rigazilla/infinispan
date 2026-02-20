# Dual Netty Transport Analysis: gRPC + Standard Protocols

## Executive Summary

Having two separate Netty transports in Infinispan (one managed by `NettyTransport` for HotRod/REST/RESP/Memcached, and one managed by gRPC's `NettyServerBuilder`) is **architecturally sound** but comes with **resource overhead** that should be understood and managed.

**Verdict**: ✅ No fundamental problems, but optimization opportunities exist.

---

## Current Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Infinispan Server Process                     │
│                                                                   │
│  ┌────────────────────────────┐  ┌───────────────────────────┐ │
│  │   NettyTransport (11222)   │  │   gRPC Server (9090)      │ │
│  │                            │  │                           │ │
│  │  Boss EventLoopGroup       │  │  Boss EventLoopGroup      │ │
│  │  - Accept thread (1)       │  │  - Accept thread (1)      │ │
│  │                            │  │                           │ │
│  │  Worker EventLoopGroup     │  │  Worker EventLoopGroup    │ │
│  │  - I/O threads (N)         │  │  - I/O threads (M)        │ │
│  │                            │  │                           │ │
│  │  Protocols:                │  │  Executor Service         │ │
│  │  - HotRod                  │  │  - App threads (P)        │ │
│  │  - REST                    │  │                           │ │
│  │  - RESP                    │  │  Services:                │ │
│  │  - Memcached               │  │  - CacheService           │ │
│  └────────────────────────────┘  └───────────────────────────┘ │
│                                                                   │
│  Total threads: 1 + N + 1 + M + P                                │
│  (Typical: 1 + 16 + 1 + 16 + 32 = 66 threads)                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Analysis of Concerns

### 1. ✅ Thread Proliferation (Minor Concern)

**The Situation:**

Each Netty transport creates its own thread pools:

```java
// Infinispan's NettyTransport
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup(ioThreads); // Default: 2 * CPU cores

// gRPC's NettyServerBuilder (defaults)
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup(0); // 0 = 2 * CPU cores
Executor executor = Executors.newCachedThreadPool(); // Unbounded!
```

**On a 16-core machine:**
- Infinispan boss: 1 thread
- Infinispan workers: 32 threads (2 × 16)
- gRPC boss: 1 thread
- gRPC workers: 32 threads (2 × 16)
- gRPC executor: 0-∞ threads (cached pool)
- **Total: ~66+ threads minimum**

**Is this a problem?**

❌ **No, not really:**
- Modern JVMs handle hundreds of threads efficiently
- Virtual threads (Project Loom) make this even cheaper
- Most threads are parked (waiting for I/O)
- Context switching overhead is minimal with proper event loops

✅ **But it's not optimal:**
- Could share event loop groups between transports
- Could use bounded executor for gRPC
- Could reduce thread counts if under light load

**Recommendation:**
```java
// Configure gRPC to use bounded resources
public class GrpcServer extends AbstractProtocolServer {
    @Override
    protected void startInternal() {
        // Bounded executor instead of cached
        ExecutorService executor = new ThreadPoolExecutor(
            4,  // core threads
            32, // max threads
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000)
        );

        grpcServer = NettyServerBuilder
            .forAddress(address)
            .executor(executor)
            .workerEventLoopGroup(new NioEventLoopGroup(configuration.ioThreads()))
            .build();
    }
}
```

### 2. ✅ Memory Overhead (Negligible)

**Each EventLoopGroup allocates:**
- Thread stacks: ~1MB per thread (32 threads = 32MB)
- Direct memory buffers: Pooled and shared
- Selector overhead: ~1KB per thread

**Total additional memory for dual transport:**
- Threads: ~66MB (stack space)
- Buffers: Shared via `PooledByteBufAllocator`
- **Total: <100MB on a server with GBs of heap**

**Is this a problem?**

❌ **No** - Infinispan servers typically run with multi-GB heaps. An extra 100MB is negligible.

### 3. ✅ Port Management (Operational Consideration)

**Current setup:**
- Port 11222: HotRod, REST, RESP, Memcached (single-port mode)
- Port 9090: gRPC (dedicated port)

**Firewall/networking implications:**
```bash
# Firewall rules needed
firewall-cmd --add-port=11222/tcp  # Standard protocols
firewall-cmd --add-port=9090/tcp   # gRPC

# Container port mappings
docker run -p 11222:11222 -p 9090:9090 infinispan-server

# Kubernetes service
spec:
  ports:
  - name: hotrod-rest
    port: 11222
  - name: grpc
    port: 9090
```

**Is this a problem?**

⚠️ **Minor operational complexity:**
- More ports to manage
- More firewall rules
- More service endpoints
- But this is standard practice (Redis: 6379, MongoDB: 27017, Prometheus: 9090, etc.)

**Benefit of dedicated port:**
- Clear separation for monitoring
- Can apply different QoS policies
- Can isolate gRPC traffic for debugging

### 4. ✅ Monitoring and Metrics (Fragmentation)

**Two separate metric sources:**

```java
// Infinispan's NettyTransport metrics
- infinispan.server.connections.active
- infinispan.server.bytes.sent
- infinispan.server.bytes.received
- infinispan.server.worker.threads.busy

// gRPC metrics (if we add them)
- grpc.server.calls.started
- grpc.server.calls.succeeded
- grpc.server.msgs.sent
- grpc.server.msgs.received
```

**Is this a problem?**

⚠️ **Slight monitoring complexity:**
- Need to aggregate from two sources
- Different metric naming schemes
- But this is easily handled by modern monitoring (Prometheus, Grafana)

**Solution:**
```yaml
# Prometheus config
scrape_configs:
  - job_name: 'infinispan'
    static_configs:
      - targets: ['localhost:11222']  # REST metrics endpoint
    relabel_configs:
      - source_labels: [__name__]
        regex: '(infinispan|grpc).*'
        action: keep
```

### 5. ⚠️ Single-Port Mode Limitation (Architectural Constraint)

**The challenge:**

Protocol detection on a shared port requires reading initial bytes:

```
Connection arrives on port 11222
  ↓
Read first few bytes
  ↓
  0xA0 → HotRod
  "GET /rest" → REST
  "*3\r\n$3\r\n" → RESP
  "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" → gRPC/HTTP2
```

**The problem:**

Once you've read bytes for detection, how do you hand them off to gRPC's server?

```java
// Netty pipeline before detection
[Protocol Detector] → ???

// After detecting gRPC, we need:
[HTTP2 Frame Decoder] → [gRPC Handler] → [Service Dispatcher]
  ↑
  └── But this is all internal to NettyServerBuilder!
```

**Is this a problem?**

⚠️ **Yes, for single-port mode**, but:
- Dedicated port mode works fine (current implementation)
- Single-port can be added later with complexity
- Most production deployments use separate ports anyway

**Potential solution** (future work):
```java
// Option 1: Proxy pattern
// Detect gRPC, then proxy bytes to gRPC's server on localhost
if (isGrpcConnection) {
    proxyTo("localhost:9090");
}

// Option 2: Shared event loop with protocol routing
// More complex, requires deep integration with gRPC internals
EventLoopGroup sharedEventLoop = ...;
NettyServerBuilder.forAddress(...).workerEventLoopGroup(sharedEventLoop);
NettyTransport.useEventLoopGroup(sharedEventLoop);
```

### 6. ✅ Resource Isolation (Actually a Benefit!)

**Positive aspect of dual transport:**

```
Scenario: HotRod client sends huge bulk request that saturates I/O threads
  ↓
With shared transport:
  - gRPC calls also delayed (bad!)

With separate transports:
  - gRPC unaffected (good!)
  - Independent thread pools provide isolation
```

**Benefits:**
- No cross-protocol interference
- Can tune each transport independently
- Easier to debug performance issues
- Can apply different backpressure strategies

---

## Comparison: How Other Systems Handle This

### 1. **Envoy Proxy** (Similar Dual-Transport Model)

```yaml
# Envoy config
static_resources:
  listeners:
  - name: http_listener
    address:
      socket_address: { address: 0.0.0.0, port_value: 8080 }
    filter_chains:
    - filters:
      - name: envoy.filters.network.http_connection_manager
        # Uses its own Envoy event loop

  - name: grpc_listener
    address:
      socket_address: { address: 0.0.0.0, port_value: 9090 }
    filter_chains:
    - filters:
      - name: envoy.filters.network.http_connection_manager
        # Shares the same event loop but separate listener
```

**Envoy's approach:**
- Multiple listeners on different ports
- Shared event loop but separate protocol stacks
- Similar to our dual-transport model

### 2. **NGINX** (Multiple Worker Processes)

```nginx
worker_processes auto;  # Creates N worker processes

http {
    server {
        listen 80;
        # HTTP/1.1 handling
    }
}

stream {
    server {
        listen 9090 http2;
        # gRPC handling
    }
}
```

**NGINX's approach:**
- Separate worker processes
- Each has its own event loop
- More resource overhead than our model

### 3. **gRPC-Go Server** (Single Event Loop)

```go
lis, _ := net.Listen("tcp", ":9090")
grpcServer := grpc.NewServer()
pb.RegisterGreeterServer(grpcServer, &server{})
grpcServer.Serve(lis)
```

**gRPC-Go's approach:**
- Uses Go's goroutine model
- Single event loop in the runtime
- Much lighter weight than Java threads

### 4. **MongoDB** (Separate Transports)

```
Port 27017: MongoDB Wire Protocol
Port 28017: HTTP Status/Metrics (deprecated)
Port 27018: Sharding config server
```

**MongoDB's approach:**
- Multiple dedicated ports
- Each protocol gets its own transport
- **Same model as Infinispan!**

---

## Optimization Opportunities

### Option 1: Share Event Loop Groups (Medium Complexity)

```java
public class ServerTransportManager {
    private static final EventLoopGroup SHARED_BOSS = new NioEventLoopGroup(1);
    private static final EventLoopGroup SHARED_WORKER = new NioEventLoopGroup();

    // NettyTransport uses shared groups
    public static NettyTransport createInfinispanTransport(...) {
        return new NettyTransport(SHARED_BOSS, SHARED_WORKER, ...);
    }

    // gRPC server uses shared groups
    public static Server createGrpcServer(...) {
        return NettyServerBuilder.forAddress(...)
            .bossEventLoopGroup(SHARED_BOSS)
            .workerEventLoopGroup(SHARED_WORKER)
            .channelType(NioServerSocketChannel.class)
            .build();
    }
}
```

**Benefits:**
- Reduces thread count from ~66 to ~34 (50% reduction)
- Shared I/O thread pool
- Better CPU utilization

**Risks:**
- Protocols now share I/O resources (less isolation)
- Harder to debug performance issues
- Need careful tuning of thread pool size

### Option 2: Dynamic Thread Pool Sizing (Low Complexity)

```java
public class GrpcServerConfiguration {
    public int ioThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int load = estimateServerLoad();

        if (load < 0.3) {
            return cores / 2;  // Light load: use fewer threads
        } else if (load < 0.7) {
            return cores;      // Medium load: use cores
        } else {
            return cores * 2;  // High load: use 2x cores
        }
    }
}
```

**Benefits:**
- Adapts to actual load
- Saves resources when idle
- Scales up under pressure

### Option 3: Virtual Threads (Java 21+, Low Complexity)

```java
public class GrpcServer extends AbstractProtocolServer {
    @Override
    protected void startInternal() {
        // Use virtual threads for gRPC executor
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        grpcServer = NettyServerBuilder
            .forAddress(address)
            .executor(executor)  // Virtual threads = minimal overhead
            .build();
    }
}
```

**Benefits:**
- Millions of virtual threads possible
- Minimal memory overhead
- Simplified thread management

**Status:** Infinispan already uses virtual threads (seen in logs: "Virtual threads support: enabled")

---

## Resource Usage Comparison

### Scenario: 16-core server, moderate load

| Configuration | Boss Threads | Worker Threads | App Threads | Total | Memory |
|---------------|--------------|----------------|-------------|-------|--------|
| **Current (Dual Transport)** | 2 | 64 | 32 | 98 | ~100MB |
| **Shared Event Loops** | 1 | 32 | 32 | 65 | ~70MB |
| **Virtual Thread Executor** | 2 | 64 | 0* | 66 | ~70MB |
| **Single Transport (hypothetical)** | 1 | 32 | 0 | 33 | ~40MB |

*Virtual threads don't consume platform threads

### Actual Impact

On a typical Infinispan deployment:
- Heap: 4-16 GB
- CPU: 8-32 cores
- Connections: 100-10,000 concurrent

**The extra 60-70MB and 30-60 threads are negligible** (<2% overhead)

---

## Real-World Problems vs Theoretical Concerns

### ❌ Theoretical Concerns That Don't Matter

1. **"Too many threads"**
   - Modern JVMs handle this fine
   - Virtual threads make it even cheaper
   - Most threads are parked anyway

2. **"Doubled memory usage"**
   - Extra 70-100MB on multi-GB heaps
   - ByteBuf pools are shared
   - Negligible impact

3. **"Wasted CPU cycles"**
   - Event loops are efficient
   - Threads sleep when idle
   - No measurable CPU overhead

### ✅ Real Concerns That Matter

1. **Operational complexity**
   - Two ports to manage
   - Two metric sources
   - More configuration options

2. **Single-port mode is harder**
   - Protocol detection is complex
   - Requires deep integration
   - But it's optional

3. **Tuning complexity**
   - Two thread pools to configure
   - Need to understand both transports
   - But defaults work well

---

## Recommendations

### For Current Implementation (Phase 1)

✅ **Keep the dual-transport model as-is** because:
1. It works correctly
2. Resource overhead is negligible
3. Clean separation of concerns
4. Easier to debug and maintain

✅ **Add configuration options** for gRPC:
```xml
<grpc-connector
    socket-binding="grpc"
    io-threads="16"           <!-- Configurable worker threads -->
    executor-threads="32"     <!-- Configurable app threads -->
    name="grpc"/>
```

✅ **Document the architecture** (done in GRPC_ARCHITECTURE.md)

### For Future Enhancements (Phase 2)

⚠️ **Consider event loop sharing** if:
- Users complain about thread count
- Profiling shows thread overhead
- Running on resource-constrained environments

⚠️ **Add single-port support** if:
- Users request it (not needed initially)
- Willing to accept complexity
- Benefits outweigh costs

✅ **Use virtual threads everywhere** (already enabled):
- Simplifies thread management
- Reduces memory overhead
- Improves scalability

---

## Conclusion

### Is dual transport a problem? **No.**

**Why it's fine:**
1. ✅ Resource overhead is negligible (<2% of typical deployment)
2. ✅ Provides better isolation between protocols
3. ✅ Easier to debug and maintain
4. ✅ Follows industry patterns (MongoDB, Envoy, NGINX)
5. ✅ Can be optimized later if needed

**The real insight:**

The "problem" isn't the dual transport—it's that people instinctively think "two of something" is worse than "one of something." But in this case:

- **Two simple, isolated transports** (current model)
  - Easy to understand
  - Easy to maintain
  - Predictable behavior
  - Small overhead

Is better than:

- **One complex, shared transport** (hypothetical alternative)
  - Complex protocol detection
  - Shared resource contention
  - Harder to debug
  - Unclear benefit

**Verdict:** The dual-transport architecture is **correct, pragmatic, and efficient**. Keep it.

---

## Appendix: If You Really Want Single Transport

For completeness, here's how you'd do it (not recommended for v1):

```java
public class UnifiedProtocolDetector extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 24) {
            return; // Need more bytes
        }

        // Check for HTTP/2 preface (gRPC)
        if (isHttp2Preface(in)) {
            // Hand off to gRPC's NettyServerHandler
            // This is VERY complex - need to bridge pipelines
            handOffToGrpc(ctx, in);
            return;
        }

        // Check for HotRod magic byte
        if (in.getByte(0) == (byte) 0xA0) {
            ctx.pipeline().replace(this, "hotrod-decoder", new HotRodDecoder());
            return;
        }

        // Check for HTTP/1.1 (REST)
        if (isHttp1(in)) {
            ctx.pipeline().replace(this, "http-codec", new HttpServerCodec());
            return;
        }

        // ... etc
    }

    private void handOffToGrpc(ChannelHandlerContext ctx, ByteBuf in) {
        // THE PROBLEM: How do we inject into gRPC's pipeline?
        // gRPC's NettyServerHandler is created by NettyServerBuilder
        // and expects a clean channel with no existing handlers.

        // Possible solutions:
        // 1. Proxy to localhost:9090 (adds latency)
        // 2. Reflection hacks into gRPC internals (fragile)
        // 3. Fork gRPC and add hooks (maintenance burden)

        // All options are complex and fragile.
        // Dual transport is simpler.
    }
}
```

This is why dual transport is the right choice for v1.
