package org.infinispan.server.grpc;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.core.AbstractProtocolServer;
import org.infinispan.server.core.transport.NettyChannelInitializer;
import org.infinispan.server.core.transport.NettyInitializers;
import org.infinispan.server.grpc.configuration.GrpcServerConfiguration;
import org.infinispan.server.grpc.services.CacheServiceImpl;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.group.ChannelMatcher;

/**
 * gRPC Protocol Server for Infinispan
 *
 * Provides a gRPC endpoint for cache operations with clean protobuf-based API
 * for easy client development across multiple languages.
 *
 * @since 16.2
 */
public class GrpcServer extends AbstractProtocolServer<GrpcServerConfiguration> {

   private Server grpcServer;
   private CacheServiceImpl cacheService;

   public GrpcServer() {
      super("gRPC");
   }

   @Override
   protected void startInternal() {
      // Initialize the cache service
      cacheService = new CacheServiceImpl(this);

      // Build gRPC server
      InetSocketAddress address = new InetSocketAddress(configuration.host(), configuration.port());

      grpcServer = NettyServerBuilder
            .forAddress(address)
            .addService(cacheService)
            .maxInboundMessageSize(configuration.maxInboundMessageSize())
            .maxInboundMetadataSize(configuration.maxInboundMetadataSize())
            .build();

      super.startInternal();
   }

   @Override
   protected void startTransport() {
      log.infof("Starting gRPC server on %s:%s", configuration.host(), configuration.port());
      try {
         grpcServer.start();
         log.infof("gRPC server started successfully on port %s", configuration.port());
      } catch (IOException e) {
         log.errorf(e, "Failed to start gRPC server on %s:%s", configuration.host(), configuration.port());
         throw new RuntimeException("Failed to start gRPC server", e);
      }

      // Note: We don't call registerServerMBeans() because gRPC manages its own Netty transport
      // and the transport field is null in this implementation
   }

   @Override
   protected void startCaches() {
      // gRPC doesn't need a default cache - clients specify cache name in each request
   }

   @Override
   protected void registerMetrics() {
      // Skip metrics registration since transport is null (gRPC manages its own Netty server)
      // TODO: Add gRPC-specific metrics in future enhancement
   }

   @Override
   public void stop() {
      if (grpcServer != null) {
         try {
            grpcServer.shutdown();
            grpcServer.awaitTermination();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Interrupted while stopping gRPC server");
         }
      }
      super.stop();
   }

   @Override
   public ChannelOutboundHandler getEncoder() {
      // gRPC handles encoding internally
      return null;
   }

   @Override
   public ChannelInboundHandler getDecoder() {
      // gRPC handles decoding internally
      return null;
   }

   @Override
   public ChannelInitializer<Channel> getInitializer() {
      // Provide a minimal initializer for compatibility
      return new NettyInitializers(
            new NettyChannelInitializer<>(this, transport, null, null),
            new GrpcChannelInitializer(this)
      );
   }

   @Override
   public ChannelMatcher getChannelMatcher() {
      // Match all channels for this server
      return channel -> true;
   }

   @Override
   public void installDetector(Channel ch) {
      // Protocol detection not supported in initial gRPC implementation
      // Single-port mode can be added in future enhancement
   }

   @Override
   protected String protocolType() {
      return "gRPC";
   }

   /**
    * Get the cache manager for service implementations
    */
   public EmbeddedCacheManager getCacheManager() {
      return cacheManager;
   }

   /**
    * Get the configuration for service implementations
    */
   @Override
   public GrpcServerConfiguration getConfiguration() {
      return configuration;
   }

   private static final org.infinispan.server.core.logging.Log log =
         org.infinispan.server.core.logging.Log.getLog(GrpcServer.class);
}
