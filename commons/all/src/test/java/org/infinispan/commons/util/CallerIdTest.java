package org.infinispan.commons.util;

import static org.junit.Assert.assertEquals;

import org.infinispan.commons.jdkspecific.CallerId;
import org.infinispan.commons.test.categories.Java11;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class CallerIdTest {

   @Test
   @Category(Java11.class)
   public void testCaller() {
      assertEquals(this.getClass(), CallerId.getCallerClass(1));
   }

   private static int i=0;
   @Test
   public void testFlaky() {
      if (i==0) {
         i=1;
         Assert.fail();
      }
   }
}
