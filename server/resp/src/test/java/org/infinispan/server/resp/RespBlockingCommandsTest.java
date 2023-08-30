package org.infinispan.server.resp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.infinispan.server.resp.test.RespTestingUtil.createClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.server.resp.test.RespTestingUtil;
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

   @Test
   void testBlpopAsync() throws InterruptedException, ExecutionException, TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redis1 = client.connect().async();
      redis.lpush("keyY", "firstY");
      redis.rpush("key1", "first", "second", "third");
      CountDownLatch latch = new CountDownLatch(1);
      var cf = CompletableFuture.supplyAsync(() -> {
         var rf = redis1.blpop(0, "keyZ");
         latch.countDown();
         return rf.toCompletableFuture();
      }, testExecutor()).thenCompose(v -> v);
      // Ensure lpush is after blpop
      try {
         assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
         redis.lpush("keyZ", "firstZ");
         var response = cf.get(4, TimeUnit.SECONDS);
         assertThat(response.getKey()).isEqualTo("keyZ");
         assertThat(response.getValue()).isEqualTo("firstZ");
      } finally {
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   public void testBlpop() throws InterruptedException, ExecutionException, TimeoutException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisBlock = client.connect().async();
      CountDownLatch latch = new CountDownLatch(1);
      var cf = CompletableFuture.supplyAsync(() -> {
         RedisFuture<KeyValue<String, String>> rf = redisBlock.blpop(0, "keyZ");
         latch.countDown();
         return rf.toCompletableFuture();
      }, testExecutor()).thenCompose(v -> v);
      // Ensure lpush is after blpop
      try {
         assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
         redis.lpush("keyZ", "firstZ");
         var res = cf.get(3, TimeUnit.SECONDS);
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
         RespTestingUtil.killClient(client);
      }
   }

   @Test
   public void testBlpopTimeout() throws InterruptedException, ExecutionException {
      RedisCommands<String, String> redis = redisConnection.sync();
      var client = createClient(30000, server.getPort());
      RedisAsyncCommands<String, String> redisAsync = client.connect().async();
      redis.rpush("key1", "first", "second", "third");
      assertThatThrownBy(() -> {
         redisAsync.blpop(-1, "keyZ").get();
      }).cause().isInstanceOf(RedisCommandExecutionException.class)
            .hasMessageContaining("ERR value is out of range, must be positive");
      var res = redisAsync.blpop(1, "keyZ");
      // Ensure blpop is expired
      res.get();
      redis.lpush("keyZ", "firstZ");
      assertThat(res.get()).isNull();

      CountDownLatch latch = new CountDownLatch(1);
      var cf = CompletableFuture.supplyAsync(() -> {
         var rf = redisAsync.blpop(1, "keyY");
         latch.countDown();
         return rf.toCompletableFuture();
      }, testExecutor()).thenCompose(v -> v);
      // Ensure lpush is after blpop
      try {
         assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
         Thread.sleep(500);
         redis.lpush("keyY", "valueY");
         assertThat(cf.get().getKey()).isEqualTo("keyY");
         assertThat(cf.get().getValue()).isEqualTo("valueY");
      } finally {
         RespTestingUtil.killClient(client);
      }
   }
}
