package org.infinispan.server.resp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.infinispan.server.resp.test.RespTestingUtil.createClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.notifications.cachelistener.CacheNotifierImpl;
import org.infinispan.notifications.cachelistener.ListenerHolder;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.server.resp.commands.list.blocking.BLPOP;
import org.infinispan.server.resp.test.RespTestingUtil;
import org.infinispan.test.TestingUtil;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Factory;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Class for blocking commands tests.
 *
 * @author Vittorio Rigamonti
 * @since 15.0
 */
@Test(groups = "functional", testName = "server.resp.RespBlockingCommandsTest")
public class RespBlockingCommandsTest extends SingleNodeRespBaseTest {

   private CacheMode cacheMode = CacheMode.LOCAL;
   private boolean simpleCache;

   @Factory
   public Object[] factory() {
      return new Object[] {
            new RespBlockingCommandsTest(),
            new RespBlockingCommandsTest().simpleCache()
      };
   }

   RespBlockingCommandsTest simpleCache() {
      this.cacheMode = CacheMode.LOCAL;
      this.simpleCache = true;
      return this;
   }

   @Override
   protected String parameters() {
      return "[simpleCache=" + simpleCache + ", cacheMode=" + cacheMode + "]";
   }

   @Override
   protected void amendConfiguration(ConfigurationBuilder configurationBuilder) {
      if (simpleCache) {
         configurationBuilder.clustering().cacheMode(CacheMode.LOCAL).simpleCache(true);
      } else {
         configurationBuilder.clustering().cacheMode(cacheMode);
      }
   }

   private RedisFuture<KeyValue<String, String>> registerBLPOPListener(RedisAsyncCommands<String, String> redis,
         long timeout, String... keys) {
      return registerBLPOPListener(redis, timeout, 1, keys);
   }

   private RedisFuture<KeyValue<String, String>> registerBLPOPListener(RedisAsyncCommands<String, String> redis,
         long timeout, int count, String... keys) {
      var cni =  (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      long pre = cni.getListeners().stream()
            .filter(l -> l instanceof BLPOP.PubSubListener || l instanceof RespBlockingCommandsTest.FailingListener)
            .count();
      RedisFuture<KeyValue<String, String>> rf = redis.blpop(timeout, keys);
      // If there's a listener ok otherwise
      // if rf is done an error during listener registration has happend
      // no need to wait anymore. test will fail
      eventually(() -> (cni.getListeners().stream()
            .filter(l -> l instanceof BLPOP.PubSubListener || l instanceof RespBlockingCommandsTest.FailingListener)
            .count() == pre + 1)
            || rf.isDone());
      return rf;
   }

   private void verifyBLPOPListenerUnregistered() {
      CacheNotifierImpl<?, ?> cni = (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      // Check listener is unregistered
      eventually(() -> cni.getListeners().stream().noneMatch(
            l -> l instanceof BLPOP.PubSubListener || l instanceof RespBlockingCommandsTest.FailingListener));
   }

   @Test
   @Ignore
   void testBlpopAsync() throws InterruptedException, ExecutionException, TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redis1 = client.connect().async();
      redis.lpush("keyY", "firstY");
      try {
         var cf = registerBLPOPListener(redis1, 0, "keyZ");
         // Ensure lpush is after blpop
         redis.lpush("keyZ", "firstZ");
         var response = cf.get(10, TimeUnit.SECONDS);
         assertThat(response.getKey()).isEqualTo("keyZ");
         assertThat(response.getValue()).isEqualTo("firstZ");
         assertThat(redis.lpop("keyZ")).isNull();
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpop()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 0, "keyZ");
         redis.lpush("keyZ", "firstZ", "secondZ");
         var res = cf.get(10, TimeUnit.SECONDS);
         assertThat(res.getKey()).isEqualTo("keyZ");
         assertThat(res.getValue()).isEqualTo("secondZ");
         // Check blpop (feeded by listener) removed just one element
         assertThat(redis.lrange("keyZ", 0, -1))
               .containsExactly("firstZ");

         redis.rpush("key1", "first", "second", "third");
         res = redis.blpop(0, "key1");
         assertThat(res.getKey()).isEqualTo("key1");
         assertThat(res.getValue()).isEqualTo("first");
         // Check blpop (feeded by poll) removed just one element
         assertThat(redis.lrange("key1", 0, -1))
               .containsExactlyInAnyOrder("second", "third");

         res = redis.blpop(0, "key2", "key1");
         assertThat(res.getKey()).isEqualTo("key1");
         assertThat(res.getValue()).isEqualTo("second");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testLPOP() {
      RedisCommands<String, String> redis = redisConnection.sync();
      assertThat(redis.lpop("not_exist")).isNull();

      // test single value
      assertThat(redis.rpush("leads", "tristan")).isEqualTo(1);
      assertThat(redis.lpop("leads")).isEqualTo("tristan");
      assertThat(redis.lpop("leads")).isNull();
      assertThat(redis.sadd("leads","f")).isEqualTo(1);
   }

   @Test
   @Ignore
   public void testBlpopTimeout() throws InterruptedException, ExecutionException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisAsync = client.connect().async();

      redis.rpush("key1", "first", "second", "third");

      assertThatThrownBy(() -> redisAsync.blpop(-1, "keyZ").get(10, TimeUnit.SECONDS))
            .cause()
            .isInstanceOf(RedisCommandExecutionException.class)
            .hasMessageContaining("ERR value is out of range, must be positive");
      var res = redisAsync.blpop(1, "keyZ");
      // Ensure blpop is expired
      eventually(res::isDone);
      redis.lpush("keyZ", "firstZ");
      assertThat(res.get()).isNull();

      try {
         var cf = registerBLPOPListener(redisAsync, 0, "keyY");
         redis.lpush("keyY", "valueY");
         assertThat(cf.get().getKey()).isEqualTo("keyY");
         assertThat(cf.get().getValue()).isEqualTo("valueY");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpopTwoListenersWithValues()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock2 = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 0, "key");
         var cf2 = registerBLPOPListener(redisBlock2, 0, 2, "key");
         redis.lpush("key", "first", "second", "third");
         var res = cf.get(10, TimeUnit.SECONDS);
         var res2 = cf2.get(10, TimeUnit.SECONDS);
         assertThat(res.getKey()).isEqualTo("key");
         assertThat(res2.getKey()).isEqualTo("key");
         assertThat(redis.lrange("key", 0, -1))
               .containsExactly("first");
         assertThat(Arrays.asList(res.getValue(), res2.getValue())).containsExactlyInAnyOrder("second", "third");
         // Check blpop (feeded by events) removed two events
         assertThat(redis.lrange("key", 0, -1))
               .containsExactly("first");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpopTwoListenersOneTimeout()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock2 = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 3, "key");
         var cf2 = registerBLPOPListener(redisBlock2, 3, 2, "key");
         redis.lpush("key", "first");
         var res = cf.get(10, TimeUnit.SECONDS);
         var res2 = cf2.get(10, TimeUnit.SECONDS);

         assertThat(res.getKey()).isEqualTo("key");
         assertThat(res.getValue()).isEqualTo("first");
         assertThat(res2).isNull();
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpopTwoListenersTwoProducers()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis1 = redisConnection.sync();
      RedisCommands<String, String> redis2 = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock2 = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 0, "key");
         var cf2 = registerBLPOPListener(redisBlock2, 0, 2, "key");
         Thread.sleep(5000);
         var t1 = new Thread(() -> {
            redis1.lpush("key", "first");
         });
         var t2 = new Thread(() -> {
            redis2.lpush("key", "second", "third");
         });
         t1.start();
         t2.start();
         var res = cf.get(10, TimeUnit.SECONDS);
         var res2 = cf2.get(10, TimeUnit.SECONDS);
         assertThat(res.getKey()).isEqualTo("key");
         assertThat(res2.getKey()).isEqualTo("key");
         var rest = redis1.lrange("key", 0, -1);
         assertThat(rest.size()).isEqualTo(1);
         assertThat(Arrays.asList(res.getValue(), res2.getValue(), rest.get(0)))
               .containsExactlyInAnyOrder("first", "second", "third");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpopThreeListenersOneTimesOutTwoProducers()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis1 = redisConnection.sync();
      RedisCommands<String, String> redis2 = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock2 = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock3 = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 3, "key");
         var cf2 = registerBLPOPListener(redisBlock2, 3, 2, "key");
         var cf3 = registerBLPOPListener(redisBlock3, 3, 3, "key");
         var t1 = new Thread(() -> {
            redis1.lpush("key", "first");
         });
         var t2 = new Thread(() -> {
            redis2.lpush("key", "second");
         });
         t1.start();
         t2.start();
         var res = cf.get(10, TimeUnit.SECONDS);
         var res2 = cf2.get(10, TimeUnit.SECONDS);
         var res3 = cf3.get(10, TimeUnit.SECONDS);
         assertThat(Arrays.asList(extractValue(res), extractValue(res2), extractValue(res3)))
               .containsExactlyInAnyOrder("first", "second", null);
         assertThat(redis1.lrange("key", 0, -1)).isEmpty();
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   private String extractValue(KeyValue<String, String> kv) {
      return kv == null ? null : kv.getValue();
   }

   @Test
   public void testBlpopThreeListenersTwoProducers()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis1 = redisConnection.sync();
      RedisCommands<String, String> redis2 = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock2 = client.connect().async();
      RedisAsyncCommands<String, String> redisBlock3 = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 5, "key");
         var cf2 = registerBLPOPListener(redisBlock2, 5, 2, "key");
         var cf3 = registerBLPOPListener(redisBlock3, 5, 3, "key");
         var t1 = new Thread(() -> {
            redis1.lpush("key", "first");
         });
         var t2 = new Thread(() -> {
            try {
               Thread.sleep(2000);
            } catch (InterruptedException e) {
               // TODO Auto-generated catch block
               e.printStackTrace();
            }
            redis2.lpush("key", "second", "third", "fourth");
         });
         t1.start();
         t2.start();
         var res = cf.get(10, TimeUnit.SECONDS);
         var res2 = cf2.get(10, TimeUnit.SECONDS);
         var res3 = cf3.get(10, TimeUnit.SECONDS);
         List<String> results = Arrays.asList(res.getValue(), res2.getValue(), res3.getValue());
         results.sort(null);
         List<String> expected1 = Arrays.asList("first", "fourth", "third");
         List<String> expected2 = Arrays.asList("fourth", "second", "third");
         assertThat(results.size()).isEqualTo(3);
         // assertThat(results.containsAll(expected1) ||
         // results.containsAll(expected2)).isTrue();
         var rest = redis1.lrange("key", 0, -1);
         assertThat(rest.size()).isEqualTo(1);
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   @Ignore
   public void testBlpopFailsInstallingListener() throws Exception {
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisAsync = client.connect().async();

      CacheNotifierImpl<?, ?> cni = (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      CacheNotifierImpl<?, ?> spyCni = spy(cni);
      CountDownLatch latch = new CountDownLatch(1);

      Answer<CompletionStage<Void>> listenerAnswer = ignore -> {
         latch.countDown();
         return CompletableFuture.failedFuture(new RuntimeException("Injected failure"));
      };

      if (simpleCache)
         doAnswer(listenerAnswer).when(spyCni).addListenerAsync(any(), any(), any());
      else
         doAnswer(listenerAnswer).when(spyCni).addListenerAsync(any(), any(), any(), any());

      try {
         TestingUtil.replaceComponent(cache, CacheNotifier.class, spyCni, true);
         assertThatThrownBy(() -> redisAsync.blpop(0, "my-nice-key").get(10, TimeUnit.SECONDS))
               .isInstanceOf(ExecutionException.class)
               .hasMessageContaining("Injected failure");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
         TestingUtil.replaceComponent(cache, CacheNotifier.class, cni, true);
      }
   }

   FailingListener failingListener;

   @Test
   @Ignore
   public void testBlpopFailsListenerOnEvent() throws Exception {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisAsync = client.connect().async();

      CacheNotifierImpl<?, ?> cni = (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      CacheNotifierImpl<?, ?> spyCni = spy(cni);
      CountDownLatch latch = new CountDownLatch(1);

      Answer<CompletionStage<Void>> listenerAnswer = invocation -> {
         Object[] args = invocation.getArguments();
         failingListener = new FailingListener((BLPOP.PubSubListener) args[0]);
         args[0] = failingListener;
         invocation.callRealMethod();
         latch.countDown();
         return CompletableFuture.completedFuture(null);
      };

      Answer<CompletionStage<Void>> listenerHolderAnswer = invocation -> {
         Object[] args = invocation.getArguments();
         var oldHolder = (ListenerHolder) args[0];
         failingListener = new FailingListener((BLPOP.PubSubListener) oldHolder.getListener());
         var holder = new ListenerHolder(failingListener,
               oldHolder.getKeyDataConversion(), oldHolder.getValueDataConversion(), false);
         args[0] = holder;
         invocation.callRealMethod();
         latch.countDown();
         return CompletableFuture.completedFuture(null);
      };

      Answer<CompletionStage<Void>> listenerHolderRemove = invocation -> {
         Object[] args = invocation.getArguments();
         args[0] = failingListener;
         invocation.callRealMethod();
         return CompletableFuture.completedFuture(null);
      };

      if (simpleCache)
         doAnswer(listenerAnswer).when(spyCni).addListenerAsync(any(), any(), any());
      else
         doAnswer(listenerHolderAnswer).when(spyCni).addListenerAsync(any(), any(), any(), any());
      doAnswer(listenerHolderRemove).when(spyCni).removeListenerAsync(any());

      try {
         TestingUtil.replaceComponent(cache, CacheNotifier.class, spyCni, true);
         var cf = registerBLPOPListener(redisAsync, 0, "keyY");
         redis.lpush("keyY", "valueY");
         assertThatThrownBy(() -> cf.get())
               .isInstanceOf(ExecutionException.class)
               .hasMessageContaining("Injected failure in OnEvent");
      } finally {
         verifyBLPOPListenerUnregistered();
         RespTestingUtil.killClient(client);
         TestingUtil.replaceComponent(cache, CacheNotifier.class, cni, true);
      }
   }

   @Listener(clustered = true)
   public static class FailingListener {
      BLPOP.PubSubListener blpop;

      public FailingListener(BLPOP.PubSubListener arg) {
         blpop = arg;
      }

      @CacheEntryCreated
      public CompletionStage<Void> onEvent(CacheEntryEvent<Object, Object> entryEvent) {
         blpop.getFuture().completeExceptionally(
               new RuntimeException("Injected failure in OnEvent"));
         return CompletableFutures.completedNull();
      }
   }
}
