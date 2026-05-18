package org.infinispan.server.grpc.configuration;

import org.infinispan.commons.configuration.BuiltBy;
import org.infinispan.commons.configuration.ConfigurationFor;
import org.infinispan.commons.configuration.attributes.AttributeDefinition;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.server.core.configuration.IpFilterConfiguration;
import org.infinispan.server.core.configuration.NoAuthenticationConfiguration;
import org.infinispan.server.core.configuration.ProtocolServerConfiguration;
import org.infinispan.server.core.configuration.SslConfiguration;
import org.infinispan.server.grpc.GrpcServer;

/**
 * gRPC Server Configuration
 *
 * @since 16.2
 */
@BuiltBy(GrpcServerConfigurationBuilder.class)
@ConfigurationFor(GrpcServer.class)
public class GrpcServerConfiguration extends ProtocolServerConfiguration<GrpcServerConfiguration, NoAuthenticationConfiguration> {

   public static final int DEFAULT_GRPC_PORT = 9090;

   // gRPC specific settings
   public static final AttributeDefinition<Integer> MAX_INBOUND_MESSAGE_SIZE =
         AttributeDefinition.builder("max-inbound-message-size", 4 * 1024 * 1024).immutable().build(); // 4MB
   public static final AttributeDefinition<Integer> MAX_INBOUND_METADATA_SIZE =
         AttributeDefinition.builder("max-inbound-metadata-size", 8 * 1024).immutable().build(); // 8KB

   public static AttributeSet attributeDefinitionSet() {
      return new AttributeSet(GrpcServerConfiguration.class,
            ProtocolServerConfiguration.attributeDefinitionSet(),
            MAX_INBOUND_MESSAGE_SIZE, MAX_INBOUND_METADATA_SIZE);
   }

   private final NoAuthenticationConfiguration authentication;

   GrpcServerConfiguration(AttributeSet attributes, IpFilterConfiguration ipRules, SslConfiguration ssl,
                          NoAuthenticationConfiguration authentication) {
      super("grpc-connector", attributes, authentication, ssl, ipRules);
      this.authentication = authentication;
   }

   @Override
   public NoAuthenticationConfiguration authentication() {
      return authentication;
   }

   public int maxInboundMessageSize() {
      return attributes.attribute(MAX_INBOUND_MESSAGE_SIZE).get();
   }

   public int maxInboundMetadataSize() {
      return attributes.attribute(MAX_INBOUND_METADATA_SIZE).get();
   }
}
