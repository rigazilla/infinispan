package org.infinispan.server.configuration.grpc;

import static org.infinispan.commons.util.StringPropertyReplacer.replaceProperties;

import org.infinispan.commons.configuration.io.ConfigurationReader;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ConfigurationParser;
import org.infinispan.configuration.parsing.Namespace;
import org.infinispan.configuration.parsing.Namespaces;
import org.infinispan.configuration.parsing.ParseUtils;
import org.infinispan.server.configuration.ServerConfigurationBuilder;
import org.infinispan.server.configuration.ServerConfigurationParser;
import org.infinispan.server.configuration.endpoint.EndpointConfigurationBuilder;
import org.infinispan.server.grpc.configuration.GrpcServerConfigurationBuilder;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.kohsuke.MetaInfServices;

/**
 * Server endpoint configuration parser for gRPC protocol
 *
 * @since 16.2
 */
@MetaInfServices
@Namespaces({
      @Namespace(root = "grpc-connector"),
      @Namespace(uri = "urn:infinispan:server:*", root = "grpc-connector"),
})
public class GrpcServerConfigurationParser implements ConfigurationParser {
   private static final Log coreLog = LogFactory.getLog(ServerConfigurationParser.class);

   @Override
   public void readElement(ConfigurationReader reader, ConfigurationBuilderHolder holder) {
      if (!holder.inScope(ServerConfigurationParser.ENDPOINTS_SCOPE)) {
         throw coreLog.invalidScope(ServerConfigurationParser.ENDPOINTS_SCOPE, holder.getScope());
      }
      GlobalConfigurationBuilder builder = holder.getGlobalConfigurationBuilder();

      Element element = Element.forName(reader.getLocalName());
      switch (element) {
         case GRPC_CONNECTOR: {
            ServerConfigurationBuilder serverBuilder = builder.module(ServerConfigurationBuilder.class);
            if (serverBuilder != null) {
               parseGrpc(reader, serverBuilder);
            } else {
               throw ParseUtils.unexpectedElement(reader);
            }
            break;
         }
         default: {
            throw ParseUtils.unexpectedElement(reader);
         }
      }
   }

   @Override
   public Namespace[] getNamespaces() {
      return ParseUtils.getNamespaceAnnotations(getClass());
   }

   private void parseGrpc(ConfigurationReader reader, ServerConfigurationBuilder serverBuilder) {
      boolean dedicatedSocketBinding = false;
      EndpointConfigurationBuilder endpoint = serverBuilder.endpoints().current();
      GrpcServerConfigurationBuilder builder = endpoint.addConnector(GrpcServerConfigurationBuilder.class);

      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case CACHE: {
               builder.defaultCacheName(value);
               break;
            }
            case NAME: {
               builder.name(value);
               break;
            }
            case SOCKET_BINDING: {
               builder.socketBinding(value);
               builder.startTransport(true);
               dedicatedSocketBinding = true;
               break;
            }
            case MAX_INBOUND_MESSAGE_SIZE: {
               builder.maxInboundMessageSize(Integer.parseInt(value));
               break;
            }
            case MAX_INBOUND_METADATA_SIZE: {
               builder.maxInboundMetadataSize(Integer.parseInt(value));
               break;
            }
            default: {
               ServerConfigurationParser.parseCommonConnectorAttributes(reader, i, serverBuilder, builder);
            }
         }
      }

      if (!dedicatedSocketBinding) {
         builder.socketBinding(endpoint.singlePort().socketBinding()).startTransport(false);
      }

      while (reader.inTag()) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
            default: {
               ServerConfigurationParser.parseCommonConnectorElements(reader, builder);
            }
         }
      }
   }
}
