package org.infinispan.cdi.embedded.test.util;

import org.infinispan.cdi.common.util.Contracts;
import org.testng.annotations.Test;

/**
 * @author Kevin Pollet &lt;kevin.pollet@serli.com&gt; (C) 2011 SERLI
 */
@Test(groups = "unit", testName = "cdi.test.util.ContractsTest")
public class ContractsTest {

   public void testAssertNotNullOnNotNullParameter() {
      Contracts.assertNotNull("not null", "This parameter cannot be null");
   }
}
