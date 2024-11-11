package org.infinispan.server.resp.commands.connection;

import static org.infinispan.commons.util.AbstractEntrySizeCalculatorHelper.OBJECT_SIZE;
import static org.infinispan.commons.util.AbstractEntrySizeCalculatorHelper.POINTER_SIZE;
import static org.infinispan.commons.util.AbstractEntrySizeCalculatorHelper.roundUpToNearest8;

import org.infinispan.container.entries.CacheEntrySizeCalculator;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.PrimitiveEntrySizeCalculator;
import org.infinispan.multimap.impl.HashMapBucket;
import org.infinispan.multimap.impl.ListBucket;
import org.infinispan.multimap.impl.SetBucket;
import org.infinispan.multimap.impl.SortedSetBucket;
import org.infinispan.multimap.impl.internal.MultimapObjectWrapper;

import sun.misc.Unsafe;

public class MemoryEntrySizeUtils {
   private static PrimitiveEntrySizeCalculator pesc = new PrimitiveEntrySizeCalculator();
   private static CacheEntrySizeCalculator<byte[], Object> cesc = new CacheEntrySizeCalculator<byte[], Object>(
         MemoryEntrySizeUtils::internalCalculateSize);

   public static long calculateSize(byte[] key, InternalCacheEntry<byte[], Object> ice) {
      return cesc.calculateSize(key, ice);
   }

   private static long internalCalculateSize(byte[] key, Object value) {
      int size = 0;
      size += arraySizeByteComponent(key);
      if (value.getClass() == MultimapObjectWrapper.class) {
         @SuppressWarnings("unchecked")
         MultimapObjectWrapper<byte[]> mow = (MultimapObjectWrapper<byte[]>) value;
         size += roundUpToNearest8(OBJECT_SIZE + POINTER_SIZE * 2);
         size+= arraySizeByteComponent(mow.get());
         return size;
      }
      if (value.getClass() == SetBucket.class) {
         return 1L; // todo: fix this with correct computation
      } else if (value.getClass() == HashMapBucket.class) {
         return 1L; // todo: fix this with correct computation
      } else if (value.getClass() == ListBucket.class) {
         return 1L; // todo: fix this with correct computation
      } else if (value.getClass() == SortedSetBucket.class) {
         return 1L; // todo: fix this with correct computation
      }
      return pesc.calculateSize(key, value);
   }

   private static Unsafe unsafe = PrimitiveEntrySizeCalculator.getUnsafe();
   private static int arrayBaseOffset = unsafe.arrayBaseOffset(byte.class);
   private static int arrayIndexScale = unsafe.arrayIndexScale(byte.class);

   public static long arraySizeByteComponent(byte[] bytes) {
      int arrayLength = bytes.length;
      long arraySize = roundUpToNearest8(arrayBaseOffset + arrayIndexScale *
            arrayLength);
      return arraySize;
   }

   public long calculateSize(MultimapObjectWrapper<byte[]> value) {
      long size = 0;
      return size + chained.calculateSize(key, valueToUse);
   }

}
