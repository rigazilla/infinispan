package org.infinispan.server.resp.commands.sortedset.internal;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import org.infinispan.multimap.impl.EmbeddedMultimapSortedSetCache;
import org.infinispan.multimap.impl.ScoredValue;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.commands.ArgumentUtils;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.response.ScoredValueSerializer;
import org.infinispan.server.resp.serialization.ResponseWriter;

import io.netty.channel.ChannelHandlerContext;

/**
 * Common implementation for ZPOP commands
 */
public abstract class POP extends RespCommand implements Resp3Command {
   private static final BiConsumer<Object, ResponseWriter> SERIALIZER = (res, writer) -> {
      if (res instanceof Collection<?>) {
         @SuppressWarnings("unchecked")
         Collection<ScoredValue<byte[]>> cast = (Collection<ScoredValue<byte[]>>) res;
         writer.array(cast, ScoredValueSerializer.WITH_SCORE);
         return;
      }

      @SuppressWarnings("unchecked")
      ScoredValue<byte[]> sv = (ScoredValue<byte[]>) res;
      writer.write(sv, ScoredValueSerializer.WITH_SCORE);
   };

   private final boolean min;

   public POP(boolean min, long aclMask) {
      super(-2, 1, 1, 1, aclMask);
      this.min = min;
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {

      byte[] name = arguments.get(0);
      EmbeddedMultimapSortedSetCache<byte[], byte[]> sortedSetCache = handler.getSortedSeMultimap();

      long count;
      boolean hasCount;
      if (arguments.size() > 1) {
         try {
            count = ArgumentUtils.toLong(arguments.get(1));
            hasCount = true;
            if (count < 0) {
               handler.writer().mustBePositive();
               return handler.myStage();
            }
         } catch (NumberFormatException e) {
            handler.writer().mustBePositive();
            return handler.myStage();
         }
      } else {
         count = 1;
         hasCount = false;
      }

      CompletionStage<Object> popElements = sortedSetCache.pop(name, min, count).thenApply(r -> {
         if (r.isEmpty() || hasCount) return r;
         return r.iterator().next();
      });
      return handler.stageToReturn(popElements, ctx, SERIALIZER);
   }
}
