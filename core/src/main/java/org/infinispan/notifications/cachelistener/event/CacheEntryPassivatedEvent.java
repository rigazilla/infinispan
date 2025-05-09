package org.infinispan.notifications.cachelistener.event;

/**
 * This event subtype is passed in to any method annotated with
 * {@link org.infinispan.notifications.cachelistener.annotation.CacheEntryPassivated}.
 *
 * @author Manik Surtani
 * @author Galder Zamarreño
 * @since 5.0
 */
public interface CacheEntryPassivatedEvent<K, V> extends CacheEntryEvent<K, V> {
   /**
    * Retrieves the value of the entry being passivated.
       * @return the value of the entry being passivated, if <code>isPre()</code> is <code>true</code>.  <code>null</code> otherwise.
    */
   V getValue();
}
