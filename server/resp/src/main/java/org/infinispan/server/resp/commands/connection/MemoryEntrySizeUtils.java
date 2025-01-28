package org.infinispan.server.resp.commands.connection;

import org.infinispan.container.entries.CacheEntrySizeCalculator;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.PrimitiveEntrySizeCalculator;
import org.openjdk.jol.info.GraphLayout;

public class MemoryEntrySizeUtils {
   private static PrimitiveEntrySizeCalculator pesc = new PrimitiveEntrySizeCalculator();
   private static CacheEntrySizeCalculator<byte[], Object> cesc = new CacheEntrySizeCalculator<byte[], Object>(
         MemoryEntrySizeUtils::internalCalculateSize);

   public static long calculateSize(byte[] key, InternalCacheEntry<byte[], Object> ice) {
      return cesc.calculateSize(key, ice);
   }

   static long internalCalculateSize(byte[] key, Object value) {
      try {
         var keySize = GraphLayout.parseInstance(key).totalSize();
         var valueSize = GraphLayout.parseInstance(value).totalSize();
         return keySize + valueSize;
      } catch (Exception ex) {
         // Try an old style computation
         return pesc.calculateSize(key, value);
      }
   }
}
