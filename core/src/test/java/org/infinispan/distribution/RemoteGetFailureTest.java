package org.infinispan.distribution;

import static org.infinispan.commons.test.Exceptions.expectException;
import static org.infinispan.test.TestingUtil.extractGlobalComponent;
import static org.infinispan.test.TestingUtil.extractInterceptorChain;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.infinispan.Cache;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.TimeoutException;
import org.infinispan.commons.test.Exceptions;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.container.entries.ImmortalCacheValue;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.remoting.RemoteException;
import org.infinispan.remoting.inboundhandler.DeliverOrder;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.impl.MapResponseCollector;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.statetransfer.StateTransferInterceptor;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestDataSCI;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.TransportFlags;
import org.infinispan.util.ByteString;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.pbcast.GMS;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

@Test(groups = "functional", testName = "distribution.RemoteGetFailureTest")
@CleanupAfterMethod
public class RemoteGetFailureTest extends MultipleCacheManagersTest {
   private Object key;

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC);
      builder.clustering().stateTransfer().timeout(10, TimeUnit.SECONDS);
      builder.clustering().remoteTimeout(5, TimeUnit.SECONDS);
      createClusteredCaches(3, TestDataSCI.INSTANCE, builder, new TransportFlags().withFD(true));
      waitForClusterToForm();
      key = getKeyForCache(cache(1), cache(2));
   }

   @AfterMethod(alwaysRun = true)
   @Override
   protected void clearContent() throws Throwable {
      // Merge the cluster back so that the leave requests don't have to time out
      for (Cache<Object, Object> cache : caches()) {
         installNewView(cache, caches().toArray(new Cache[0]));
      }
      super.clearContent();
   }

   public void testDelayed(Method m) {
      initAndCheck(m);

      CountDownLatch release = new CountDownLatch(1);
      extractInterceptorChain(cache(1)).addInterceptor(new DelayingInterceptor(null, release), 0);

      long requestStart = System.nanoTime();
      assertEquals(m.getName(), cache(0).get(key));
      long requestEnd = System.nanoTime();
      long remoteTimeout = cache(0).getCacheConfiguration().clustering().remoteTimeout();
      long delay = TimeUnit.NANOSECONDS.toMillis(requestEnd - requestStart);
      assertTrue(delay < remoteTimeout);

      release.countDown();
   }

   public void testExceptionFromBothOwners(Method m) {
      initAndCheck(m);

      extractInterceptorChain(cache(1)).addInterceptor(new FailingInterceptor(), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new FailingInterceptor(), 0);

      expectException(RemoteException.class, CacheException.class, "Injected", () -> cache(0).get(key));
   }

   public void testExceptionFromOneOwnerOtherTimeout(Method m) {
      initAndCheck(m);

      CountDownLatch release = new CountDownLatch(1);
      extractInterceptorChain(cache(1)).addInterceptor(new FailingInterceptor(), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new DelayingInterceptor(null, release), 0);

      // It's not enough to test if the exception is TimeoutException as we want the remote get fail immediately
      // upon exception.

      // We cannot mock TimeService in ScheduledExecutor, so we have to measure if the response was fast
      // remoteTimeout is gracious enough (15s) to not cause false positives
      long requestStart = System.nanoTime();
      try {
         expectException(RemoteException.class, CacheException.class, "Injected", () -> cache(0).get(key));
         long exceptionThrown = System.nanoTime();
         long remoteTimeout = cache(0).getCacheConfiguration().clustering().remoteTimeout();
         long delay = TimeUnit.NANOSECONDS.toMillis(exceptionThrown - requestStart);
         assertTrue(delay < remoteTimeout);
      } finally {
         release.countDown();
      }
   }

   public void testBothOwnersSuspected(Method m) throws ExecutionException, InterruptedException {
      initAndCheck(m);

      CountDownLatch arrival = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger thrown = new AtomicInteger();
      AtomicInteger retried = new AtomicInteger();
      extractInterceptorChain(cache(0)).addInterceptorAfter(new CheckOTEInterceptor(thrown, retried), StateTransferInterceptor.class);
      extractInterceptorChain(cache(1)).addInterceptor(new DelayingInterceptor(arrival, release), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new DelayingInterceptor(arrival, release), 0);

      Future<Object> future = fork(() -> cache(0).get(key));
      assertTrue(arrival.await(10, TimeUnit.SECONDS));

      installNewView(cache(0), cache(0));

      // The entry was lost, so we'll get null
      assertNull(future.get());
      // Since we've lost all owners, we get an OutdatedTopologyException and we retry
      assertEquals(1, thrown.get());
      assertEquals(1, retried.get());
      release.countDown();
   }

   public void testOneOwnerSuspected(Method m) throws ExecutionException, InterruptedException {
      initAndCheck(m);

      CountDownLatch arrival = new CountDownLatch(2);
      CountDownLatch release1 = new CountDownLatch(1);
      CountDownLatch release2 = new CountDownLatch(1);
      extractInterceptorChain(cache(1)).addInterceptor(new DelayingInterceptor(arrival, release1), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new DelayingInterceptor(arrival, release2), 0);

      Future<?> future = fork(() -> {
         assertEquals(cache(0).get(key), m.getName());
      });
      assertTrue(arrival.await(10, TimeUnit.SECONDS));

      installNewView(cache(0), cache(0), cache(1));

      // suspection should not fail the operation
      assertFalse(future.isDone());
      release1.countDown();
      future.get();
      release2.countDown();
   }

   public void testOneOwnerSuspectedNoFilter(Method m) throws ExecutionException, InterruptedException {
      initAndCheck(m);

      CountDownLatch arrival = new CountDownLatch(2);
      CountDownLatch release1 = new CountDownLatch(1);
      CountDownLatch release2 = new CountDownLatch(1);
      extractInterceptorChain(cache(1)).addInterceptor(new DelayingInterceptor(arrival, release1), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new DelayingInterceptor(arrival, release2), 0);

      Address address1 = address(1);
      Address address2 = address(2);
      List<Address> owners = Arrays.asList(address1, address2);

      ClusteredGetCommand clusteredGet = new ClusteredGetCommand(key, ByteString.fromString(cache(0).getName()),
            TestingUtil.getSegmentForKey(key, cache(1)), 0);
      final int timeout = 15;
      RpcOptions rpcOptions = new RpcOptions(DeliverOrder.NONE, timeout, TimeUnit.SECONDS);

      RpcManager rpcManager = cache(0).getAdvancedCache().getRpcManager();
      clusteredGet.setTopologyId(rpcManager.getTopologyId());
      CompletableFuture<Map<Address, Response>> future = rpcManager.invokeCommand(owners, clusteredGet, MapResponseCollector.ignoreLeavers(), rpcOptions).toCompletableFuture();

      assertTrue(arrival.await(10, TimeUnit.SECONDS));

      installNewView(cache(0), cache(0), cache(1));

      // RequestCorrelator processes the view asynchronously, so we need to wait a bit for node 2 to be suspected
      Thread.sleep(100);

      // suspection should not fail the operation
      assertFalse(future.isDone());
      long requestAllowed = System.nanoTime();
      release1.countDown();
      Map<Address, Response> responses = future.get();
      long requestCompleted = System.nanoTime();
      long requestSeconds = TimeUnit.NANOSECONDS.toSeconds(requestCompleted - requestAllowed);

      assertTrue("Request took too long: " + requestSeconds, requestSeconds < timeout / 2);
      assertEquals(SuccessfulResponse.create(new ImmortalCacheValue(m.getName())), responses.get(address1));
      assertEquals(CacheNotFoundResponse.INSTANCE, responses.get(address2));
      release2.countDown();
   }

   public void testOneOwnerSuspectedOtherTimeout(Method m) throws ExecutionException, InterruptedException {
      initAndCheck(m);

      CountDownLatch arrival = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      extractInterceptorChain(cache(1)).addInterceptor(new DelayingInterceptor(arrival, release), 0);
      extractInterceptorChain(cache(2)).addInterceptor(new DelayingInterceptor(arrival, release), 0);

      Future<?> future = fork(() -> {
         long start = System.nanoTime();
         Exceptions.expectException(TimeoutException.class, () -> cache(0).get(key));
         long end = System.nanoTime();
         long duration = TimeUnit.NANOSECONDS.toMillis(end - start);
         assertTrue("Request did not wait for long enough: " + duration,
               duration >= cache(0).getCacheConfiguration().clustering().remoteTimeout());
      });
      assertTrue(arrival.await(10, TimeUnit.SECONDS));

      installNewView(cache(0), cache(0), cache(1));

      // suspection should not fail the operation
      assertFalse(future.isDone());
      future.get();
      release.countDown();
   }

   private void initAndCheck(Method m) {
      cache(0).put(key, m.getName());
      assertEquals(m.getName(), cache(1).get(key));
      assertEquals(m.getName(), cache(2).get(key));
   }

   private void installNewView(Cache installing, Cache... cachesInView) {
      JGroupsTransport transport = (JGroupsTransport) extractGlobalComponent(installing.getCacheManager(), Transport.class);
      JChannel channel = transport.getChannel();

      org.jgroups.Address[] members = Stream.of(cachesInView)
            .map(this::address)
            .map(Address::toExtendedUUID)
            .toArray(org.jgroups.Address[]::new);
      View view = View.create(members[0], transport.getViewId() + 1, members);
      ((GMS) channel.getProtocolStack().findProtocol(GMS.class)).installView(view);
   }

   static class FailingInterceptor extends DDAsyncInterceptor {
      @Override
      public Object visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command) throws Throwable {
         throw new CacheException("Injected");
      }
   }

   static class DelayingInterceptor extends DDAsyncInterceptor {
      private final CountDownLatch arrival;
      private final CountDownLatch release;

      private DelayingInterceptor(CountDownLatch arrival, CountDownLatch release) {
         this.arrival = arrival;
         this.release = release;
      }

      @Override
      public Object visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command) throws Throwable {
         if (arrival != null) arrival.countDown();
         // the timeout has to be longer than remoteTimeout!
         release.await(30, TimeUnit.SECONDS);
         return super.visitGetCacheEntryCommand(ctx, command);
      }
   }

   static class CheckOTEInterceptor extends DDAsyncInterceptor {
      private final AtomicInteger thrown;
      private final AtomicInteger retried;

      public CheckOTEInterceptor(AtomicInteger thrown, AtomicInteger retried) {
         this.thrown = thrown;
         this.retried = retried;
      }

      @Override
      public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
         if (command.hasAnyFlag(FlagBitSets.COMMAND_RETRY)) {
            retried.incrementAndGet();
         }
         return invokeNextAndExceptionally(ctx, command, (rCtx, rCommand, t) -> {
            thrown.incrementAndGet();
            throw t;
         });
      }
   }
}
