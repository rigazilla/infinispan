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
      PubSubListener pubSubListener = new PubSubListener(ctx.channel(), cache, listMultimap);
      EventListenerKeysFilter filter = new EventListenerKeysFilter(filterKeys.toArray(byte[][]::new));
      CacheEventConverter<Object, Object, Object> converter = (key, oldValue, oldMetadata, newValue, newMetadata,
            eventType) -> vc.fromStorage(newValue);
      CompletionStage<Void> addListenerStage = cache.addListenerAsync(pubSubListener, filter, converter);
      addListenerStage.whenComplete((ignore, t) -> {
         // If listener fails to install, complete exceptionally pubSubFuture and return
         if (t != null) {
            pubSubListener.completeExceptionally(t);
         }
         pubSubListener.setListenerAdded(true);
         // Listener can lose events during its install, so we need to poll again
         // and if we get values complete the listener future. In case of exception
         // completeExceptionally
         pollAllKeys(listMultimap, filterKeys).whenComplete((v, t2) -> {
            // If second poll fails, remove listener and complete exceptionally
            if (t2 != null) {
               pubSubListener.completeExceptionally(t);
            }
            // Complete poll stage
            pubSubListener.completePoll(v);
            if (v == null) {
               // If no value, start a timer if required
               pubSubListener.startTimer(timeout);
            }
         });
      });
      return pubSubListener.getFuture();
   }

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
      EmbeddedMultimapListCache<byte[], byte[]> multimapList;
      public AdvancedCache<Object, Object> cache;
      ScheduledFuture<?> scheduledTimer;
      private boolean listenerAdded = false;
      private CompletableFuture<Collection<byte[]>> listnerFuture = new CompletableFuture<>();
      private CompletableFuture<Collection<byte[]>> pollFuture = new CompletableFuture<>();
      private CompletableFuture<Collection<byte[]>> future;

      public void setListenerAdded(boolean listenerAdded) {
         this.listenerAdded = listenerAdded;
      }

      public PubSubListener(Channel channel, AdvancedCache<Object, Object> cache,
            EmbeddedMultimapListCache<byte[], byte[]> mml) {
         this.channel = channel;
         this.multimapList = mml;
         this.cache = cache;
         // This future sync poll thread and listener. It waits for the poll stage completion
         // and its value returned if available otherwise wait for events
         future = pollFuture.thenCompose((v) -> v != null ? CompletableFuture.completedFuture(v) : listnerFuture)
               .whenComplete((ignore_v, ignore_t) -> {
                  try {
                     this.deleteTimer();
                     if (listenerAdded) {
                        cache.removeListenerAsync(this);
                     }
                  } catch (Exception ex) {
                     System.out.println(ex.toString());
                  }
               });
      }

      public CompletableFuture<Collection<byte[]>> getFuture() {
         return future;
      }

      public void completePoll(Collection<byte[]> entry) {
         pollFuture.complete(entry);
      }

      public void completeExceptionally(Throwable t) {
         future.completeExceptionally(t);
      }

      public void startTimer(long timeout) {
         deleteTimer();
         scheduledTimer = (timeout > 0) ? channel.eventLoop().schedule(() -> {
            if (listenerAdded) {
               cache.removeListenerAsync(this);
            }
            future.complete(null);
         }, timeout, TimeUnit.MILLISECONDS) : null;
      }

      public void deleteTimer() {
         if (scheduledTimer != null)
            scheduledTimer.cancel(true);
         scheduledTimer = null;
      }

      @CacheEntryCreated
      public CompletionStage<Void> onEvent(CacheEntryEvent<Object, Object> entryEvent) {
         try {
            if (entryEvent.getValue() instanceof ListBucket) {
               byte[] key = unwrapKey(entryEvent.getKey());
               multimapList.pollFirst(key, 1).handle((v, t) -> {
                  if (t != null || v == null || v.size() == 0) {
                     this.completeExceptionally(
                           t != null ? t : new AssertionError("Unexpected empty or null ListBucket"));
                  } else {
                     byte[] value = v.iterator().next();
                     listnerFuture.complete(Arrays.asList(key, value));
                  }
                  return null;
               });
            }
         } catch (Exception ex) {
            this.completeExceptionally(ex);
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
