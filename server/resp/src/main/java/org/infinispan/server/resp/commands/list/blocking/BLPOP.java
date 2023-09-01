package org.infinispan.server.resp.commands.list.blocking;

import static org.infinispan.server.resp.RespConstants.CRLF_STRING;

import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.WrappedByteArray;
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
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.CharsetUtil;

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
      for (int i = 0; i < lastKeyIdx; ++i) {
         var keyChannel = arguments.get(i);
         // chain all polls returning first not null value or null/empty list
         pollStage = pollStage.thenCompose(
               v -> (v == null || v.isEmpty())
                     ? pollKeyValue(listMultimap, keyChannel)
                     : CompletableFuture.completedFuture(v));
      }
      // If no value returned, we need subscribers
      return pollStage.thenCompose(v -> (v != null && !v.isEmpty())
            ? handler.stageToReturn(CompletableFuture.completedFuture(v), ctx, Consumers.COLLECTION_BULK_BICONSUMER)
            : handler.dontSendAndBlock(ctx, addSubscribers(arguments, lastKeyIdx, handler, ctx).freeze(), arguments,
                  true));
   }

   CompletionStage<Collection<byte[]>> pollKeyValue(EmbeddedMultimapListCache<byte[], byte[]> cache, byte[] key) {
      return cache.pollFirst(key, 1)
            .thenApply((v) -> (v == null || v.isEmpty())
                  ? null
                  : Arrays.asList(key, v.iterator().next()));
   }

   AggregateCompletionStage<Void> addSubscribers(List<byte[]> arguments, int lastKeyIdx, SubscriberHandler handler,
         ChannelHandlerContext ctx) {
      AggregateCompletionStage<Void> aggregateCompletionStage = CompletionStages.aggregateCompletionStage();
      var filterKeys = arguments.subList(0, lastKeyIdx);
      for (int i = 0; i < lastKeyIdx; ++i) {
         var keyChannel = arguments.get(i);
         if (log.isTraceEnabled()) {
            log.tracef("Subscriber for keys: " +
                  filterKeys.toString());
         }
         PubSubListener pubSubListener = new PubSubListener(ctx.channel(),
               handler.cache().getKeyDataConversion(),
               handler.cache().getValueDataConversion());
         CompletionStage<Void> stage = handler.cache().addListenerAsync(pubSubListener,
               new EventListenerKeysFilter(filterKeys.toArray(byte[][]::new), handler.cache().getKeyDataConversion()),
               null);
         aggregateCompletionStage.dependsOn(handler.handleStageListenerError(stage,
               keyChannel, true));
      }
      return aggregateCompletionStage;
   }

   @Listener(clustered = true)
   public static class PubSubListener extends SubscriberHandler.PubSubListener {
      private final Channel channel;
      private final DataConversion keyConversion;
      private final DataConversion valueConversion;

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
         if (key.length > 0 && value != null && value.length > 0) {
            // *3 + \r\n + $7 + \r\n + message + \r\n + $ + keylength (log10 + 1) + \r\n +
            // key + \r\n +
            // $ + valuelength (log 10 + 1) + \r\n + value + \r\n
            int byteSize = 2 + 2 + (int) Math.log10(key.length) + 1
                  + 2 + key.length + 2 + 1 + (int) Math.log10(value.length) + 1 + 2 + value.length + 2;
            // TODO: this is technically an issue with concurrent events before/after
            // register/unregister message
            ByteBuf byteBuf = channel.alloc().buffer(byteSize, byteSize);
            byteBuf.writeCharSequence("*2\r\n" + key.length + CRLF_STRING, CharsetUtil.US_ASCII);
            byteBuf.writeBytes(key);
            byteBuf.writeCharSequence("\r\n$" + value.length + CRLF_STRING, CharsetUtil.US_ASCII);
            byteBuf.writeBytes(value);
            byteBuf.writeByte('\r');
            byteBuf.writeByte('\n');
            assert byteBuf.writerIndex() == byteSize;
            // TODO: add some back pressure? - something like ClientListenerRegistry?
            channel.writeAndFlush(byteBuf, channel.voidPromise());
         }
         return CompletableFutures.completedNull();
      }
   }

}
