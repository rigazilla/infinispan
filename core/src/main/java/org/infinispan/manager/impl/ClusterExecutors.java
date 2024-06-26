package org.infinispan.manager.impl;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.infinispan.manager.ClusterExecutor;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.util.concurrent.BlockingManager;

/**
 * Static factory methods to construct a ClusterExecutor
 * @author wburns
 * @since 9.0
 */
public class ClusterExecutors {
   private ClusterExecutors() { }

   public static ClusterExecutor allSubmissionExecutor(Predicate<? super Address> predicate, EmbeddedCacheManager manager,
                                                       Transport transport, long time, TimeUnit unit, BlockingManager bm,
                                                       ScheduledExecutorService timeoutExecutor) {
      if (transport == null) {
         return new LocalClusterExecutor(predicate, manager, bm, time, unit, timeoutExecutor);
      }
      return new AllClusterExecutor(predicate, manager, transport, time, unit, bm, timeoutExecutor);
   }

   public static ClusterExecutor singleNodeSubmissionExecutor(Predicate<? super Address> predicate, EmbeddedCacheManager manager,
                                                              Transport transport, long time, TimeUnit unit, BlockingManager bm,
                                                              ScheduledExecutorService timeoutExecutor, int failOverCount) {
      if (failOverCount < 0) {
         throw new IllegalArgumentException("Failover count must be 0 or greater");
      }
      ClusterExecutor executor;
      if (transport == null) {
         executor = new LocalClusterExecutor(predicate, manager, bm, time, unit, timeoutExecutor);
      } else {
         executor = new SingleClusterExecutor(predicate, manager, transport, time, unit, bm, timeoutExecutor);
      }
      if (failOverCount == 0) {
         return executor;
      }
      return new FailOverClusterExecutor(executor, failOverCount);
   }
}
