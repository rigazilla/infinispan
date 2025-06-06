package org.infinispan.server.resp.commands.sortedset;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.infinispan.multimap.impl.EmbeddedMultimapSortedSetCache;
import org.infinispan.multimap.impl.ScoredValue;
import org.infinispan.server.resp.AclCategory;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.commands.ArgumentUtils;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.serialization.ResponseWriter;

import io.netty.channel.ChannelHandlerContext;

/**
 * ZRANDMEMBER
 *
 * @see <a href="https://redis.io/commands/zrandmember/">ZRANDMEMBER</a>
 * When called with just the key argument, return a random element from the sorted set value stored at key.
 * If the provided count argument is positive, return an array of distinct elements.
 * The array's length is either count or the sorted set's cardinality ({@link ZCARD}), whichever is lower.
 *
 * If called with a negative count, the behavior changes and the command is allowed to return the same element
 * multiple times. In this case, the number of returned elements is the absolute value of the specified count.
 *
 * The optional WITHSCORES modifier changes the reply, so it includes the respective scores of the randomly
 * selected elements from the sorted set.
 * <ul>
 *    <li>
 *      Bulk string reply: without the additional count argument,
 *      the command returns a Bulk Reply with the randomly selected element,
 *      or nil when key does not exist.
 *    </li>
 *    <li>
 *       Array reply: when the additional count argument is passed, the
 *       command returns an array of elements, or an empty array when key does not exist.
 *    </li>
 * </ul>
 *
 * If the WITHSCORES modifier is used, the reply is a list elements and their scores from the sorted set.
 *
 * @since 15.0
 */
public class ZRANDMEMBER extends RespCommand implements Resp3Command {
   public ZRANDMEMBER() {
      super(-2, 1, 1, 1, AclCategory.READ.mask() | AclCategory.SORTEDSET.mask() | AclCategory.SLOW.mask());
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {

      byte[] name = arguments.get(0);
      int count = 1;
      final boolean withScores;
      if (arguments.size() > 1) {
         // next argument must be count
         try {
            count = ArgumentUtils.toInt(arguments.get(1));
         } catch (NumberFormatException ex) {
            handler.writer().valueNotInteger();
            return handler.myStage();
         }
      }
      if (arguments.size() > 2) {
         withScores = ZSetCommonUtils.isWithScoresArg(arguments.get(2));
         if (!withScores) {
            handler.writer().syntaxError();
            return handler.myStage();
         }
      } else {
         withScores = false;
      }

      EmbeddedMultimapSortedSetCache<byte[], byte[]> sortedSetCache = handler.getSortedSeMultimap();

      CompletionStage<List<ScoredValue<byte[]>>> randomMembers = sortedSetCache.randomMembers(name, count);
      if (arguments.size() == 1) {
         return handler.stageToReturn(randomMembers.thenApply(r -> r.isEmpty() ? null : r.get(0).getValue()), ctx, ResponseWriter.BULK_STRING_BYTES);
      }

      return handler.stageToReturn(randomMembers.thenApply(r -> ZSetCommonUtils.response(r, withScores)), ctx, ResponseWriter.CUSTOM);
   }
}
