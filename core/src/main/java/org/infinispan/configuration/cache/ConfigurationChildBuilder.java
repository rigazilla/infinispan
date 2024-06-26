package org.infinispan.configuration.cache;

import org.infinispan.configuration.global.GlobalConfiguration;

public interface ConfigurationChildBuilder {

   ConfigurationBuilder aliases(String... aliases);

   ConfigurationChildBuilder simpleCache(boolean simpleCache);

   boolean simpleCache();

   ClusteringConfigurationBuilder clustering();

   EncodingConfigurationBuilder encoding();

   ExpirationConfigurationBuilder expiration();

   QueryConfigurationBuilder query();

   IndexingConfigurationBuilder indexing();

   TracingConfigurationBuilder tracing();

   InvocationBatchingConfigurationBuilder invocationBatching();

   StatisticsConfigurationBuilder statistics();

   PersistenceConfigurationBuilder persistence();

   LockingConfigurationBuilder locking();

   SecurityConfigurationBuilder security();

   TransactionConfigurationBuilder transaction();

   UnsafeConfigurationBuilder unsafe();

   SitesConfigurationBuilder sites();

   MemoryConfigurationBuilder memory();

   default ConfigurationChildBuilder template(boolean template) {
      return this;
   }

   default void validate(GlobalConfiguration globalConfig) {}

   Configuration build();
}
