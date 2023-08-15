package org.infinispan.distribution.ch.impl;

import org.infinispan.commons.hash.CRC16;
import org.infinispan.commons.hash.Hash;

/**
 * Implementation of {@link HashFunctionPartitioner} using {@link CRC16}.
 *
 * @since 15.0
 * @see HashFunctionPartitioner
 */
public class CRC16HashFunctionPartitioner extends HashFunctionPartitioner {

   private boolean isPow2;

   public static CRC16HashFunctionPartitioner instance(int numSegments) {
      CRC16HashFunctionPartitioner partitioner = new CRC16HashFunctionPartitioner();
      partitioner.init(numSegments);
      return partitioner;
   }

   @Override
   protected void init(int numSegments) {
      super.init(numSegments);
      isPow2 = isPow2(numSegments);
   }

   private static boolean isPow2(int n) {
      boolean isPow2 = false;
      int v = n;
      while (v > 1) {
         if ((v & 1) == 1) {
            return false;
         }
         isPow2 = true;
         v >>= 1;
      }
      return isPow2;
   }

   @Override
   public int getSegment(Object key) {
      int h = hashFunction.hash(key) & Integer.MAX_VALUE;

      // See: https://redis.io/docs/reference/cluster-spec/#key-distribution-model
      return isPow2 ? (h & (numSegments - 1)) : h % numSegments;
   }

   @Override
   protected Hash getHash() {
      return CRC16.getInstance();
   }
}
