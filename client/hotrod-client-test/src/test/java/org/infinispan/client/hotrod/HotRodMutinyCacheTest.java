package org.infinispan.client.hotrod;

import static org.infinispan.client.hotrod.AwaitAssertions.assertAwaitEquals;
import static org.infinispan.client.hotrod.AwaitAssertions.await;
import static org.infinispan.client.hotrod.CacheEntryAssertions.assertEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.infinispan.api.common.CacheEntry;
import org.infinispan.api.common.CacheEntryMetadata;
import org.infinispan.api.common.CacheEntryVersion;
import org.infinispan.api.common.CacheWriteOptions;
import org.infinispan.client.hotrod.impl.MetadataValueImpl;
import org.infinispan.client.hotrod.impl.cache.CacheEntryImpl;
import org.infinispan.client.hotrod.impl.cache.CacheEntryMetadataImpl;
import org.infinispan.client.hotrod.impl.cache.CacheEntryVersionImpl;
import org.infinispan.client.hotrod.test.AbstractMutinyCacheSingleServerTest;
import org.infinispan.client.hotrod.test.KeyValueGenerator;
import org.infinispan.client.hotrod.util.FlowUtils;
import org.infinispan.client.hotrod.util.MapKVHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.smallrye.mutiny.Multi;

/**
 * @since 14.0
 **/
public class HotRodMutinyCacheTest<K, V> extends AbstractMutinyCacheSingleServerTest<K, V> {

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testPut[{0}]")
   public void testPut(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V v1 = kvGenerator.generateValue(cacheName, 0);

      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      assertEntry(key, null, kvGenerator, await(cache.put(key, v1, options)));
      kvGenerator.assertValueEquals(v1, await(cache.get(key)));
      assertEntry(key, v1, kvGenerator, await(cache.getEntry(key)), options);

      CacheWriteOptions optionsV1 = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(20))
            .lifespanAndMaxIdle(Duration.ofSeconds(30), Duration.ofSeconds(25))
            .build();
      final V v2 = kvGenerator.generateValue(cacheName, 1);
      assertEntry(key, v1, kvGenerator, await(cache.put(key, v2, optionsV1)), options);
      assertEntry(key, v2, kvGenerator, await(cache.getEntry(key)), optionsV1);
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testPutIfAbsent[{0}]")
   public void testPutIfAbsent(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V v1 = kvGenerator.generateValue(cacheName, 0);

      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      assertAwaitEquals(null, cache.putIfAbsent(key, v1, options));
      assertEntry(key, v1, kvGenerator, await(cache.getEntry(key)), options);

      final V other = kvGenerator.generateValue(cacheName, 1);
      CacheEntry<K, V> previousEntry = await(cache.putIfAbsent(key, other));
      kvGenerator.assertKeyEquals(key, previousEntry.key());

      kvGenerator.assertValueEquals(v1, previousEntry.value());
      kvGenerator.assertValueEquals(v1, await(cache.get(key)));
      assertEntry(key, v1, kvGenerator, await(cache.getEntry(key)), options);
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testSetIfAbsent[{0}]")
   public void testSetIfAbsent(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V value = kvGenerator.generateValue(cacheName, 0);

      CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      assertAwaitEquals(true, cache.setIfAbsent(key, value, options));
      assertEntry(key, value, kvGenerator, await(cache.getEntry(key)), options);

      final V other = kvGenerator.generateValue(cacheName, 1);
      assertAwaitEquals(false, cache.setIfAbsent(key, other));

      final V actual = await(cache.get(key));
      kvGenerator.assertValueEquals(value, actual);

      assertEntry(key, value, kvGenerator, await(cache.getEntry(key)), options);
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testSet[{0}]")
   public void testSet(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V v1 = kvGenerator.generateValue(cacheName, 0);

      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      await(cache.set(key, v1, options));
      assertEntry(key, v1, kvGenerator, await(cache.getEntry(key)), options);

      final CacheWriteOptions optionsV2 = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(20))
            .lifespanAndMaxIdle(Duration.ofSeconds(30), Duration.ofSeconds(25))
            .build();
      final V v2 = kvGenerator.generateValue(cacheName, 1);
      await(cache.set(key, v2, optionsV2));
      V actual = await(cache.get(key));
      kvGenerator.assertValueEquals(v2, actual);

      assertEntry(key, v2, kvGenerator, await(cache.getEntry(key)), optionsV2);
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testGetAndRemove[{0}]")
   public void testGetAndRemove(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V value = kvGenerator.generateValue(cacheName, 0);
      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      assertEntry(key, null, kvGenerator, await(cache.put(key, value, options)));

      assertEntry(key, value, kvGenerator, await(cache.getEntry(key)), options);
      assertEntry(key, value, kvGenerator, await(cache.getAndRemove(key)), options);

      assertAwaitEquals(null, cache.get(key));
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testPutAllAndClear[{0}]")
   public void testPutAllAndClear(KeyValueGenerator<K, V> kvGenerator) {
      Map<K, V> entries = new HashMap<>();
      for (int i = 0; i < 10; i++) {
         final K key = kvGenerator.generateKey(cacheName, i);
         final V value = kvGenerator.generateValue(cacheName, i);
         entries.put(key, value);
      }

      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      await(cache.putAll(entries, options));

      for (Map.Entry<K, V> entry : entries.entrySet()) {
         assertEntry(entry.getKey(), entry.getValue(), kvGenerator, await(cache.getEntry(entry.getKey())), options);
      }

      await(cache.clear());
      for (Map.Entry<K, V> entry : entries.entrySet()) {
         assertAwaitEquals(null, cache.get(entry.getKey()));
      }
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testPutAllGetAll[{0}]")
   public void testPutAllGetAll(KeyValueGenerator<K, V> kvGenerator) {
      Map<K, V> entries = new HashMap<>();
      for (int i = 0; i < 10; i++) {
         final K key = kvGenerator.generateKey(cacheName, i);
         final V value = kvGenerator.generateValue(cacheName, i);
         entries.put(key, value);
      }

      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();
      await(cache.putAll(entries, options));

      Multi<CacheEntry<K, V>> multi = cache.getAndRemoveAll(Multi.createFrom().iterable(entries.keySet()));
      Map<K, CacheEntry<K, V>> retrieved = FlowUtils.blockingCollect(multi)
            .stream().collect(Collectors.toMap(CacheEntry::key, e -> e));

      assertEquals(entries.size(), retrieved.size());
      MapKVHelper<K, V> helper = new MapKVHelper<>(entries, kvGenerator);
      for (Map.Entry<K, CacheEntry<K, V>> entry : retrieved.entrySet()) {
         V expected = helper.get(entry.getKey());
         assertNotNull(expected);
         // TODO: once handling metadata on remote cache verify that too
         assertEntry(entry.getKey(), expected, kvGenerator, entry.getValue());
      }
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testPutAllGetAndRemoveAll[{0}]")
   public void testPutAllGetAndRemoveAll(KeyValueGenerator<K, V> kvGenerator) {
      Map<K, V> entries = new HashMap<>();
      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();

      for (int i = 0; i < 10; i++) {
         final K key = kvGenerator.generateKey(cacheName, i);
         final V value = kvGenerator.generateValue(cacheName, i);
         entries.put(key, value);
      }

      MetadataValue<V> mv = new MetadataValueImpl<>(-1, -1, -1, -1, 0, null);
      CacheEntryMetadata metadata = new CacheEntryMetadataImpl<>(mv);
      List<CacheEntry<K, V>> allEntries = entries.entrySet().stream()
            .map(e -> new CacheEntryImpl<>(e.getKey(), e.getValue(), metadata))
            .collect(Collectors.toList());
      await(cache.putAll(Multi.createFrom().iterable(allEntries), options));

      Multi<CacheEntry<K, V>> multi = cache.getAndRemoveAll(Multi.createFrom().iterable(entries.keySet()));
      Map<K, CacheEntry<K, V>> retrieved = FlowUtils.blockingCollect(multi)
            .stream().collect(Collectors.toMap(CacheEntry::key, e -> e));

      assertEquals(entries.size(), retrieved.size());
      MapKVHelper<K, V> helper = new MapKVHelper<>(entries, kvGenerator);
      for (Map.Entry<K, CacheEntry<K, V>> entry : retrieved.entrySet()) {
         V expected = helper.get(entry.getKey());
         assertNotNull(expected);
         assertEntry(entry.getKey(), expected, kvGenerator, entry.getValue(), options);
      }

      for (Map.Entry<K, V> entry : entries.entrySet()) {
         assertAwaitEquals(null, cache.get(entry.getKey()));
      }
   }

   @MethodSource("parameterized")
   @ParameterizedTest(name = "testReplace[{0}]")
   public void testReplace(KeyValueGenerator<K, V> kvGenerator) {
      final K key = kvGenerator.generateKey(cacheName, 0);
      final V initialValue = kvGenerator.generateValue(cacheName, 0);
      final CacheWriteOptions options = CacheWriteOptions.writeOptions()
            .timeout(Duration.ofSeconds(15))
            .lifespanAndMaxIdle(Duration.ofSeconds(25), Duration.ofSeconds(20))
            .build();

      // Returns false for a nonexistent entry.
      final CacheEntryVersion cve0 = new CacheEntryVersionImpl(0);
      assertAwaitEquals(false, cache.replace(key, initialValue, cve0));
      assertEntry(key, null, kvGenerator, await(cache.put(key, initialValue, options)));
      var entry = await(cache.getEntry(key));
      assertEntry(key, initialValue, kvGenerator, entry);

      final V replaceValue = kvGenerator.generateValue(cacheName, 1);
      assertAwaitEquals(true, cache.replace(key, replaceValue, entry.metadata().version()));

      // Returns false for the wrong version.
      final V anyValue = kvGenerator.generateValue(cacheName, 1);
      assertAwaitEquals(false, cache.replace(key, anyValue, cve0));
   }

   public final Stream<Arguments> parameterized() {
      return Stream.of(
            Arguments.of(KeyValueGenerator.BYTE_ARRAY_GENERATOR),
            Arguments.of(KeyValueGenerator.STRING_GENERATOR),
            Arguments.of(KeyValueGenerator.GENERIC_ARRAY_GENERATOR)
      );
   }
}
