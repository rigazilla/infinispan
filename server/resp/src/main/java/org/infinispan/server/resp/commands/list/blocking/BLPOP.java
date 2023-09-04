package org.infinispan.server.resp.commands.list.blocking;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.encoding.DataConversion;
import org.infinispan.multimap.impl.EmbeddedMultimapListCache;
import org.infinispan.multimap.impl.ListBucket;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.server.resp.Consumers;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.SubscriberHandler;
import org.infinispan.server.resp.commands.ArgumentUtils;
import org.infinispan.server.resp.commands.PubSubResp3Command;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.filter.EventListenerKeysFilter;
import org.infinispan.server.resp.logging.Log;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * @link https://redis.io/commands/subscribe/
 * @since 14.0
 */
public class BLPOP extends RespCommand implements Resp3Command, PubSubResp3Command {
   private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass(), Log.class);
   public BLPOP() {
      super(-3, 1, -2, 1);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
         ChannelHandlerContext ctx,
         List<byte[]> arguments) {
      SubscriberHandler subscriberHandler = new SubscriberHandler(handler.respServer(), handler);
      return subscriberHandler.handleRequest(ctx, this, arguments);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(SubscriberHandler handler,
         ChannelHandlerContext ctx,
         List<byte[]> arguments) {
      // Using last arg as timeout if it can be a double
      int lastKeyIdx = arguments.size() - 1;
      var timeout = ArgumentUtils.toDouble(arguments.get(arguments.size() - 1));
      EmbeddedMultimapListCache<byte[], byte[]> listMultimap = handler.resp3Handler().getListMultimap();
      // If all the keys are empty or null, create a listener for each key
      // otherwise return the left value of the first non empty list
      var pollStage = pollKeyValue(listMultimap, arguments.get(0));
      for (int i = 1; i < lastKeyIdx; ++i) {
         var keyChannel = arguments.get(i);
         // chain all polls returning first not null value or null/empty list
         pollStage = pollStage.thenCompose(
               v -> (v == null || v.isEmpty())
                     ? pollKeyValue(listMultimap, keyChannel)
                     : CompletableFuture.completedFuture(v));
      }
      // If no value returned, we need subscribers
      return pollStage.thenCompose(v ->
            handler.stageToReturn(
                (v != null && !v.isEmpty())
               ? CompletableFuture.completedFuture(v)
               : addSubscriber(arguments, lastKeyIdx, handler, ctx)
               , ctx, Consumers.COLLECTION_BULK_BICONSUMER));
   }

   CompletionStage<Collection<byte[]>> pollKeyValue(EmbeddedMultimapListCache<byte[], byte[]> cache, byte[] key) {
      return cache.pollFirst(key, 1)
            .thenApply((v) -> (v == null || v.isEmpty())
                  ? null
                  : Arrays.asList(key, v.iterator().next()));
   }

   CompletionStage<Collection<byte[]>> addSubscriber(List<byte[]> arguments, int lastKeyIdx, SubscriberHandler handler,
         ChannelHandlerContext ctx) {
      var filterKeys = arguments.subList(0, lastKeyIdx);
         if (log.isTraceEnabled()) {
            log.tracef("Subscriber for keys: " +
                  filterKeys.toString());
         }
         PubSubListener pubSubListener = new PubSubListener(ctx.channel(),
               handler.cache().getKeyDataConversion(),
               handler.cache().getValueDataConversion());
         // TODO: should depend from the addListenerAsync future?
         handler.cache().addListenerAsync(pubSubListener,
               new EventListenerKeysFilter(filterKeys.toArray(byte[][]::new)), null);
      return pubSubListener.getFuture();
   }

   @Listener(clustered = true)
   public static class PubSubListener extends SubscriberHandler.PubSubListener {
      private final Channel channel;
      private final DataConversion keyConversion;
      private final DataConversion valueConversion;
      private CompletableFuture<Collection<byte[]>> future = new CompletableFuture<>();

      public CompletableFuture<Collection<byte[]>> getFuture() {
         return future;
      }

      public PubSubListener(Channel channel, DataConversion keyConversion, DataConversion valueConversion) {
         super(channel, keyConversion, valueConversion);
         this.channel = channel;
         this.keyConversion = keyConversion;
         this.valueConversion = valueConversion;
      }

      @CacheEntryCreated
      @CacheEntryModified
      public CompletionStage<Void> onEvent(CacheEntryEvent<Object, Object> entryEvent) {
         byte[] key = (byte[]) keyConversion.fromStorage(entryEvent.getKey());
         ListBucket<byte[]> bucket = (ListBucket<byte[]>) entryEvent.getValue();
         byte[] value = bucket == null ? null : bucket.toDeque().peek();
         future.complete(Arrays.asList(key,value));
         return CompletableFutures.completedNull();
      }
   }

}
