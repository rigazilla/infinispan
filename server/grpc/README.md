# Infinispan gRPC Server

gRPC protocol endpoint for Infinispan Server providing language-agnostic cache operations over HTTP/2.

## Configuration

Add to `server/conf/infinispan.xml`:

```xml
<socket-bindings>
   <socket-binding name="grpc" port="9090"/>
</socket-bindings>

<endpoints>
   <endpoint>
      <grpc-connector socket-binding="grpc" name="grpc"/>
   </endpoint>
</endpoints>
```

## Protocol Buffers

Service definitions in `src/main/resources/proto/`:
- `common.proto` - Common types
- `cache.proto` - CacheService (Put, Get, Remove, ContainsKey, Size, Clear)

## Operations

| Operation | Description |
|-----------|-------------|
| `Put` | Store key-value with optional lifespan |
| `Get` | Retrieve value by key |
| `Remove` | Delete entry |
| `ContainsKey` | Check if key exists |
| `Size` | Get entry count |
| `Clear` | Remove all entries |

## Example Client

See `examples/go-client/` for a working Go client demonstrating all operations.

## Generating Clients

**Go:**
```bash
protoc -I=src/main/resources/proto \
  --go_out=. --go-grpc_out=. \
  src/main/resources/proto/*.proto
```

**Python:**
```bash
python -m grpc_tools.protoc -I=src/main/resources/proto \
  --python_out=. --grpc_python_out=. \
  src/main/resources/proto/*.proto
```

**JavaScript:**
```bash
protoc -I=src/main/resources/proto \
  --js_out=import_style=commonjs:. \
  --grpc-web_out=import_style=commonjs:. \
  src/main/resources/proto/*.proto
```

## Testing

```bash
# Start server
./bin/server.sh -c infinispan.xml

# Create cache
curl -X POST http://localhost:11222/rest/v2/caches/mycache -d '{}'

# Run Go client example
cd examples/go-client && go run main.go
```

## Documentation

Architecture and design documentation in `AI-docs/`.
