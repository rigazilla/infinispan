package org.infinispan.server.grpc.services;

import java.util.concurrent.TimeUnit;

import org.infinispan.AdvancedCache;
import org.infinispan.server.grpc.GrpcServer;
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
import org.infinispan.server.grpc.proto.CommonProto.Status;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

/**
 * gRPC Cache Service Implementation
 *
 * Implements all basic CRUD operations for Infinispan caches via gRPC.
 * Uses async cache operations for better performance.
 *
 * @since 16.2
 */
public class CacheServiceImpl extends CacheServiceGrpc.CacheServiceImplBase {

   private final GrpcServer grpcServer;

   public CacheServiceImpl(GrpcServer grpcServer) {
      this.grpcServer = grpcServer;
   }

   @Override
   public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Prepare key and value
         byte[] key = request.getKey().toByteArray();
         byte[] value = request.getValue().toByteArray();

         // Execute async put operation with optional lifespan
         if (request.hasLifespanMs() || request.hasMaxIdleMs()) {
            long lifespan = request.hasLifespanMs() ? request.getLifespanMs() : -1;
            long maxIdle = request.hasMaxIdleMs() ? request.getMaxIdleMs() : -1;
            cache.putAsync(key, value, lifespan, TimeUnit.MILLISECONDS, maxIdle, TimeUnit.MILLISECONDS)
                  .whenComplete((prev, throwable) -> handlePutResponse(responseObserver, prev, throwable));
         } else {
            cache.putAsync(key, value)
                  .whenComplete((prev, throwable) -> handlePutResponse(responseObserver, prev, throwable));
         }
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Put operation failed: " + e.getMessage())
               .asException());
      }
   }

   private void handlePutResponse(StreamObserver<PutResponse> responseObserver, byte[] previousValue, Throwable throwable) {
      if (throwable != null) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Put operation failed: " + throwable.getMessage())
               .asException());
      } else {
         PutResponse.Builder response = PutResponse.newBuilder()
               .setStatus(Status.newBuilder().setSuccess(true));
         if (previousValue != null) {
            response.setPreviousValue(ByteString.copyFrom(previousValue));
         }
         responseObserver.onNext(response.build());
         responseObserver.onCompleted();
      }
   }

   @Override
   public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Prepare key
         byte[] key = request.getKey().toByteArray();

         // Execute async get operation
         cache.getAsync(key).whenComplete((value, throwable) -> {
            if (throwable != null) {
               responseObserver.onError(io.grpc.Status.INTERNAL
                     .withDescription("Get operation failed: " + throwable.getMessage())
                     .asException());
            } else {
               GetResponse.Builder response = GetResponse.newBuilder()
                     .setStatus(Status.newBuilder().setSuccess(true));
               if (value != null) {
                  response.setValue(ByteString.copyFrom(value));
               }
               responseObserver.onNext(response.build());
               responseObserver.onCompleted();
            }
         });
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Get operation failed: " + e.getMessage())
               .asException());
      }
   }

   @Override
   public void remove(RemoveRequest request, StreamObserver<RemoveResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Prepare key
         byte[] key = request.getKey().toByteArray();

         // Execute async remove operation
         cache.removeAsync(key).whenComplete((previousValue, throwable) -> {
            if (throwable != null) {
               responseObserver.onError(io.grpc.Status.INTERNAL
                     .withDescription("Remove operation failed: " + throwable.getMessage())
                     .asException());
            } else {
               RemoveResponse.Builder response = RemoveResponse.newBuilder()
                     .setStatus(Status.newBuilder().setSuccess(true));
               if (previousValue != null) {
                  response.setPreviousValue(ByteString.copyFrom(previousValue));
               }
               responseObserver.onNext(response.build());
               responseObserver.onCompleted();
            }
         });
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Remove operation failed: " + e.getMessage())
               .asException());
      }
   }

   @Override
   public void containsKey(ContainsKeyRequest request, StreamObserver<ContainsKeyResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Prepare key
         byte[] key = request.getKey().toByteArray();

         // Execute async containsKey operation
         cache.containsKeyAsync(key).whenComplete((contains, throwable) -> {
            if (throwable != null) {
               responseObserver.onError(io.grpc.Status.INTERNAL
                     .withDescription("ContainsKey operation failed: " + throwable.getMessage())
                     .asException());
            } else {
               ContainsKeyResponse response = ContainsKeyResponse.newBuilder()
                     .setStatus(Status.newBuilder().setSuccess(true))
                     .setContains(contains != null && contains)
                     .build();
               responseObserver.onNext(response);
               responseObserver.onCompleted();
            }
         });
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("ContainsKey operation failed: " + e.getMessage())
               .asException());
      }
   }

   @Override
   public void size(SizeRequest request, StreamObserver<SizeResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Execute async size operation
         cache.sizeAsync().whenComplete((size, throwable) -> {
            if (throwable != null) {
               responseObserver.onError(io.grpc.Status.INTERNAL
                     .withDescription("Size operation failed: " + throwable.getMessage())
                     .asException());
            } else {
               SizeResponse response = SizeResponse.newBuilder()
                     .setStatus(Status.newBuilder().setSuccess(true))
                     .setSize(size != null ? size : 0L)
                     .build();
               responseObserver.onNext(response);
               responseObserver.onCompleted();
            }
         });
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Size operation failed: " + e.getMessage())
               .asException());
      }
   }

   @Override
   public void clear(ClearRequest request, StreamObserver<ClearResponse> responseObserver) {
      try {
         // Get cache
         AdvancedCache<byte[], byte[]> cache = getCache(request.getCacheName());

         // Execute async clear operation
         cache.clearAsync().whenComplete((voidResult, throwable) -> {
            if (throwable != null) {
               responseObserver.onError(io.grpc.Status.INTERNAL
                     .withDescription("Clear operation failed: " + throwable.getMessage())
                     .asException());
            } else {
               ClearResponse response = ClearResponse.newBuilder()
                     .setStatus(Status.newBuilder().setSuccess(true))
                     .build();
               responseObserver.onNext(response);
               responseObserver.onCompleted();
            }
         });
      } catch (Exception e) {
         responseObserver.onError(io.grpc.Status.INTERNAL
               .withDescription("Clear operation failed: " + e.getMessage())
               .asException());
      }
   }

   /**
    * Get the cache with the specified name, or the default cache if name is empty
    */
   private AdvancedCache<byte[], byte[]> getCache(String cacheName) {
      String name = cacheName;
      if (name == null || name.isEmpty()) {
         name = grpcServer.getConfiguration().defaultCacheName();
      }

      if (name == null || name.isEmpty()) {
         throw new IllegalArgumentException("Cache name is required");
      }

      return grpcServer.getCacheManager()
            .<byte[], byte[]>getCache(name)
            .getAdvancedCache();
   }
}
