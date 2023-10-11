package org.infinispan.server.resp.commands.list.blocking;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.infinispan.AdvancedCache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.encoding.DataConversion;
import org.infinispan.multimap.impl.EmbeddedMultimapListCache;
import org.infinispan.multimap.impl.ListBucket;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.notifications.cachelistener.filter.CacheEventConverter;
import org.infinispan.server.resp.Consumers;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespErrorUtil;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.commands.ArgumentUtils;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.filter.EventListenerKeysFilter;
import org.infinispan.server.resp.logging.Log;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * @link https://redis.io/commands/blpop/
 * @since 15.0
 */
public class BLPOP extends RespCommand implements Resp3Command {
   private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass(), Log.class);

   public BLPOP() {
      super(-3, 1, -2, 1);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
         ChannelHandlerContext ctx,
         List<byte[]> arguments) {
      EmbeddedMultimapListCache<byte[], byte[]> listMultimap = handler.getListMultimap();
      var lastKeyIdx = arguments.size() - 1;
      var filterKeys = arguments.subList(0, lastKeyIdx);
      // Using last arg as timeout if it can be a double
      var argTimeout = ArgumentUtils.toDouble(arguments.get(lastKeyIdx));
      if (argTimeout < 0) {
         RespErrorUtil.mustBePositive(handler.allocator());
         return handler.myStage();
      }
      long timeout = (long) (argTimeout * Duration.ofSeconds(1).toMillis());

      // If all the keys are empty or null, create a listener for each key
      // otherwise return the left value of the first non empty list
      var pollStage = pollAllKeys(listMultimap, arguments);
      // If no value returned, we need subscribers
      return handler.stageToReturn(pollStage.thenCompose(v -> {
         // addSubscriber call can rise exception that needs to be reported
         // as error
            var retStage = (v != null && !v.isEmpty())
                  ? CompletableFuture.completedFuture(v)
                  : addSubscriber(listMultimap, filterKeys, timeout, handler, ctx);
                  return retStage;
      }), ctx, Consumers.COLLECTION_BULK_BICONSUMER);
   }

   CompletionStage<Collection<byte[]>> pollKeyValue(EmbeddedMultimapListCache<byte[], byte[]> mmList, byte[] key) {
      return mmList.pollFirst(key, 1)
            .thenApply((v) -> (v == null || v.isEmpty())
                  ? null
                  : Arrays.asList(key, v.iterator().next()));
   }

   CompletionStage<Collection<byte[]>> addSubscriber(EmbeddedMultimapListCache<byte[], byte[]> listMultimap,
         List<byte[]> filterKeys, long timeout, Resp3Handler handler,
         ChannelHandlerContext ctx) {
      if (log.isTraceEnabled()) {
         log.tracef("Subscriber for keys: " +
               filterKeys.toString());
      }
      AdvancedCache<Object, Object> cache = handler.cache().withMediaType(MediaType.APPLICATION_OCTET_STREAM, null);
      DataConversion vc = cache.getValueDataConversion();
      PubSubListener pubSubListener = new PubSubListener(ctx.channel());
      EventListenerKeysFilter filter = new EventListenerKeysFilter(filterKeys.toArray(byte[][]::new));
      CacheEventConverter<Object, Object, Object> converter = (key, oldValue, oldMetadata, newValue, newMetadata,
            eventType) -> vc.fromStorage(newValue);
      cache.addListener(pubSubListener, filter, converter);
      final ScheduledFuture<?> scheduledTimer = (timeout > 0) ? ctx.channel().eventLoop().schedule(() -> {
         pubSubListener.getFuture().complete(null);
      }, timeout, TimeUnit.MILLISECONDS) : null;
      pubSubListener.getFuture().thenAccept(ignore -> {
         if (scheduledTimer != null) {
            scheduledTimer.cancel(true);
         }
      });
      pollAllKeys(listMultimap, filterKeys).thenAccept(v -> {
         if (v != null) {
            pubSubListener.getFuture().complete(v);
         }
      });
      // listnerStage.toCompletableFuture().join();
      return pubSubListener.getFuture();
   }
   // pubSubListener.future = CompletableFuture.failedFuture(new
   // RuntimeException());

   // <Void>handle((s,t) ->
   // {
   // if (t!=null) {
   // pubSubListener.getFuture().completeExceptionally(t);
   // }
   // pubSubListener.getFuture().completeExceptionally(t);
   // // pollAllKeys(listMultimap, filterKeys)
   // // pubSubListener.getFuture().thenAccept(pollAllKeys(listMultimap,
   // filterKeys)).thenAccept(v -> {
   // // if (v != null) {
   // // pubSubListener.getFuture().complete(v);
   // // if (scheduledTimer != null) {
   // // scheduledTimer.cancel(true);
   // // }
   // // }
   // // });}
   // });
   // return pubSubListener.getFuture();
   // // // start a timer if needed
   // // final ScheduledFuture<?> scheduledTimer = (timeout > 0) ?
   // ctx.channel().eventLoop().schedule(() -> {
   // // pubSubListener.getFuture().complete(null);
   // // }, timeout, TimeUnit.MILLISECONDS) : null;
   // // // Listener can lose events during its install, so we need to poll again
   // // // and if we get values complete the listener future.
   // // listnerStage.thenCompose(v -> pollAllKeys(listMultimap,
   // filterKeys)).thenAccept(v -> {
   // // if (v != null) {
   // // pubSubListener.getFuture().complete(v);
   // // if (scheduledTimer != null) {
   // // scheduledTimer.cancel(true);
   // // }
   // // }
   // // });
   // // // Uninstall the listener when got values
   // // return pubSubListener.getFuture().thenApply(v -> {
   // // if (scheduledTimer != null) {
   // // scheduledTimer.cancel(true);
   // // }
   // // handler.cache().removeListener(pubSubListener);
   // // return v;
   // // });
   // });

   private CompletionStage<Collection<byte[]>> pollAllKeys(EmbeddedMultimapListCache<byte[], byte[]> listMultimap,
         List<byte[]> filterKeys) {
      var pollStage = pollKeyValue(listMultimap, filterKeys.get(0));
      for (int i = 1; i < filterKeys.size(); ++i) {
         var keyChannel = filterKeys.get(i);
         pollStage = pollStage.thenCompose(
               v -> (v == null || v.isEmpty())
                     ? pollKeyValue(listMultimap, keyChannel)
                     : CompletableFuture.completedFuture(v));
      }
      return pollStage;
   }

   @Listener(clustered = true)
   public static class PubSubListener {
      private final Channel channel;
      private CompletableFuture<Collection<byte[]>> future = new CompletableFuture<>();

      public CompletableFuture<Collection<byte[]>> getFuture() {
         return future;
      }

      public PubSubListener(Channel channel) {
         this.channel = channel;
      }

      @CacheEntryCreated
      public CompletionStage<Void> onEvent(CacheEntryEvent<Object, Object> entryEvent) {
         if (entryEvent.getValue() instanceof ListBucket) {
            byte[] key = unwrapKey(entryEvent.getKey());
            var bucket = (ListBucket<byte[]>) entryEvent.getValue();
            byte[] value = bucket == null ? null : bucket.toDeque().peek();
            future.complete(Arrays.asList(key, value));
         }
         return CompletableFutures.completedNull();
      }

      private byte[] unwrapKey(Object key) {
         return key instanceof WrappedByteArray
               ? ((WrappedByteArray) key).getBytes()
               : (byte[]) key;
      }
   }
}
