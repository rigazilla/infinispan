package org.infinispan.container;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;

import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.impl.InternalEntryFactory;
import org.infinispan.eviction.impl.PassivationManager;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.metadata.Metadata;

/**
 * The main internal data structure which stores entries. Care should be taken when using this directly as entries
 * could be stored differently than they were given to a {@link org.infinispan.Cache}. If you wish to convert
 * entries to the stored format, you should use the provided {@link org.infinispan.encoding.DataConversion} such as
 * <pre>
 * cache.getAdvancedCache().getKeyDataConversion().toStorage(key);
 * </pre>
 * when dealing with keys or the following when dealing with values
 * <pre>
 * cache.getAdvancedCache().getValueDataConversion().toStorage(value);
 * </pre>
 * You can also convert from storage to the user-provided type by using the
 * {@link org.infinispan.encoding.DataConversion#fromStorage(Object)} method on any value returned from the DataContainer
 * @author Manik Surtani (<a href="mailto:manik@jboss.org">manik@jboss.org</a>)
 * @author Galder Zamarreño
 * @author Vladimir Blagojevic
 * @since 4.0
 */
@Scope(Scopes.NAMED_CACHE)
public interface DataContainer<K, V> extends Iterable<InternalCacheEntry<K, V>> {
   /**
    * Retrieves a cache entry. This method does not update or reorder any of the internal constructs.
    * I.e., expiration does not happen, and in the case of the LRU container, the entry is not
    * moved to the end of the chain.
    *
    * @param k key under which entry is stored
    * @return entry, if it exists, or null if not
    */
   InternalCacheEntry<K, V> peek(Object k);

   /**
    * Puts an entry in the cache along with metadata adding information such lifespan of entry, max idle time, version
    * information...etc.
    *
    * @param k key under which to store entry
    * @param v value to store
    * @param metadata metadata of the entry
    */
   void put(K k, V v, Metadata metadata);

   /**
    * Tests whether an entry exists in the container
    *
    * @param k key to test
    * @return true if entry exists and has not expired; false otherwise
    */
   boolean containsKey(Object k);

   /**
    * Removes an entry from the cache
    *
    * @param k key to remove
    * @return entry removed, or null if it didn't exist or had expired
    */
   InternalCacheEntry<K, V> remove(Object k);

   /**
    * @return count of the number of entries in the container excluding expired entries
        * Default method invokes the {@link #iterator()} method and just counts entries.
    */
   default int size() {
      int size = 0;
      // We have to loop through to make sure to remove expired entries
      for (InternalCacheEntry<K, V> ignore : this) {
         if (++size == Integer.MAX_VALUE) return Integer.MAX_VALUE;
      }
      return size;
   }

   /**
    *
    * @return count of the number of entries in the container including expired entries
    */
   int sizeIncludingExpired();

   /**
    * Removes all entries in the container
    */
   void clear();


   /**
    * Atomically, it removes the key from {@code DataContainer} and passivates it to persistence.
        * The passivation must be done by invoking the method {@link PassivationManager#passivateAsync(InternalCacheEntry)}.
    *
    * @param key The key to evict.
    */
   void evict(K key);

   /**
    * Computes the new value for the key.
        * See {@link org.infinispan.container.DataContainer.ComputeAction#compute(Object,
    * org.infinispan.container.entries.InternalCacheEntry, InternalEntryFactory)}.
    * <p>
    * Note the entry provided to {@link org.infinispan.container.DataContainer.ComputeAction} may be expired as these
    * entries are not filtered as many other methods do.
    * @param key    The key.
    * @param action The action that will compute the new value.
    * @return The {@link org.infinispan.container.entries.InternalCacheEntry} associated to the key.
    */
   InternalCacheEntry<K, V> compute(K key, ComputeAction<K, V> action);

   /**
    * {@inheritDoc}
    * <p>This iterator only returns entries that are not expired, however it will not remove them while doing so.</p>
    * @return iterator that doesn't produce expired entries
    */
   @Override
   Iterator<InternalCacheEntry<K, V>> iterator();

   /**
    * {@inheritDoc}
    * <p>This spliterator only returns entries that are not expired; however, it will not remove them while doing so.</p>
    * @return spliterator that doesn't produce expired entries
    */
   @Override
   default Spliterator<InternalCacheEntry<K, V>> spliterator() {
      return Spliterators.spliterator(iterator(), sizeIncludingExpired(),
            Spliterator.CONCURRENT | Spliterator.NONNULL | Spliterator.DISTINCT);
   }

   /**
    * Same as {@link DataContainer#iterator()} except that is also returns expired entries.
    * @return iterator that returns all entries including expired ones
    */
   Iterator<InternalCacheEntry<K, V>> iteratorIncludingExpired();

   /**
    * Same as {@link DataContainer#spliterator()} except that is also returns expired entries.
    * @return spliterator that returns all entries including expired ones
    */
   default Spliterator<InternalCacheEntry<K, V>> spliteratorIncludingExpired() {
      return Spliterators.spliterator(iteratorIncludingExpired(), sizeIncludingExpired(),
            Spliterator.CONCURRENT | Spliterator.NONNULL | Spliterator.DISTINCT);
   }

   interface ComputeAction<K, V> {

      /**
       * Computes the new value for the key.
       *
       * @return The new {@code InternalCacheEntry} for the key, {@code null} if the entry is to be removed or {@code
       * oldEntry} is the entry is not to be changed (i.e. not entries are added, removed or touched).
       */
      InternalCacheEntry<K, V> compute(K key, InternalCacheEntry<K, V> oldEntry, InternalEntryFactory factory);

   }

   /**
    * Resizes the capacity of the underlying container. This is only supported if the container is bounded.
    * An {@link UnsupportedOperationException} is thrown otherwise.
    *
    * @param newSize the new size
    */
   default void resize(long newSize) {
      throw new UnsupportedOperationException();
   }

   /**
    * Returns the capacity of the underlying container. This is only supported if the container is bounded. An {@link UnsupportedOperationException} is thrown
    * otherwise.
    *
    * @return the capacity of a bounded container
    */
   default long capacity() {
      throw new UnsupportedOperationException();
   }

   /**
    * Returns how large the eviction size is currently. This is only supported if the container is bounded. An
    * {@link UnsupportedOperationException} is thrown otherwise. This value will always be lower than the value returned
    * from {@link DataContainer#capacity()}
    * @return how large the counted eviction is
    */
   default long evictionSize() {
      throw new UnsupportedOperationException();
   }
}
