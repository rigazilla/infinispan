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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

      // If all the keys are empty or null, create a listener
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

   CompletionStage<Collection<byte[]>> addSubscriber(EmbeddedMultimapListCache<byte[], byte[]> listMultimap,
         List<byte[]> filterKeys, long timeout, Resp3Handler handler,
         ChannelHandlerContext ctx) {
      if (log.isTraceEnabled()) {
         log.tracef("Subscriber for keys: " +
               filterKeys.toString());
      }
      AdvancedCache<Object, Object> cache = handler.cache().withMediaType(MediaType.APPLICATION_OCTET_STREAM, null);
      DataConversion vc = cache.getValueDataConversion();
      PubSubListener pubSubListener = new PubSubListener(handler, cache, listMultimap);
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
         // Start a timer if required
         pubSubListener.startTimer(timeout);
         pollAllKeys(listMultimap, filterKeys).whenComplete((v, t2) -> {
            // If second poll fails, remove listener and complete exceptionally
            if (t2 != null) {
               pubSubListener.completeExceptionally(t);
            }
            if (v != null) {
               // If got result complete poll stage
               pubSubListener.completePoll(v);
            } else {
               // Poll result is null. If got event from listener use it
               if (pubSubListener.getSavedKey() != null) {
                  pubSubListener.multimapList.pollFirst(pubSubListener.getSavedKey(), 1).thenApply(eventVal -> {
                     pubSubListener.completePoll(eventVal);
                     return null;
                  });
               } else {
                  // Poll phase completed, allow listener to continue the work
                  pubSubListener.pollComplete = true;
               }
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

   CompletionStage<Collection<byte[]>> pollKeyValue(EmbeddedMultimapListCache<byte[], byte[]> mmList, byte[] key) {
      return mmList.pollFirst(key, 1)
            .thenApply((v) -> (v == null || v.isEmpty())
                  ? null
                  : Arrays.asList(key, v.iterator().next()));
   }

   @Listener(clustered = true)
   public static class PubSubListener {
      public EmbeddedMultimapListCache<byte[], byte[]> multimapList;
      public AdvancedCache<Object, Object> cache;
      ScheduledFuture<?> scheduledTimer;
      private boolean listenerAdded;
      private CompletableFuture<Collection<byte[]>> pollFuture;
      public boolean pollComplete;
      private AtomicBoolean executed;
      public AtomicReference<byte[]> atomicSavedKey;
      Resp3Handler handler;

      public void setListenerAdded(boolean listenerAdded) {
         this.listenerAdded = listenerAdded;
      }

      public PubSubListener(Resp3Handler handler, AdvancedCache<Object, Object> cache,
            EmbeddedMultimapListCache<byte[], byte[]> mml) {
         this.multimapList = mml;
         this.cache = cache;
         this.handler = handler;
         pollFuture = new CompletableFuture<Collection<byte[]>>();
         executed = new AtomicBoolean();
         atomicSavedKey = new AtomicReference<byte[]>();
         pollFuture.whenComplete((ignore_v, ignore_t) -> {
            this.deleteTimer();
            if (listenerAdded) {
               cache.removeListenerAsync(this);
            }
         });
      }

      public byte[] getSavedKey() {
         return atomicSavedKey.get();
      }

      public CompletableFuture<Collection<byte[]>> getFuture() {
         return pollFuture;
      }

      public void completePoll(Collection<byte[]> entry) {
         pollFuture.complete(entry);
      }

      public void completeExceptionally(Throwable t) {
         pollFuture.completeExceptionally(t);
      }

      public void startTimer(long timeout) {
         deleteTimer();
         scheduledTimer = (timeout > 0) ? handler.getScheduler().schedule(() -> {
            if (listenerAdded) {
               cache.removeListenerAsync(this);
            }
            pollFuture.complete(null);
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
               if (!pollComplete) {
                  // `addSubscriber` is still executing `pollAllKeys`, just save
                  // the key `addSubscriber` will eventually use it
                  atomicSavedKey.compareAndExchange(null, key);
               } else {
                  // poll thread is completed, listener can execute the operation
                  // Execute just once
                  if (executed.compareAndSet(false, true)) {
                     // It's possible that an event has been received, stored in eventKey and not
                     // processed by `addSubscriber` code. If this is the case use eventKey in place
                     // of the received one
                     final byte[] finalKey = getSavedKey() != null ? getSavedKey() : key;
                     multimapList.pollFirst(finalKey, 1).handle((v, t) -> {
                        if (t != null || v == null || v.size() == 0) {
                           this.completeExceptionally(
                                 t != null ? t : new AssertionError("Unexpected empty or null ListBucket"));
                        } else {
                           byte[] value = v.iterator().next();
                           completePoll(Arrays.asList(key, value));
                        }
                        return null;
                     });
                  }
               }
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
