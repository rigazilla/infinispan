# Infinispan gRPC Go Client Example

This is a demonstration Go client for the Infinispan gRPC endpoint.

## Prerequisites

- Go 1.21 or later
- Protocol Buffers compiler (`protoc`)
- Infinispan Server with gRPC endpoint running on `localhost:9090`

## Setup

### 1. Generate Go code from .proto files

The `generate.sh` script copies proto files from the server and generates Go code:

```bash
./generate.sh
```

This will:
- Copy `.proto` files from `../../src/main/resources/proto/`
- Install `protoc-gen-go` and `protoc-gen-go-grpc` if needed
- Generate Go code in `api/proto/`

### 2. Install dependencies

```bash
go mod tidy
```

## Running the Example

### 1. Start Infinispan Server

The example includes `infinispan.xml` with the gRPC connector configured on port 9090.

Copy it to your server's conf directory and start:

```bash
# Copy the config
cp infinispan.xml /path/to/infinispan-server/server/conf/

# Start server
cd /path/to/infinispan-server
./bin/server.sh -c infinispan.xml
```

The server should show:
```
INFO  [o.i.s.g.GrpcServer] Starting gRPC server on 127.0.0.1:9090
INFO  [o.i.s.g.GrpcServer] gRPC server started successfully on port 9090
```

### 2. Create a test cache

```bash
curl -X POST http://localhost:11222/rest/v2/caches/testcache -H "Content-Type: application/json" -d '{}'
```

### 3. Run the client

```bash
go run main.go
```

## Example Output

```
🚀 Infinispan gRPC Go Client Demo
=====================================

Test 1: Put key-value pair
✓ Put successful: true

Test 2: Get value by key
✓ Get successful: true
  Value: Hello from Go!

Test 3: Check if key exists
✓ ContainsKey successful: true
  Key exists: true

Test 4: Put with lifespan (5 seconds)
✓ Put with lifespan successful: true

Test 5: Get cache size
✓ Size successful: true
  Cache size: 2 entries

Test 6: Put multiple entries
✓ Successfully added 5 more entries

Test 7: Get cache size after adding entries
✓ Cache size: 7 entries

Test 8: Remove a key
✓ Remove successful: true
  Removed value was: value1

Test 9: Check if removed key still exists
✓ Key exists: false (should be false)

Test 10: Wait 6 seconds and check if tempkey expired
  Waiting......
✓ Tempkey exists: false (should be false after expiration)

Test 11: Clear entire cache
✓ Clear successful: true

Test 12: Get cache size after clear
✓ Cache size: 0 entries (should be 0)

=====================================
✅ All tests completed successfully!
```

## What the Demo Tests

The example demonstrates all available gRPC operations:

1. **Put** - Store a key-value pair
2. **Get** - Retrieve a value by key
3. **ContainsKey** - Check if a key exists
4. **Put with Lifespan** - Store with automatic expiration
5. **Size** - Get number of entries in cache
6. **Remove** - Delete a key-value pair
7. **Clear** - Remove all entries from cache
8. **TTL Expiration** - Verify time-based expiration works

## API Usage

### Basic Put/Get

```go
client := pb.NewCacheServiceClient(conn)

// Put
putResp, err := client.Put(ctx, &pb.PutRequest{
    CacheName: "mycache",
    Key:       []byte("mykey"),
    Value:     []byte("myvalue"),
})

// Get
getResp, err := client.Get(ctx, &pb.GetRequest{
    CacheName: "mycache",
    Key:       []byte("mykey"),
})
fmt.Println(string(getResp.Value))
```

### Put with Expiration

```go
// Entry expires after 10 seconds
putResp, err := client.Put(ctx, &pb.PutRequest{
    CacheName:  "mycache",
    Key:        []byte("tempkey"),
    Value:      []byte("tempvalue"),
    LifespanMs: func(i int64) *int64 { return &i }(10000),
})
```

### Other Operations

```go
// Remove
removeResp, err := client.Remove(ctx, &pb.RemoveRequest{
    CacheName: "mycache",
    Key:       []byte("mykey"),
})

// Check if key exists
containsResp, err := client.ContainsKey(ctx, &pb.ContainsKeyRequest{
    CacheName: "mycache",
    Key:       []byte("mykey"),
})

// Get cache size
sizeResp, err := client.Size(ctx, &pb.SizeRequest{
    CacheName: "mycache",
})

// Clear all entries
clearResp, err := client.Clear(ctx, &pb.ClearRequest{
    CacheName: "mycache",
})
```

## Error Handling

All responses include a `Status` field:

```go
if resp.Status.Success {
    fmt.Println("Operation succeeded")
} else {
    fmt.Printf("Operation failed: %s\n", resp.Status.ErrorMessage)
}
```

## Protocol Buffers

The client is generated from the server's `.proto` files:

- **common.proto** - Common types (Status message)
- **cache.proto** - CacheService definition with all operations

These files are located in `../../src/main/resources/proto/` and are copied to `proto/` by the `generate.sh` script.

## Building Clients in Other Languages

Use the proto files from `../../src/main/resources/proto/`:

### Python

```bash
python -m grpc_tools.protoc -I=../../src/main/resources/proto \
  --python_out=. --grpc_python_out=. \
  ../../src/main/resources/proto/*.proto
```

### JavaScript/Node.js

```bash
protoc -I=../../src/main/resources/proto \
  --js_out=import_style=commonjs:. \
  --grpc-web_out=import_style=commonjs:. \
  ../../src/main/resources/proto/*.proto
```

### C++

```bash
protoc -I=../../src/main/resources/proto \
  --cpp_out=. --grpc_out=. \
  --plugin=protoc-gen-grpc=`which grpc_cpp_plugin` \
  ../../src/main/resources/proto/*.proto
```

## Project Structure

```
go-client/
├── main.go              # Client application
├── go.mod               # Go module definition
├── go.sum               # Dependency checksums
├── generate.sh          # Script to generate proto code
├── infinispan.xml       # Server config with gRPC connector enabled
├── .gitignore           # Excludes generated files
├── README.md            # This file
├── proto/               # Protocol Buffer definitions (generated by generate.sh)
│   ├── common.proto     # Copied from ../../src/main/resources/proto/
│   └── cache.proto      # Copied from ../../src/main/resources/proto/
└── api/proto/           # Generated Go code (generated by generate.sh)
    ├── common.pb.go
    ├── cache.pb.go
    └── cache_grpc.pb.go
```

## License

This example is part of the Infinispan project and uses the same license as Infinispan.
