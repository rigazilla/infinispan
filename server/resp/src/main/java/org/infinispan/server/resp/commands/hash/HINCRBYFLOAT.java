package org.infinispan.server.resp.commands.hash;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.multimap.impl.EmbeddedMultimapPairCache;
import org.infinispan.server.resp.Consumers;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespErrorUtil;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.commands.ArgumentUtils;
import org.infinispan.server.resp.commands.Resp3Command;

import io.netty.channel.ChannelHandlerContext;

/**
 * `<code>HINCRBYFLOAT key field increment</code>` command.
 * <p>
 * Increments the value at the <code>field</code> in the hash map stored at <code>key</code> by <code>increment</code>.
 * The command fails if the stored value is not a number.
 *
 * @since 15.0
 * @see <a href="https://redis.io/commands/hincrbyfloat/">Redis Documentation</a>
 * @author José Bolina
 */
public class HINCRBYFLOAT extends RespCommand implements Resp3Command {

   private static final int SUCCESS = 0;
   private static final int NOT_A_FLOAT = 1;
   private static final int NAN_OR_INF = 2;

   public HINCRBYFLOAT() {
      super(4, 1, 1, 1);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler, ChannelHandlerContext ctx, List<byte[]> arguments) {
      EmbeddedMultimapPairCache<byte[], byte[], byte[]> multimap = handler.getHashMapMultimap();
      double delta = ArgumentUtils.toDouble(arguments.get(2));
      if (!ArgumentUtils.isFloatValid(delta)) {
         RespErrorUtil.nanOrInfinity(handler.allocator());
         return handler.myStage();
      }

      AtomicInteger status = new AtomicInteger(SUCCESS);
      CompletionStage<byte[]> cs = multimap.compute(arguments.get(0), arguments.get(1), (ignore, prev) -> {
         if (prev == null) return arguments.get(2);
         try {
            double prevDouble = ArgumentUtils.toDouble(prev);
            double after = prevDouble + delta;
            if (!ArgumentUtils.isFloatValid(after)) {
               status.set(NAN_OR_INF);
               return prev;
            }
            return ArgumentUtils.toByteArray(prevDouble + delta);
         } catch (NumberFormatException nfe) {
            status.set(NOT_A_FLOAT);
            return prev;
         }
      });
      return handler.stageToReturn(cs, ctx, (res, alloc) -> {
         switch (status.get()) {
            case NOT_A_FLOAT -> RespErrorUtil.customError("hash value is not a float", alloc);
            case NAN_OR_INF -> RespErrorUtil.nanOrInfinity(alloc);
            default -> Consumers.GET_BICONSUMER.accept(res, alloc);
         }
      });
   }
}
