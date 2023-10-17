package org.infinispan.server.resp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.infinispan.server.resp.test.RespTestingUtil.createClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

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
      RedisFuture<KeyValue<String, String>> rf = redis.blpop(timeout, keys);
      CacheNotifierImpl<?, ?> cni = (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      // If there's a listener ok otherwise
      // if rf is done an error during listener registration has happend
      // no need to wait anymore. test will fail
      eventually(() -> cni.getListeners().stream()
            .anyMatch(l -> l instanceof BLPOP.PubSubListener || l instanceof RespBlockingCommandsTest.FailingListener)
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
   void testBlpopAsync() throws InterruptedException, ExecutionException, TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redis1 = client.connect().async();
      redis.lpush("keyY", "firstY");
      redis.rpush("key1", "first", "second", "third");
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
   public void testBlpop()
         throws InterruptedException, ExecutionException, TimeoutException, java.util.concurrent.TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      try {
         var cf = registerBLPOPListener(redisBlock, 0, "keyZ");
         redis.lpush("keyZ", "firstZ");
         var res = cf.get(10, TimeUnit.SECONDS);
         assertThat(res.getKey()).isEqualTo("keyZ");
         assertThat(res.getValue()).isEqualTo("firstZ");

         redis.rpush("key1", "first", "second", "third");
         res = redis.blpop(0, "key1");
         assertThat(res.getKey()).isEqualTo("key1");
         assertThat(res.getValue()).isEqualTo("first");
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

   @Test
   public void testBlpopFailsListenerOnEvent() throws Exception {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisAsync = client.connect().async();

      CacheNotifierImpl<?, ?> cni = (CacheNotifierImpl<?, ?>) TestingUtil.extractComponent(cache, CacheNotifier.class);
      CacheNotifierImpl<?, ?> spyCni = spy(cni);
      CountDownLatch latch = new CountDownLatch(1);

      Answer<CompletionStage<Void>> listenerAnswer = invocation -> {
         Object[] args = invocation.getArguments();

         args[0] = new FailingListener((BLPOP.PubSubListener) args[0]);
         invocation.callRealMethod();
         latch.countDown();
         return CompletableFuture.completedFuture(null);
      };

      Answer<CompletionStage<Void>> listenerHolderAnswer = invocation -> {
         Object[] args = invocation.getArguments();
         var oldHolder = (ListenerHolder) args[0];
         var holder = new ListenerHolder(new FailingListener((BLPOP.PubSubListener) oldHolder.getListener()),
               oldHolder.getKeyDataConversion(), oldHolder.getValueDataConversion(), false);
         args[0] = holder;
         invocation.callRealMethod();
         latch.countDown();
         return CompletableFuture.completedFuture(null);
      };

      if (simpleCache)
         doAnswer(listenerAnswer).when(spyCni).addListenerAsync(any(), any(), any());
      else
         doAnswer(listenerHolderAnswer).when(spyCni).addListenerAsync(any(), any(), any(), any());

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
         blpop.setListenerAdded(true);
         blpop.cache.removeListenerAsync(this);
         blpop.getFuture().completeExceptionally(
               new RuntimeException("Injected failure in OnEvent"));
         return CompletableFutures.completedNull();
      }
   }

}
