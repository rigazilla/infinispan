package org.infinispan.commands;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletionStage;

import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextFactory;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.util.concurrent.locks.RemoteLockCommand;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;


/**
 * A type of command that can accept {@link Visitor}s, such as {@link org.infinispan.interceptors.DDAsyncInterceptor}.
 *
 * @author Manik Surtani (<a href="mailto:manik@jboss.org">manik@jboss.org</a>)
 * @since 4.0
 */
public interface VisitableCommand extends CacheRpcCommand {

   static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass());

   default void init(ComponentRegistry registry) {
      // no-op
   }

   default CompletionStage<?> invokeAsync(ComponentRegistry registry) throws Throwable {
      this.init(registry);
      InvocationContextFactory icf = registry.getInvocationContextFactory().running();
      InvocationContext ctx = icf.createRemoteInvocationContextForCommand(this, getOrigin());
      if (this instanceof RemoteLockCommand remoteLockCmd) {
         ctx.setLockOwner(remoteLockCmd.getKeyLockOwner());
      }
      if (log.isTraceEnabled())
         log.tracef("Invoking command %s, with originLocal flag set to %b", this, ctx.isOriginLocal());
      return registry.getInterceptorChain().running().invokeAsync(ctx, this);
   }

   /**
    * Accept a visitor, and return the result of accepting this visitor.
    *
    * @param ctx     invocation context
    * @param visitor visitor to accept
    * @return arbitrary return value
    * @throws Throwable in the event of problems
    */
   Object acceptVisitor(InvocationContext ctx, Visitor visitor) throws Throwable;

   /**
    * @return Nodes on which the command needs to read the previous values of the keys it acts on.
    * @throws UnsupportedOperationException if the distinction does not make any sense.
    */
   LoadType loadType();

   enum LoadType {
      /**
       * Never load previous value.
       */
      DONT_LOAD,
      /**
       * In non-transactional cache, load previous value only on the primary owner.
       * In transactional cache, the value is fetched to originator. Primary then does not have to
       * load the value but for write-skew check.
       */
      PRIMARY,
      /**
       * In non-transactional cache, load previous value on both primary and backups.
       * In transactional cache, the value is both fetched to originator and all owners have to load
       * it because it is needed to produce the new value.
       */
      OWNER
   }
}
