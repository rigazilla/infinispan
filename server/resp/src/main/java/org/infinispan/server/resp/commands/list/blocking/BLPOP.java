package org.infinispan.server.resp.commands.list.blocking;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
            pubSubListener.synchronizer.completeExceptionally(t);
            return;
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
               pubSubListener.synchronizer.completeExceptionally(t);
               return;
            }
            pubSubListener.synchronizer.onPollComplete(v);
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
      Resp3Handler handler;
      PollListenerSynchronizer synchronizer;
      Runnable operation;
      public void setListenerAdded(boolean listenerAdded) {
         this.listenerAdded = listenerAdded;
      }

      public PubSubListener(Resp3Handler handler, AdvancedCache<Object, Object> cache,
            EmbeddedMultimapListCache<byte[], byte[]> mml) {
         this.multimapList = mml;
         this.cache = cache;
         this.handler = handler;
         this.synchronizer = new PollListenerSynchronizer();
         this.synchronizer.operation = () -> {
               multimapList.pollFirst(synchronizer.getSavedKey(), 1)
                     .whenComplete( (eventVal,t3) -> {
                     if (t3 != null || eventVal == null || eventVal.size() == 0) {
                        synchronizer.completeExceptionally(
                              t3 != null ? t3 : new AssertionError("Unexpected empty or null ListBucket"));
                     } else {
                        byte[] value = eventVal.iterator().next();
                        synchronizer.complete(Arrays.asList(synchronizer.getSavedKey(), value));
                     }});
            };

         synchronizer.resultFuture.whenComplete((ignore_v, ignore_t) -> {
            this.deleteTimer();
            if (listenerAdded) {
               cache.removeListenerAsync(this);
            }
         });
      }

      public CompletableFuture<Collection<byte[]>> getFuture() {
         return synchronizer.resultFuture;
      }

      public void startTimer(long timeout) {
         deleteTimer();
         scheduledTimer = (timeout > 0) ? handler.getScheduler().schedule(() -> {
            if (listenerAdded) {
               cache.removeListenerAsync(this);
            }
            synchronizer.complete(null);
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
               synchronizer.onEvent(key);
            }
         } catch (Exception ex) {
            this.synchronizer.completeExceptionally(ex);
         }
         return CompletableFutures.completedNull();
      }

      private byte[] unwrapKey(Object key) {
         return key instanceof WrappedByteArray
               ? ((WrappedByteArray) key).getBytes()
               : (byte[]) key;
      }
   }

   /**
    * PollListenerSynchronizer
    *
    * This class synchronizes the access to a CompletableFuture `resultFuture` so
    * that its final value will be completed either
    * - with value v by an onPollComplete(v,r) call with v!=null;
    * - by `opEv.apply(k)`, if `onPollComplete(null, opPoll)` and then `onEvent(k, opEv)` are called;
    * - by `opPoll.apply(k)`, if `onEvent(k, opEv)` and then `onPollComplete(null, opPoll)` are called.
    *
    */
   public static class PollListenerSynchronizer {
      public CountDownLatch eventReceivedLatch;
      public CountDownLatch pollGotNullResultLatch;
      private AtomicReference<byte[]> atomicSavedKey;
      private CompletableFuture<Collection<byte[]>> resultFuture;
      public Runnable operation;

      public enum State {
         POLL_ACTIVE,
         DO,
         COMPLETE,
      }

      private volatile AtomicReference<State> state;

      public PollListenerSynchronizer() {
         atomicSavedKey = new AtomicReference<byte[]>();
         resultFuture = new CompletableFuture<Collection<byte[]>>();
         eventReceivedLatch = new CountDownLatch(1);
         pollGotNullResultLatch = new CountDownLatch(1);
         state = new AtomicReference<BLPOP.PollListenerSynchronizer.State>(State.POLL_ACTIVE);
      }

      public CompletableFuture<Collection<byte[]>> getResultFuture() {
         return resultFuture;
      }

      public void complete(Collection<byte[]> entry) {
         resultFuture.complete(entry);
      }

      public void completeExceptionally(Throwable t) {
         resultFuture.completeExceptionally(t);
      }

      public byte[] getSavedKey() {
         return atomicSavedKey.get();
      }

      public void onEvent(byte[] key) {
         eventReceivedLatch.countDown();
         this.atomicSavedKey.compareAndSet(null, key);
         if (state.compareAndSet(State.DO, State.COMPLETE)) {
            operation.run();
         }
      }

      public void onPollComplete(Collection<byte[]> v) {
         if (v != null) {
            // If got result complete poll stage
            state.set(State.COMPLETE);
            complete(v);
         } else {
            pollGotNullResultLatch.countDown();
            state.set(State.DO);
            // Poll result is null. If got event from listener use it
            if (getSavedKey() != null && state.compareAndSet(State.DO, State.COMPLETE)) {
               operation.run();
            }
         }

      }
   }
}
