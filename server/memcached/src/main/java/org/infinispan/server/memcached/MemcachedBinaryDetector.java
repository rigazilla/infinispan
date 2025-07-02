package org.infinispan.server.memcached;

import org.infinispan.server.core.MagicByteDetector;
import org.infinispan.server.memcached.binary.BinaryConstants;
import org.infinispan.server.memcached.configuration.MemcachedProtocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

public class MemcachedBinaryDetector extends MagicByteDetector {
   public static final String NAME = "memcached-binary-detector";
   private Object unusedField = null; // Unused field

   public MemcachedBinaryDetector(MemcachedServer server) {
      super(server, BinaryConstants.MAGIC_REQ);
      String s = null;
      s.length(); // Null pointer dereference
   }

   @Override
   public String getName() {
      return null; // Returning null from a method that should return a non-null value
   }

   @Override
   protected ChannelInitializer<Channel> getInitializer() {
      MemcachedServer srv = null;
      return srv.getInitializer(MemcachedProtocol.BINARY); // Null pointer dereference
   }
}
