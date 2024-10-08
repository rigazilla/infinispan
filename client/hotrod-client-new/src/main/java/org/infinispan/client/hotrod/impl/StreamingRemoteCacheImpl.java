package org.infinispan.client.hotrod.impl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.infinispan.client.hotrod.DataFormat;
import org.infinispan.client.hotrod.StreamingRemoteCache;
import org.infinispan.client.hotrod.VersionedMetadata;

/**
 * Implementation of {@link StreamingRemoteCache}
 *
 * @author Tristan Tarrant
 * @since 9.0
 */

public class StreamingRemoteCacheImpl<K> implements StreamingRemoteCache<K> {
   private final InternalRemoteCache<K, ?> cache;

   public StreamingRemoteCacheImpl(InternalRemoteCache<K, ?> cache) {
      this.cache = cache;
   }

   private K keyAsObjectIfNeeded(K key) {
      DataFormat df = cache.getDataFormat();
      return df.isObjectStorage()
            ? key
            : null;
   }

   @Override
   public <T extends InputStream & VersionedMetadata> T get(K key) {
      // TODO: need to implement new commands that are non blocking
      throw new UnsupportedOperationException();
//      GetStreamOperation op = cache.getOperationsFactory().newGetStreamOperation(keyAsObjectIfNeeded(key), cache.keyToBytes(key), 0);
//      return (T) await(op.execute());
   }

   @Override
   public OutputStream put(K key) {
      return put(key, -1, TimeUnit.SECONDS, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream put(K key, long lifespan, TimeUnit unit) {
      return put(key, lifespan, unit, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream put(K key, long lifespan, TimeUnit lifespanUnit, long maxIdle, TimeUnit maxIdleUnit) {
      // TODO: need to implement new commands that are non blocking
      throw new UnsupportedOperationException();
//      PutStreamOperation op = cache.getOperationsFactory().newPutStreamOperation(keyAsObjectIfNeeded(key), cache.keyToBytes(key), lifespan, lifespanUnit, maxIdle, maxIdleUnit);
//      return await(op.execute());
   }

   @Override
   public OutputStream putIfAbsent(K key) {
      return putIfAbsent(key, -1, TimeUnit.SECONDS, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream putIfAbsent(K key, long lifespan, TimeUnit unit) {
      return putIfAbsent(key, lifespan, unit, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream putIfAbsent(K key, long lifespan, TimeUnit lifespanUnit, long maxIdle, TimeUnit maxIdleUnit) {
      // TODO: need to implement new commands that are non blocking
      throw new UnsupportedOperationException();
//      PutStreamOperation op = cache.getOperationsFactory().newPutIfAbsentStreamOperation(keyAsObjectIfNeeded(key), cache.keyToBytes(key), lifespan, lifespanUnit, maxIdle, maxIdleUnit);
//      return await(op.execute());
   }

   @Override
   public OutputStream replaceWithVersion(K key, long version) {
      return replaceWithVersion(key, version, -1, TimeUnit.SECONDS, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream replaceWithVersion(K key, long version, long lifespan, TimeUnit unit) {
      return replaceWithVersion(key, version, lifespan, unit, -1, TimeUnit.SECONDS);
   }

   @Override
   public OutputStream replaceWithVersion(K key, long version, long lifespan, TimeUnit lifespanUnit, long maxIdle, TimeUnit maxIdleUnit) {
      // TODO: need to implement new commands that are non blocking
      throw new UnsupportedOperationException();
//      PutStreamOperation op = cache.getOperationsFactory().newPutStreamOperation(keyAsObjectIfNeeded(key), cache.keyToBytes(key), version, lifespan, lifespanUnit, maxIdle, maxIdleUnit);
//      return await(op.execute());
   }
}
