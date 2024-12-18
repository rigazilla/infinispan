package org.infinispan.client.hotrod.query;

import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.registerSCI;
import static org.infinispan.configuration.cache.IndexStorage.LOCAL_HEAP;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

import java.util.List;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.client.hotrod.query.testdomain.protobuf.marshallers.TestDomainSCI;
import org.infinispan.client.hotrod.test.HotRodClientTestingUtil;
import org.infinispan.client.hotrod.test.SingleHotRodServerTest;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoSchema;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests for remote queries over HotRod using protostream annotations on a local cache using indexing in RAM. Indexing
 * is not enabled for the searched entity. Non-indexed querying should still work.
 *
 * @author anistor@redhat.com
 * @since 9.4
 */
@Test(groups = "functional", testName = "client.hotrod.query.RemoteQueryProtostreamAnnotationsDisableIndexingTest")
public class RemoteQueryProtostreamAnnotationsDisableIndexingTest extends SingleHotRodServerTest {

   public static class Memo {

      @ProtoField(number = 10, defaultValue = "0")
      public int id;

      @ProtoField(20)
      public String text;

      @ProtoField(30)
      public Author author;

      public Memo(int id, String text) {
         this.id = id;
         this.text = text;
      }

      public Memo() {
      }

      @Override
      public String toString() {
         return "Memo{id=" + id + ", text='" + text + '\'' + ", author=" + author + '}';
      }
   }

   public static class Author {

      @ProtoField(number = 1, defaultValue = "0")
      public int id;

      @ProtoField(2)
      public String name;

      public Author(int id, String name) {
         this.id = id;
         this.name = name;
      }

      public Author() {
      }

      @Override
      public String toString() {
         return "Author{id=" + id + ", name='" + name + "'}";
      }
   }

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      org.infinispan.configuration.cache.ConfigurationBuilder builder = new org.infinispan.configuration.cache.ConfigurationBuilder();
      builder.indexing().enable()
            .storage(LOCAL_HEAP)
            .addIndexedEntity("sample_bank_account.User");

      return TestCacheManagerFactory.createServerModeCacheManager(TestDomainSCI.INSTANCE, builder);
   }

   @Override
   protected RemoteCacheManager getRemoteCacheManager() {
      org.infinispan.client.hotrod.configuration.ConfigurationBuilder clientBuilder = HotRodClientTestingUtil.newRemoteConfigurationBuilder();
      clientBuilder.addServer().host("127.0.0.1").port(hotrodServer.getPort());
      return new RemoteCacheManager(clientBuilder.build());
   }

   @BeforeClass
   protected void registerProtobufSchema() {
      registerSCI(remoteCacheManager, RemoteQueryProtostreamAnnotationsDisableIndexingTestSCI.INSTANCE);
   }

   public void testAttributeQuery() {
      RemoteCache<Integer, Memo> remoteCache = remoteCacheManager.getCache();

      remoteCache.put(1, createMemo1());
      remoteCache.put(2, createMemo2());

      // get memo1 back from remote cache and check its attributes
      Memo fromCache = remoteCache.get(1);
      assertMemo1(fromCache);

      // get memo1 back from remote cache via query and check its attributes
      QueryFactory qf = Search.getQueryFactory(remoteCache);
      Query<Memo> query = qf.from(Memo.class)
            .having("text").like("%ipsum%")
            .build();
      List<Memo> list = query.execute().list();
      assertNotNull(list);
      assertEquals(1, list.size());
      assertEquals(Memo.class, list.get(0).getClass());
      assertMemo1(list.get(0));

      // get memo2 back from remote cache via query and check its attributes
      query = qf.from(Memo.class)
            .having("author.name").eq("Adrian")
            .build();
      list = query.list();
      assertNotNull(list);
      assertEquals(1, list.size());
      assertEquals(Memo.class, list.get(0).getClass());
      assertMemo2(list.get(0));
   }

   private Memo createMemo1() {
      Memo memo = new Memo(1, "Lorem ipsum");
      memo.author = new Author(1, "Tom");
      return memo;
   }

   private Memo createMemo2() {
      Memo memo = new Memo(2, "Sed ut perspiciatis unde omnis iste natus error");
      memo.author = new Author(2, "Adrian");
      return memo;
   }

   private void assertMemo1(Memo memo) {
      assertNotNull(memo);
      assertEquals(1, memo.id);
      assertEquals("Lorem ipsum", memo.text);
      assertEquals(1, memo.author.id);
   }

   private void assertMemo2(Memo memo) {
      assertNotNull(memo);
      assertEquals(2, memo.id);
      assertEquals("Sed ut perspiciatis unde omnis iste natus error", memo.text);
      assertEquals(2, memo.author.id);
   }

   @ProtoSchema(
         includeClasses = {Author.class, Memo.class},
         schemaFileName = "test.client.RemoteQueryProtostreamAnnotationsDisableIndexingTest.proto",
         schemaFilePath = "proto/generated",
         service = false
   )
   public interface RemoteQueryProtostreamAnnotationsDisableIndexingTestSCI extends GeneratedSchema {
      GeneratedSchema INSTANCE = new RemoteQueryProtostreamAnnotationsDisableIndexingTestSCIImpl();
   }
}
