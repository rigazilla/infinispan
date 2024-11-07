package org.infinispan.server.resp.commands.connection;

import java.util.Set;

import org.infinispan.multimap.impl.HashMapBucket;
import org.infinispan.multimap.impl.ListBucket;
import org.infinispan.multimap.impl.SetBucket;
import org.infinispan.multimap.impl.SortedSetBucket;
import org.infinispan.multimap.impl.internal.MultimapObjectWrapper;

public class MemoryUtils {
   private static final int DOUBLE_SIZE = 24;

   // Values obtained by
   // System.out.println(GraphLayout.parseInstance(<object>).toFootprint());
   /*
    * Returns the byte size in memory of a byte
    */
   public static int memSize(byte o) {
      return 16;
   }

   /*
    * Returns bytesize of a byte[]
    *
    * Size is the length of the array rounded up to the 8 bytes
    */
   public static int memSize(byte[] o) {
      var r = o.length % 8;
      return 16 + ( r == 0 ? o.length : o.length + (8 - r));
   }

   /*
    * Returns bytesize of a MultiObjectWrapper
    *
    * Size is 16 + memSize() of the embedded byte[]
    */
   public static int memSize(MultimapObjectWrapper<byte[]> o) {
      return MemoryUtils.memSize(o.get()) + 16;
   }

   /*
    * Return the memsize of a ListBucket
    *
    * Size is:
    * - 64 bytes fixed overhead
    * - [Ljava.lang.Object) growing like (length/2)*8
    * - payload size (size byte[])
    */
    public static int memSize(ListBucket<byte[]> o) {
      int payload = 0;
      for (byte[] it : o.toDeque()) {
         payload += memSize(it);
      }

      return (int) (64 + ((o.size() / 2) * 8) + payload);
   }

   /*
    * Returns the memsize of an HashMapBucket
    *
    * - 64 fixed overhead
    * - hashMapNodeOverHead(length)
    * - 32*length (hashMap$Node object)
    * - payload for all the MultimapObjectWrapper (keys)
    * - payload for all the byte[] (values)
    */
   public static int memSize(HashMapBucket<byte[], byte[]> o) {
      if (o.size() == 0) {
         return 64;
      }
      int payload = 0;
      for (byte[] it : o.keySet()) {
         payload += memSize(it) + memSize(o.get(it));
      }
      return 64 + MemoryUtils.hashMapNodeOverHead(o.size()) + 32 * o.size() + payload;
   }

   /*
    * Return the memsize of a SetBucket
    *
    * - 64 fixed overhead
    * - hashMapNodeOverHead(length)
    * - 32*length (hashMap$Node object)
    * - payload for all the MultimapObjectWrapper (values)
    */
   public static int memSize(SetBucket<byte[]> o) {
      if (o.size() == 0) {
         return 64;
      }
      int payload = 0;
      for (byte[] it : o.toList()) {
         payload += memSize(it);
      }
      // Similar to HashMapBucket there just an object more
      // as fixed overhead
      return 80 + MemoryUtils.hashMapNodeOverHead(o.size()) + 32 * o.size() + payload;
   }

   /*
    * Return the memsize of a SortedSetBucket
    *
    * Size is:
    * - 136 fixed overhead
    * - 16 fixed overhead with first object inserted
    * - hashMapNodeOverHead(length)
    * - 24*length (Double)
    * - 32*length (hashMap$Node object)
    * - 40*length (java.util.TreeMap$Entry)
    * - 24*length (ScoredValue)
    */
   public static int memSize(SortedSetBucket<byte[]> o) {
      int payload = 0;
      if (o.size() == 0) {
         return 136;
      }
      Set<MultimapObjectWrapper<byte[]>> valSet = o.getScoredEntriesAsValuesSet();
      for (MultimapObjectWrapper<byte[]> it : valSet) {
         payload += memSize(it) + DOUBLE_SIZE;
      }
      return (int) (152 + MemoryUtils.hashMapNodeOverHead((int) o.size()) + 96 * (int) o.size() + payload);
   }

   @SuppressWarnings("unchecked")
   public static int memSize(Object o) {
      if (o instanceof Byte) {
         return memSize((byte) o);
      }
      if (o instanceof byte[]) {
         return memSize((byte[]) o);
      }

      if (o instanceof MultimapObjectWrapper) {
         return memSize((MultimapObjectWrapper<byte[]>) o);
      }

      if (o instanceof HashMapBucket) {
         return memSize((HashMapBucket<byte[], byte[]>) o);
      }

      if (o instanceof SetBucket) {
         return memSize((SetBucket<byte[]>) o);
      }

      if (o instanceof SortedSetBucket) {
         return memSize((SortedSetBucket<byte[]>) o);
      }

      throw new UnsupportedOperationException();
   }

   /*
    * Returns the memory overhead of [Ljava.util.HashMap$Node object embedded.
    *
    * The formula is extrapolated from empirical values.
    */
   public static int hashMapNodeOverHead(int size) {
      if (size == 0) {
         return 0;
      }
      int pow2 = size / 12;
      int exponent = 31 - Integer.numberOfLeadingZeros(pow2);
      return 16 + 64 << exponent;
   }
}
