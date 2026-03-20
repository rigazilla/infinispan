package org.infinispan.server.configuration.grpc;

import java.util.HashMap;
import java.util.Map;

/**
 * gRPC configuration XML attributes
 *
 * @since 16.2
 */
public enum Attribute {
   UNKNOWN(null), // must be first

   CACHE,
   MAX_INBOUND_MESSAGE_SIZE,
   MAX_INBOUND_METADATA_SIZE,
   NAME,
   SOCKET_BINDING;

   private static final Map<String, Attribute> ATTRIBUTES;

   static {
      final Map<String, Attribute> map = new HashMap<>(64);
      for (Attribute attribute : values()) {
         final String name = attribute.name;
         if (name != null) {
            map.put(name, attribute);
         }
      }
      ATTRIBUTES = map;
   }

   private final String name;

   Attribute(final String name) {
      this.name = name;
   }

   Attribute() {
      this.name = name().toLowerCase().replace('_', '-');
   }

   public static Attribute forName(String localName) {
      final Attribute attribute = ATTRIBUTES.get(localName);
      return attribute == null ? UNKNOWN : attribute;
   }

   @Override
   public String toString() {
      return name;
   }
}
