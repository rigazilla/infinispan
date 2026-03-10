package org.infinispan.server.grpc.configuration;

import org.infinispan.commons.configuration.Builder;
import org.infinispan.commons.configuration.Combine;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.server.core.admin.AdminOperationsHandler;
import org.infinispan.server.core.configuration.NoAuthenticationConfiguration;
import org.infinispan.server.core.configuration.NoAuthenticationConfigurationBuilder;
import org.infinispan.server.core.configuration.ProtocolServerConfigurationBuilder;

/**
 * gRPC Server Configuration Builder
 *
 * @since 16.2
 */
public class GrpcServerConfigurationBuilder extends
      ProtocolServerConfigurationBuilder<GrpcServerConfiguration, GrpcServerConfigurationBuilder, NoAuthenticationConfiguration>
      implements Builder<GrpcServerConfiguration> {

   private final NoAuthenticationConfigurationBuilder authentication = new NoAuthenticationConfigurationBuilder();

   public GrpcServerConfigurationBuilder() {
      super(GrpcServerConfiguration.DEFAULT_GRPC_PORT, GrpcServerConfiguration.attributeDefinitionSet());
      // gRPC doesn't need a default cache - clients specify cache name in each request
   }

   @Override
   public AttributeSet attributes() {
      return attributes;
   }

   @Override
   public GrpcServerConfigurationBuilder self() {
      return this;
   }

   public GrpcServerConfigurationBuilder maxInboundMessageSize(int size) {
      attributes.attribute(GrpcServerConfiguration.MAX_INBOUND_MESSAGE_SIZE).set(size);
      return this;
   }

   public GrpcServerConfigurationBuilder maxInboundMetadataSize(int size) {
      attributes.attribute(GrpcServerConfiguration.MAX_INBOUND_METADATA_SIZE).set(size);
      return this;
   }

   @Override
   public GrpcServerConfigurationBuilder adminOperationsHandler(AdminOperationsHandler handler) {
      // gRPC doesn't use admin operations handler in initial implementation
      return this;
   }

   @Override
   public NoAuthenticationConfigurationBuilder authentication() {
      return authentication;
   }

   @Override
   public GrpcServerConfiguration create() {
      return new GrpcServerConfiguration(attributes.protect(), ipFilter.create(), ssl.create(), authentication.create());
   }

   public GrpcServerConfiguration build(boolean validate) {
      if (validate) {
         validate();
      }
      return create();
   }

   @Override
   public GrpcServerConfiguration build() {
      return build(true);
   }

   @Override
   public Builder<?> read(GrpcServerConfiguration template, Combine combine) {
      super.read(template, combine);
      return this;
   }
}
