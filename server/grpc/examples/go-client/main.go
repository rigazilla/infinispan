package main

import (
	"context"
	"fmt"
	"log"
	"time"

	pb "github.com/infinispan/grpc-go-client/api/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const (
	serverAddress = "localhost:9090"
	cacheName     = "testcache"
)

func main() {
	// Connect to the gRPC server
	conn, err := grpc.NewClient(
		serverAddress,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}
	defer conn.Close()

	// Create cache service client
	client := pb.NewCacheServiceClient(conn)

	// Create context with timeout
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	fmt.Println("🚀 Infinispan gRPC Go Client Demo")
	fmt.Println("=====================================\n")

	// Test 1: Put operation
	fmt.Println("Test 1: Put key-value pair")
	putResp, err := client.Put(ctx, &pb.PutRequest{
		CacheName: cacheName,
		Key:       []byte("mykey"),
		Value:     []byte("Hello from Go!"),
	})
	if err != nil {
		log.Fatalf("Put failed: %v", err)
	}
	fmt.Printf("✓ Put successful: %v\n", putResp.Status.Success)
	if putResp.PreviousValue != nil {
		fmt.Printf("  Previous value was: %s\n", string(putResp.PreviousValue))
	}
	fmt.Println()

	// Test 2: Get operation
	fmt.Println("Test 2: Get value by key")
	getResp, err := client.Get(ctx, &pb.GetRequest{
		CacheName: cacheName,
		Key:       []byte("mykey"),
	})
	if err != nil {
		log.Fatalf("Get failed: %v", err)
	}
	fmt.Printf("✓ Get successful: %v\n", getResp.Status.Success)
	if getResp.Value != nil {
		fmt.Printf("  Value: %s\n", string(getResp.Value))
	}
	fmt.Println()

	// Test 3: ContainsKey operation
	fmt.Println("Test 3: Check if key exists")
	containsResp, err := client.ContainsKey(ctx, &pb.ContainsKeyRequest{
		CacheName: cacheName,
		Key:       []byte("mykey"),
	})
	if err != nil {
		log.Fatalf("ContainsKey failed: %v", err)
	}
	fmt.Printf("✓ ContainsKey successful: %v\n", containsResp.Status.Success)
	fmt.Printf("  Key exists: %v\n", containsResp.Contains)
	fmt.Println()

	// Test 4: Put with lifespan
	fmt.Println("Test 4: Put with lifespan (5 seconds)")
	putResp2, err := client.Put(ctx, &pb.PutRequest{
		CacheName:  cacheName,
		Key:        []byte("tempkey"),
		Value:      []byte("This will expire"),
		LifespanMs: func(i int64) *int64 { return &i }(5000),
	})
	if err != nil {
		log.Fatalf("Put with lifespan failed: %v", err)
	}
	fmt.Printf("✓ Put with lifespan successful: %v\n", putResp2.Status.Success)
	fmt.Println()

	// Test 5: Size operation
	fmt.Println("Test 5: Get cache size")
	sizeResp, err := client.Size(ctx, &pb.SizeRequest{
		CacheName: cacheName,
	})
	if err != nil {
		log.Fatalf("Size failed: %v", err)
	}
	fmt.Printf("✓ Size successful: %v\n", sizeResp.Status.Success)
	fmt.Printf("  Cache size: %d entries\n", sizeResp.Size)
	fmt.Println()

	// Test 6: Put more entries
	fmt.Println("Test 6: Put multiple entries")
	for i := 1; i <= 5; i++ {
		_, err := client.Put(ctx, &pb.PutRequest{
			CacheName: cacheName,
			Key:       []byte(fmt.Sprintf("key%d", i)),
			Value:     []byte(fmt.Sprintf("value%d", i)),
		})
		if err != nil {
			log.Fatalf("Put entry %d failed: %v", i, err)
		}
	}
	fmt.Printf("✓ Successfully added 5 more entries\n")
	fmt.Println()

	// Test 7: Size again
	fmt.Println("Test 7: Get cache size after adding entries")
	sizeResp2, err := client.Size(ctx, &pb.SizeRequest{
		CacheName: cacheName,
	})
	if err != nil {
		log.Fatalf("Size failed: %v", err)
	}
	fmt.Printf("✓ Cache size: %d entries\n", sizeResp2.Size)
	fmt.Println()

	// Test 8: Remove operation
	fmt.Println("Test 8: Remove a key")
	removeResp, err := client.Remove(ctx, &pb.RemoveRequest{
		CacheName: cacheName,
		Key:       []byte("key1"),
	})
	if err != nil {
		log.Fatalf("Remove failed: %v", err)
	}
	fmt.Printf("✓ Remove successful: %v\n", removeResp.Status.Success)
	if removeResp.PreviousValue != nil {
		fmt.Printf("  Removed value was: %s\n", string(removeResp.PreviousValue))
	}
	fmt.Println()

	// Test 9: ContainsKey on removed entry
	fmt.Println("Test 9: Check if removed key still exists")
	containsResp2, err := client.ContainsKey(ctx, &pb.ContainsKeyRequest{
		CacheName: cacheName,
		Key:       []byte("key1"),
	})
	if err != nil {
		log.Fatalf("ContainsKey failed: %v", err)
	}
	fmt.Printf("✓ Key exists: %v (should be false)\n", containsResp2.Contains)
	fmt.Println()

	// Test 10: Wait and check if tempkey expired
	fmt.Println("Test 10: Wait 6 seconds and check if tempkey expired")
	fmt.Print("  Waiting")
	for i := 0; i < 6; i++ {
		time.Sleep(1 * time.Second)
		fmt.Print(".")
	}
	fmt.Println()

	containsResp3, err := client.ContainsKey(ctx, &pb.ContainsKeyRequest{
		CacheName: cacheName,
		Key:       []byte("tempkey"),
	})
	if err != nil {
		log.Fatalf("ContainsKey failed: %v", err)
	}
	fmt.Printf("✓ Tempkey exists: %v (should be false after expiration)\n", containsResp3.Contains)
	fmt.Println()

	// Test 11: Clear cache
	fmt.Println("Test 11: Clear entire cache")
	clearResp, err := client.Clear(ctx, &pb.ClearRequest{
		CacheName: cacheName,
	})
	if err != nil {
		log.Fatalf("Clear failed: %v", err)
	}
	fmt.Printf("✓ Clear successful: %v\n", clearResp.Status.Success)
	fmt.Println()

	// Test 12: Size after clear
	fmt.Println("Test 12: Get cache size after clear")
	sizeResp3, err := client.Size(ctx, &pb.SizeRequest{
		CacheName: cacheName,
	})
	if err != nil {
		log.Fatalf("Size failed: %v", err)
	}
	fmt.Printf("✓ Cache size: %d entries (should be 0)\n", sizeResp3.Size)
	fmt.Println()

	fmt.Println("=====================================")
	fmt.Println("✅ All tests completed successfully!")
}
