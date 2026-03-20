package org.infinispan.server.grpc;

import org.infinispan.server.core.transport.NettyInitializer;

import io.netty.channel.Channel;

/**
 * gRPC Channel Initializer
 *
 * Minimal implementation since gRPC manages its own Netty pipeline.
 * This is primarily for compatibility with the Infinispan server infrastructure.
 *
 * @since 16.2
 */
public class GrpcChannelInitializer implements NettyInitializer {

   private final GrpcServer server;

   public GrpcChannelInitializer(GrpcServer server) {
      this.server = server;
   }

   @Override
   public void initializeChannel(Channel ch) {
      // gRPC manages its own pipeline, so we don't need to add handlers here
      // This method is kept for compatibility with the Infinispan server framework
   }
}
