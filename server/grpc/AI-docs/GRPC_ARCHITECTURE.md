# gRPC Endpoint Architecture and Transport Model

## Executive Summary

This document describes the architectural integration of the gRPC protocol endpoint into Infinispan Server and provides a detailed analysis of the transport layer challenges unique to gRPC compared to other protocol endpoints.

**Key Finding**: The gRPC endpoint is the **only** protocol endpoint in Infinispan Server that manages its own Netty transport infrastructure, which creates architectural tension with the existing `AbstractProtocolServer` design that assumes all protocols share a common transport abstraction.

---

## Infinispan Server Protocol Architecture

### Overview

Infinispan Server supports multiple wire protocols through a plugin architecture based on `AbstractProtocolServer`:

| Protocol | Port | Purpose | Transport Model |
|----------|------|---------|-----------------|
| HotRod | 11222 | Binary protocol for Java/C++/C# clients | Shared NettyTransport |
| REST | 11222 | HTTP/JSON API | Shared NettyTransport |
| RESP | 11222 | Redis-compatible protocol | Shared NettyTransport |
| Memcached | 11222 | Memcached-compatible protocol | Shared NettyTransport |
| **gRPC** | 9090 | HTTP/2 + Protocol Buffers | **Independent gRPC Server** |

### Standard Protocol Endpoint Architecture

All existing protocols (HotRod, REST, RESP, Memcached) follow this pattern:

```
┌─────────────────────────────────────────────────────────────┐
│                   AbstractProtocolServer                     │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              NettyTransport (shared)                    │ │
│  │  - Boss EventLoopGroup (connection acceptance)         │ │
│  │  - Worker EventLoopGroup (request processing)          │ │
│  │  - Channel Pipeline (handlers)                         │ │
│  │  - Metrics registration                                │ │
│  │  - Connection tracking                                 │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                               │
│  Protocol-specific handlers:                                 │
│  - Decoder: Wire format → Internal representation           │
│  - Encoder: Internal representation → Wire format           │
│  - Handler: Business logic                                  │
└─────────────────────────────────────────────────────────────┘
```

**Key characteristics:**
- All protocols share the same Netty event loop groups
- `NettyTransport` is managed by `AbstractProtocolServer`
- Metrics are registered via the transport reference
- Channel initialization follows a standard pattern
- Single-port mode is supported through protocol detection

### AbstractProtocolServer Contract

The base class defines this contract:

```java
public abstract class AbstractProtocolServer<T extends ProtocolServerConfiguration> {
    protected NettyTransport transport;  // ← Expected to be non-null

    protected abstract ChannelOutboundHandler getEncoder();
    protected abstract ChannelInboundHandler getDecoder();
    protected abstract ChannelInitializer<Channel> getInitializer();

    protected void registerMetrics() {
        // Uses transport to register metrics
        metricsRegistry.registerExternalMetrics(transport);  // ← NPE if transport is null
    }

    protected void startTransport() {
        transport.start();  // ← Expects transport to exist
    }
}
```

**Assumptions:**
1. Every protocol has a `NettyTransport` instance
2. The transport is initialized before `registerMetrics()` is called
3. Encoders/decoders are Netty handlers that plug into the shared pipeline
4. All protocols use the same event loop model

---

## The gRPC Difference: Why It Needs Its Own Transport

### gRPC Server Architecture

gRPC is fundamentally different from other protocols because it's not just a wire format—it's a **complete RPC framework** with its own server infrastructure:

```
┌─────────────────────────────────────────────────────────────┐
│                      GrpcServer                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │          io.grpc.netty.NettyServerBuilder              │ │
│  │  - Manages its own Netty ServerBootstrap               │ │
│  │  - Creates its own boss/worker EventLoopGroups         │ │
│  │  - Configures HTTP/2 codec automatically               │ │
│  │  - Handles gRPC protocol framing                       │ │
│  │  - Manages service registration                        │ │
│  │  - Provides built-in health checks, reflection, etc.   │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                               │
│  Service implementations:                                     │
│  - CacheServiceImpl (generated from .proto)                  │
│  - Future: CounterService, AdminService, etc.               │
└─────────────────────────────────────────────────────────────┘
```

### Why gRPC Cannot Use Infinispan's NettyTransport

There are **fundamental architectural incompatibilities** that prevent gRPC from using the shared `NettyTransport`:

#### 1. **Protocol Stack Ownership**

**Standard Protocols:**
```java
// Infinispan controls the entire Netty pipeline
pipeline.addLast("decoder", new HotRodDecoder());
pipeline.addLast("encoder", new HotRodEncoder());
pipeline.addLast("handler", new HotRodRequestHandler());
```

**gRPC:**
```java
// gRPC's NettyServerBuilder builds the entire pipeline internally
NettyServerBuilder.forAddress(address)
    .addService(cacheService)  // ← gRPC manages the stack from here down
    .build()
    .start();

// Internally gRPC creates:
// - HTTP/2 frame codec
// - gRPC protocol handler
// - Service dispatch handler
// - Marshaller/unmarshaller (protobuf)
// All of this is opaque to Infinispan
```

The gRPC library **owns and manages** the entire protocol stack. We cannot inject custom decoders/encoders because gRPC doesn't expose that level of control.

#### 2. **HTTP/2 Multiplexing Requirements**

**Standard Protocols:** Simple request-response
```
Client ──[Request]──> Server
       <──[Response]──
```

**gRPC:** HTTP/2 streams with multiplexing
```
Client ══[Stream 1: Request A]══> Server
       ══[Stream 2: Request B]══>
       <══[Stream 1: Response A]══
       <══[Stream 2: Response B]══
       ══[Stream 3: Streaming RPC]══>
       <══[Stream 3: Response 1]══
       <══[Stream 3: Response 2]══
```

gRPC requires the full HTTP/2 implementation from `io.grpc:grpc-netty`, which includes:
- Stream multiplexing
- Flow control
- Header compression (HPACK)
- Server push
- Stream prioritization

Infinispan's `NettyTransport` is designed for simple protocols and doesn't have HTTP/2 support built in.

#### 3. **Service Registration Model**

**Standard Protocols:** Monolithic handler
```java
class HotRodHandler extends ChannelInboundHandler {
    void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Handle all operations in one class
        switch (operation) {
            case GET: handleGet(); break;
            case PUT: handlePut(); break;
            // ...
        }
    }
}
```

**gRPC:** Service-oriented with code generation
```java
// Generated from cache.proto
public abstract class CacheServiceGrpc {
    public static abstract class CacheServiceImplBase {
        public void put(PutRequest req, StreamObserver<PutResponse> resp) {}
        public void get(GetRequest req, StreamObserver<GetResponse> resp) {}
        // Each RPC method is separate
    }
}

// Our implementation
class CacheServiceImpl extends CacheServiceGrpc.CacheServiceImplBase {
    @Override
    public void put(PutRequest req, StreamObserver<PutResponse> resp) {
        // gRPC handles all routing to get here
    }
}
```

The gRPC server builder registers services and automatically:
- Routes incoming RPCs to the correct service method
- Handles serialization/deserialization using protobuf
- Manages streaming semantics
- Provides interceptors for cross-cutting concerns

#### 4. **Marshalling Integration**

**Standard Protocols:** Manual serialization
```java
class HotRodEncoder extends MessageToByteEncoder {
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
        // Manually write bytes
        out.writeByte(MAGIC);
        out.writeLong(messageId);
        // ...
    }
}
```

**gRPC:** Automatic protobuf marshalling
```protobuf
message PutRequest {
    string cache_name = 1;
    bytes key = 2;
    bytes value = 3;
}
```
```java
// gRPC automatically:
// 1. Deserializes bytes → PutRequest object
// 2. Calls put(PutRequest req, ...)
// 3. Serializes PutResponse object → bytes
```

The marshalling is tightly integrated with gRPC's interceptor chain and cannot be separated.

#### 5. **Thread Model Differences**

**Standard Protocols:** I/O threads handle everything
```java
// NettyTransport configuration
workerGroup = new NioEventLoopGroup(ioThreads);
bootstrap.group(bossGroup, workerGroup);

// Request handling happens on worker threads
class HotRodHandler extends ChannelInboundHandler {
    void channelRead(...) {
        // This runs on Netty I/O thread
        cache.get(key);  // Blocking call on I/O thread (bad!)
    }
}
```

**gRPC:** Executor separation
```java
NettyServerBuilder
    .forAddress(address)
    .executor(Executors.newCachedThreadPool())  // App logic runs here
    .workerEventLoopGroup(nioEventLoopGroup)    // I/O runs here
    .build();

// gRPC automatically moves work off I/O threads
class CacheServiceImpl extends CacheServiceGrpc.CacheServiceImplBase {
    public void put(...) {
        // This runs on executor thread, NOT I/O thread
        cache.putAsync(key, value).whenComplete((result, ex) -> {
            // Non-blocking callback
        });
    }
}
```

gRPC enforces a clean separation between I/O threads and application threads, which is a best practice that the older Infinispan protocols don't follow.

---

## Implementation Challenges and Solutions

### Challenge 1: Null Transport Field

**Problem:**
```java
public abstract class AbstractProtocolServer<T> {
    protected NettyTransport transport;  // gRPC leaves this null

    protected void registerMetrics() {
        // BOOM! NullPointerException
        metricsRegistry.registerExternalMetrics(transport);
    }
}
```

**Solution:**
```java
public class GrpcServer extends AbstractProtocolServer<GrpcServerConfiguration> {
    @Override
    protected void registerMetrics() {
        // Skip metrics registration - transport is null by design
        // TODO: Future enhancement - register gRPC-specific metrics
        //       using io.grpc.InternalInstrumentation
    }
}
```

**Why this works:**
- Metrics registration is optional for protocol servers
- gRPC provides its own metrics through `io.grpc.InternalInstrumentation`
- We can add gRPC-specific metrics in a future enhancement

### Challenge 2: Channel Initializer Contract

**Problem:**
```java
public abstract class AbstractProtocolServer<T> {
    // This is called during startup
    public abstract ChannelInitializer<Channel> getInitializer();
}
```

For standard protocols, this initializer sets up the pipeline:
```java
public class HotRodServer extends AbstractProtocolServer {
    public ChannelInitializer<Channel> getInitializer() {
        return new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast(new HotRodDecoder());
                ch.pipeline().addLast(new HotRodEncoder());
                ch.pipeline().addLast(new HotRodHandler());
            }
        };
    }
}
```

For gRPC, **the pipeline is managed entirely by NettyServerBuilder**, so this initializer is never actually used.

**Solution:**
```java
public class GrpcServer extends AbstractProtocolServer {
    @Override
    public ChannelInitializer<Channel> getInitializer() {
        // Return a minimal initializer for compatibility
        // This is never actually used because gRPC manages its own pipeline
        return new NettyInitializers(
            new NettyChannelInitializer<>(this, transport, null, null),
            new GrpcChannelInitializer(this)
        );
    }
}
```

**Why this works:**
- The initializer is only called if using Infinispan's `NettyTransport`
- Since gRPC uses `NettyServerBuilder`, this code path is never executed
- We provide an implementation to satisfy the abstract method contract
- In the future, if single-port mode is needed, this initializer could be used

### Challenge 3: Encoder/Decoder Methods

**Problem:**
```java
public abstract class AbstractProtocolServer<T> {
    public abstract ChannelOutboundHandler getEncoder();
    public abstract ChannelInboundHandler getDecoder();
}
```

These are used to build the Netty pipeline, but gRPC handles encoding/decoding internally.

**Solution:**
```java
public class GrpcServer extends AbstractProtocolServer {
    @Override
    public ChannelOutboundHandler getEncoder() {
        return null;  // gRPC handles encoding internally
    }

    @Override
    public ChannelInboundHandler getDecoder() {
        return null;  // gRPC handles decoding internally
    }
}
```

**Why this works:**
- These methods are only used when building Infinispan's `NettyTransport` pipeline
- Since gRPC doesn't use that pipeline, returning null is safe
- The base class checks for null before using these handlers

### Challenge 4: Transport Lifecycle

**Problem:**
```java
public abstract class AbstractProtocolServer<T> {
    protected void startInternal() {
        // Standard protocols do:
        transport = new NettyTransport(configuration, channelInitializer);
        super.startInternal();  // Sets up common infrastructure
    }

    protected void startTransport() {
        transport.start();  // Binds to port and starts accepting connections
    }
}
```

**Solution:**
```java
public class GrpcServer extends AbstractProtocolServer {
    private Server grpcServer;  // io.grpc.Server

    @Override
    protected void startInternal() {
        // Build gRPC server (doesn't start yet)
        grpcServer = NettyServerBuilder
            .forAddress(new InetSocketAddress(configuration.host(), configuration.port()))
            .addService(cacheService)
            .maxInboundMessageSize(configuration.maxInboundMessageSize())
            .build();

        // Call parent to set up common infrastructure
        super.startInternal();
    }

    @Override
    protected void startTransport() {
        // Start gRPC server (binds to port)
        grpcServer.start();
        log.infof("gRPC server started successfully on port %s", configuration.port());
    }
}
```

**Why this works:**
- We still follow the two-phase startup (internal setup, then transport start)
- `grpcServer` replaces the role of `transport`
- The lifecycle hooks are preserved, maintaining compatibility with the server framework

---

## Why Other Protocols Don't Need Their Own Transport

### HotRod

**Wire Format:**
```
[MAGIC][VERSION][MSG_ID][OP_CODE][CACHE_NAME_LEN][CACHE_NAME][KEY_LEN][KEY][VALUE_LEN][VALUE]
```

**Characteristics:**
- Simple binary protocol
- Request-response pattern
- No multiplexing needed
- Custom wire format designed for Infinispan

**Transport Needs:**
- Basic TCP with custom framing
- Length-prefixed messages
- Single outstanding request per connection
- ✅ Perfectly suited for `NettyTransport`

### REST

**Wire Format:**
```
GET /rest/v2/caches/myCache/myKey HTTP/1.1
Host: localhost:11222
Accept: application/json
```

**Characteristics:**
- HTTP/1.1 protocol
- Stateless request-response
- Text-based (JSON/XML)
- Standard HTTP semantics

**Transport Needs:**
- HTTP codec (`HttpServerCodec`)
- REST routing (`RestRequestHandler`)
- JSON marshalling
- ✅ Infinispan provides HTTP support in `NettyTransport`

### RESP (Redis Protocol)

**Wire Format:**
```
*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n
```

**Characteristics:**
- Text-based protocol
- Simple commands
- Single-threaded Redis semantics
- Standardized wire format

**Transport Needs:**
- Line-based framing (`\r\n` delimiters)
- Command parsing
- Response formatting
- ✅ Simple enough for `NettyTransport` with custom handlers

### Memcached

**Wire Format:**
```
set mykey 0 0 5\r\nmyvalue\r\n
```

**Characteristics:**
- Text or binary protocol
- Get/Set operations
- Simple key-value semantics
- Standardized protocol

**Transport Needs:**
- Line-based or length-prefixed framing
- Command parsing
- ASCII or binary mode
- ✅ Well-suited for `NettyTransport`

### Summary: Why gRPC is Unique

| Aspect | Standard Protocols | gRPC |
|--------|-------------------|------|
| **Protocol Complexity** | Simple request-response | HTTP/2 multiplexing, streaming |
| **Framing** | Custom or simple HTTP/1.1 | HTTP/2 frames (HEADERS, DATA, etc.) |
| **Marshalling** | Manual serialization | Automatic protobuf with reflection |
| **Threading** | I/O threads handle requests | Separate executor for app logic |
| **Service Model** | Monolithic handler | Service-oriented with code generation |
| **Extensibility** | Custom Netty handlers | Interceptor chain |
| **Standards** | Infinispan-specific or simple standards | Full gRPC ecosystem (health, reflection, etc.) |

**Conclusion:** gRPC is the only protocol that requires its own transport because it's not just a wire format—it's a complete application framework built on HTTP/2 with opinionated design choices that cannot be decomposed into simple Netty handlers.

---

## Future Enhancements

### 1. Single-Port Support (Protocol Detection)

Currently, gRPC runs on a dedicated port (9090). To enable single-port mode:

**Challenge:** Detect gRPC vs other protocols on the same port
```
Connection arrives on port 11222
  ↓
Is it HTTP/2 with gRPC content-type?
  YES → Route to gRPC server
  NO → Check HotRod magic byte (0xA0)
    YES → Route to HotRod
    NO → Check for HTTP/1.1
      YES → Route to REST
      NO → Try RESP or Memcached
```

**Solution:** Use Netty's `ProtocolDetector`
```java
public class GrpcServer extends AbstractProtocolServer {
    @Override
    public void installDetector(Channel ch) {
        ch.pipeline().addLast("grpc-detector", new ChannelInboundHandlerAdapter() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf buf = (ByteBuf) msg;

                // HTTP/2 connection preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
                if (buf.readableBytes() >= 24) {
                    byte[] preface = new byte[24];
                    buf.getBytes(0, preface);

                    if (Arrays.equals(preface, Http2CodecUtil.connectionPrefaceBuf().array())) {
                        // It's HTTP/2, likely gRPC
                        // Hand off to gRPC server
                        handOffToGrpcServer(ctx, ch);
                        return;
                    }
                }

                // Not gRPC, pass to next detector
                ctx.fireChannelRead(msg);
            }
        });
    }
}
```

**Complexity:** High - requires bridging between Infinispan's transport and gRPC's server

### 2. Metrics Integration

Add gRPC-specific metrics:

```java
@Override
protected void registerMetrics() {
    // Use gRPC's built-in instrumentation
    InternalInstrumentation instrumentation =
        InternalInstrumentation.instance();

    metricsRegistry.register("grpc.server.calls.started",
        instrumentation.getCallsStarted());
    metricsRegistry.register("grpc.server.calls.succeeded",
        instrumentation.getCallsSucceeded());
    metricsRegistry.register("grpc.server.calls.failed",
        instrumentation.getCallsFailed());
}
```

### 3. Sharing Event Loop Groups

Optimization to reduce thread count:

```java
public class GrpcServer extends AbstractProtocolServer {
    @Override
    protected void startInternal() {
        // Reuse Infinispan's event loops instead of creating new ones
        EventLoopGroup sharedWorkerGroup = getSharedWorkerGroup();

        grpcServer = NettyServerBuilder
            .forAddress(address)
            .workerEventLoopGroup(sharedWorkerGroup)
            .bossEventLoopGroup(sharedWorkerGroup)
            .channelType(NioServerSocketChannel.class)
            .addService(cacheService)
            .build();
    }
}
```

**Benefit:** Reduce thread overhead when running multiple protocols

---

## Recommendations

### For the Current Implementation

1. ✅ **Accept the dual-transport model** - gRPC's architecture necessitates this approach
2. ✅ **Document the limitations** - Clearly state that gRPC requires a dedicated port in initial version
3. ✅ **Override problematic methods** - Provide safe no-op implementations for transport-dependent methods
4. ✅ **Add TODO comments** - Mark areas for future enhancement (metrics, single-port, etc.)

### For Future Protocol Additions

When adding new protocols, consider:

- **Can it use `NettyTransport`?** If yes, follow the standard pattern (HotRod, REST, RESP, Memcached)
- **Does it require special framing?** HTTP/2, QUIC, WebSockets might need special handling
- **Is there an existing server library?** If yes (like gRPC), it might manage its own transport
- **Can it share event loops?** Even with separate transports, sharing event loops reduces overhead

### For AbstractProtocolServer Refactoring

Consider evolving the base class to better support both models:

```java
public abstract class AbstractProtocolServer<T> {
    // Make transport optional
    @Nullable
    protected NettyTransport transport;

    // Add lifecycle hook for servers that don't use NettyTransport
    protected boolean usesInternalTransport() {
        return false;  // Override to return true for gRPC, QUIC, etc.
    }

    @Override
    protected void registerMetrics() {
        if (transport != null) {
            // Standard metrics registration
            metricsRegistry.registerExternalMetrics(transport);
        } else {
            // Allow protocol to register its own metrics
            registerProtocolMetrics(metricsRegistry);
        }
    }

    // Hook for protocols with internal transport
    protected void registerProtocolMetrics(MetricsRegistry registry) {
        // No-op by default
    }
}
```

---

## Conclusion

The gRPC endpoint is **architecturally unique** among Infinispan's protocol implementations because:

1. **It's a complete framework**, not just a wire protocol
2. **It manages its own Netty server** through `NettyServerBuilder`
3. **It requires HTTP/2** with all its complexity (multiplexing, flow control, HPACK)
4. **It has opinionated designs** that cannot be decomposed into simple Netty handlers

This necessitates a dual-transport model where:
- **Standard protocols** (HotRod, REST, RESP, Memcached) share Infinispan's `NettyTransport`
- **gRPC** uses its own `io.grpc.Server` with internal Netty management

The current implementation handles this by:
- Leaving the `transport` field null
- Overriding transport-dependent methods to prevent NPE
- Using `NettyServerBuilder` directly instead of `NettyTransport`
- Documenting the architectural difference for future maintainers

This approach is pragmatic and correct given the constraints. Future enhancements can add single-port support and metrics integration, but the fundamental dual-transport architecture is sound and necessary.
