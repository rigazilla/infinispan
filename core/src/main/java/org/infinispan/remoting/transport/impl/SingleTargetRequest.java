package org.infinispan.remoting.transport.impl;

import static org.infinispan.util.logging.Log.CLUSTER;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.infinispan.commons.util.Util;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.transport.AbstractRequest;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.ResponseCollector;
import org.infinispan.remoting.transport.jgroups.RequestTracker;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Request implementation that waits for a response from a single target node.
 *
 * @author Dan Berindei
 * @since 9.1
 */
public class SingleTargetRequest<T> extends AbstractRequest<Address, T> {
   private static final Log log = LogFactory.getLog(SingleTargetRequest.class);

   // Only changes from non-null to null
   private final AtomicReference<RequestTracker> requestTracker;

   public SingleTargetRequest(ResponseCollector<Address, T> wrapper, long requestId, RequestRepository repository, RequestTracker requestTracker) {
      super(requestId, wrapper, repository);
      this.requestTracker = new AtomicReference<>(Objects.requireNonNull(requestTracker));
   }

   @Override
   public void onResponse(Address sender, Response response) {
      RequestTracker tracker = requestTracker.getAndSet(null);
      try {
         if (tracker != null) {
            if (!tracker.destination().equals(sender)) {
               log.tracef("Received unexpected response to request %d from %s, target is %s", requestId, sender, tracker.destination());
            }
            T result = addResponse(sender, tracker, response);
            complete(result);
         }
      } catch (Exception e) {
         completeExceptionally(e);
      }
   }

   @Override
   public boolean onNewView(Set<Address> members) {
      RequestTracker tracker = requestTracker.get();
      try {
         if (tracker == null || members.contains(tracker.destination()) || requestTracker.getAndSet(null) != tracker) {
            return false;
         }
         T result = addResponse(tracker.destination(), tracker, CacheNotFoundResponse.INSTANCE);
         complete(result);
      } catch (Exception e) {
         completeExceptionally(e);
      }
      return true;
   }

   private T addResponse(Address sender, RequestTracker tracker, Response response) {
      tracker.onComplete();
      T result = responseCollector.addResponse(sender, response);
      if (result == null) {
         result = responseCollector.finish();
      }
      return result;
   }

   @Override
   protected void onTimeout() {
      RequestTracker tracker = requestTracker.getAndSet(null);
      if (tracker != null) {
         tracker.onTimeout();
         String targetString = tracker.destination().toString();
         completeExceptionally(CLUSTER.requestTimedOut(requestId, targetString, Util.prettyPrintTime(getTimeoutMs())));
      }
   }
}
