package org.infinispan.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.AssertJUnit.assertArrayEquals;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.grpc.configuration.GrpcServerConfiguration;
import org.infinispan.server.grpc.configuration.GrpcServerConfigurationBuilder;
import org.infinispan.server.grpc.proto.CacheProto.ClearRequest;
import org.infinispan.server.grpc.proto.CacheProto.ClearResponse;
import org.infinispan.server.grpc.proto.CacheProto.ContainsKeyRequest;
import org.infinispan.server.grpc.proto.CacheProto.ContainsKeyResponse;
import org.infinispan.server.grpc.proto.CacheProto.GetRequest;
import org.infinispan.server.grpc.proto.CacheProto.GetResponse;
import org.infinispan.server.grpc.proto.CacheProto.PutRequest;
import org.infinispan.server.grpc.proto.CacheProto.PutResponse;
import org.infinispan.server.grpc.proto.CacheProto.RemoveRequest;
import org.infinispan.server.grpc.proto.CacheProto.RemoveResponse;
import org.infinispan.server.grpc.proto.CacheProto.SizeRequest;
import org.infinispan.server.grpc.proto.CacheProto.SizeResponse;
import org.infinispan.server.grpc.proto.CacheServiceGrpc;
import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Tests for basic gRPC cache operations
 *
 * Verifies all CRUD operations work correctly via gRPC:
 * - Put/Get/Remove
 * - ContainsKey
 * - Size
 * - Clear
 * - Expiration (lifespan)
 *
 * @since 16.2
 */
@Test(groups = "functional", testName = "server.grpc.GrpcCacheOperationsTest")
public class GrpcCacheOperationsTest extends AbstractInfinispanTest {

   protected static final String TEST_CACHE_NAME = "testCache";
   protected static final int GRPC_PORT = 19090;

   protected EmbeddedCacheManager cacheManager;
   protected GrpcServer grpcServer;
   protected ManagedChannel channel;
   protected CacheServiceGrpc.CacheServiceBlockingStub cacheStub;

   @BeforeClass
   public void setup() throws Exception {
      // Create cache manager
      GlobalConfigurationBuilder globalBuilder = new GlobalConfigurationBuilder().nonClusteredDefault();
      ConfigurationBuilder cacheBuilder = new ConfigurationBuilder();

      cacheManager = TestCacheManagerFactory.newDefaultCacheManager(true, globalBuilder, cacheBuilder);

      // Define test cache
      cacheManager.defineConfiguration(TEST_CACHE_NAME, cacheBuilder.build());

      // Create and start gRPC server
      GrpcServerConfiguration grpcConfig = new GrpcServerConfigurationBuilder()
            .host("127.0.0.1")
            .port(GRPC_PORT)
            .build();

      grpcServer = new GrpcServer();
      grpcServer.start(grpcConfig, cacheManager);

      // Create gRPC client channel
      channel = ManagedChannelBuilder
            .forAddress("127.0.0.1", GRPC_PORT)
            .usePlaintext()
            .build();

      cacheStub = CacheServiceGrpc.newBlockingStub(channel);
   }

   @BeforeMethod
   public void clearCache() {
      // Clear cache before each test method to ensure isolation
      cacheManager.getCache(TEST_CACHE_NAME).clear();
   }

   @AfterClass(alwaysRun = true)
   public void teardown() {
      if (channel != null) {
         channel.shutdown();
      }
      if (grpcServer != null) {
         grpcServer.stop();
      }
      if (cacheManager != null) {
         cacheManager.stop();
      }
   }

   @Test
   public void testPutAndGet() {
      // Put a key-value pair
      byte[] key = "key1".getBytes();
      byte[] value = "value1".getBytes();

      PutRequest putRequest = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

      PutResponse putResponse = cacheStub.put(putRequest);
      assertTrue(putResponse.getStatus().getSuccess());
      assertFalse(putResponse.hasPreviousValue());

      // Get the value back
      GetRequest getRequest = GetRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      GetResponse getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertTrue(getResponse.hasValue());
      assertArrayEquals(value, getResponse.getValue().toByteArray());
   }

   @Test
   public void testPutWithLifespan() throws Exception {
      // Put a key-value pair with 1 second lifespan
      byte[] key = "key2".getBytes();
      byte[] value = "value2".getBytes();

      PutRequest putRequest = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .setLifespanMs(1000) // 1 second
            .build();

      PutResponse putResponse = cacheStub.put(putRequest);
      assertTrue(putResponse.getStatus().getSuccess());

      // Value should exist immediately
      GetRequest getRequest = GetRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      GetResponse getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertTrue(getResponse.hasValue());

      // Wait for expiration
      Thread.sleep(1500);

      // Value should be expired
      getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertFalse(getResponse.hasValue());
   }

   @Test
   public void testRemove() {
      // Put a key-value pair
      byte[] key = "key3".getBytes();
      byte[] value = "value3".getBytes();

      PutRequest putRequest = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

      cacheStub.put(putRequest);

      // Remove the key
      RemoveRequest removeRequest = RemoveRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      RemoveResponse removeResponse = cacheStub.remove(removeRequest);
      assertTrue(removeResponse.getStatus().getSuccess());
      assertTrue(removeResponse.hasPreviousValue());
      assertArrayEquals(value, removeResponse.getPreviousValue().toByteArray());

      // Verify key is gone
      GetRequest getRequest = GetRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      GetResponse getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertFalse(getResponse.hasValue());
   }

   @Test
   public void testContainsKey() {
      byte[] key = "key4".getBytes();
      byte[] value = "value4".getBytes();

      // Key should not exist initially
      ContainsKeyRequest containsRequest = ContainsKeyRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      ContainsKeyResponse containsResponse = cacheStub.containsKey(containsRequest);
      assertTrue(containsResponse.getStatus().getSuccess());
      assertFalse(containsResponse.getContains());

      // Put the key
      PutRequest putRequest = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value))
            .build();

      cacheStub.put(putRequest);

      // Key should now exist
      containsResponse = cacheStub.containsKey(containsRequest);
      assertTrue(containsResponse.getStatus().getSuccess());
      assertTrue(containsResponse.getContains());
   }

   @Test
   public void testSize() {
      // Initially empty
      SizeRequest sizeRequest = SizeRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .build();

      SizeResponse sizeResponse = cacheStub.size(sizeRequest);
      assertTrue(sizeResponse.getStatus().getSuccess());
      assertEquals(0, sizeResponse.getSize());

      // Add some entries
      for (int i = 0; i < 5; i++) {
         PutRequest putRequest = PutRequest.newBuilder()
               .setCacheName(TEST_CACHE_NAME)
               .setKey(ByteString.copyFrom(("key" + i).getBytes()))
               .setValue(ByteString.copyFrom(("value" + i).getBytes()))
               .build();
         cacheStub.put(putRequest);
      }

      // Size should be 5
      sizeResponse = cacheStub.size(sizeRequest);
      assertTrue(sizeResponse.getStatus().getSuccess());
      assertEquals(5, sizeResponse.getSize());
   }

   @Test
   public void testClear() {
      // Add some entries
      for (int i = 0; i < 3; i++) {
         PutRequest putRequest = PutRequest.newBuilder()
               .setCacheName(TEST_CACHE_NAME)
               .setKey(ByteString.copyFrom(("key" + i).getBytes()))
               .setValue(ByteString.copyFrom(("value" + i).getBytes()))
               .build();
         cacheStub.put(putRequest);
      }

      // Verify size
      SizeRequest sizeRequest = SizeRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .build();

      SizeResponse sizeResponse = cacheStub.size(sizeRequest);
      assertThat(sizeResponse.getSize()).isGreaterThan(0);

      // Clear the cache
      ClearRequest clearRequest = ClearRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .build();

      ClearResponse clearResponse = cacheStub.clear(clearRequest);
      assertTrue(clearResponse.getStatus().getSuccess());

      // Size should be 0
      sizeResponse = cacheStub.size(sizeRequest);
      assertTrue(sizeResponse.getStatus().getSuccess());
      assertEquals(0, sizeResponse.getSize());
   }

   @Test
   public void testNonExistentKey() {
      // Get a non-existent key
      byte[] key = "nonexistent".getBytes();

      GetRequest getRequest = GetRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      GetResponse getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertFalse(getResponse.hasValue());
   }

   @Test
   public void testPutReplacesValue() {
      // Put initial value
      byte[] key = "key5".getBytes();
      byte[] value1 = "value5a".getBytes();
      byte[] value2 = "value5b".getBytes();

      PutRequest putRequest1 = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value1))
            .build();

      PutResponse putResponse1 = cacheStub.put(putRequest1);
      assertTrue(putResponse1.getStatus().getSuccess());
      assertFalse(putResponse1.hasPreviousValue());

      // Put new value for same key
      PutRequest putRequest2 = PutRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .setValue(ByteString.copyFrom(value2))
            .build();

      PutResponse putResponse2 = cacheStub.put(putRequest2);
      assertTrue(putResponse2.getStatus().getSuccess());
      assertTrue(putResponse2.hasPreviousValue());
      assertArrayEquals(value1, putResponse2.getPreviousValue().toByteArray());

      // Get should return new value
      GetRequest getRequest = GetRequest.newBuilder()
            .setCacheName(TEST_CACHE_NAME)
            .setKey(ByteString.copyFrom(key))
            .build();

      GetResponse getResponse = cacheStub.get(getRequest);
      assertTrue(getResponse.getStatus().getSuccess());
      assertArrayEquals(value2, getResponse.getValue().toByteArray());
   }
}
