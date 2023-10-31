package org.infinispan.server.resp.commands.list.blocking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test(groups = "unit", testName = "server.resp.commands.list.blocking.PollListenerSynchronizerTest")
public class PollListenerSynchronizerTest {

   @DataProvider
   private Object[] getSynchronizer() {
      TestableListenerSynchronizer synchronizer = new TestableListenerSynchronizer();
      synchronizer.setPerform(() -> {
         synchronizer.complete(Arrays.asList(synchronizer.getSavedKey(), "test-val".getBytes()));
      });

      return new Object[] { synchronizer };
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventThenNullPollTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(null);
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onNullPollThenEventTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(null);
      synchronizer.onEvent("key1".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onPollTest(TestableListenerSynchronizer synchronizer) throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(Arrays.asList("key1".getBytes(), "val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onPollAndEventTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      synchronizer.onEvent("key1".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventAndPollTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onMultipleEventsAndNullPollTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onEvent("key2".getBytes());
      synchronizer.onPollComplete(null);
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onMultipleEventsAndPollTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onEvent("key2".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventAndPollAndEventTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      synchronizer.onEvent("key2".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventNullPollThenEventTest(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(null);
      synchronizer.onEvent("key2".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void raceEventAndPoll(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      var l = new CountDownLatch(1);
      new Thread(() -> {
         try {
            l.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onEvent("key1".getBytes());
      }).start();
      new Thread(() -> {
         try {
            l.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      }).start();
      l.countDown();
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));

   }

   @Test(dataProvider = "getSynchronizer")
   public void raceEventsAndPoll(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      var l = new CountDownLatch(1);
      new Thread(() -> {
         try {
            l.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onEvent("key1".getBytes());
      }).start();

      new Thread(() -> {
         try {
            l.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onEvent("key2".getBytes());
      }).start();

      new Thread(() -> {
         try {
            l.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      }).start();
      l.countDown();
      // Synchronizer should guarantee that poll value is returned and not the event one
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void concurrentEventAndPollGotNullResult(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      new Thread(() -> {
         try {
            synchronizer.pollGotNullResultLatch.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onEvent("key1".getBytes());
      }).start();
      new Thread(() -> {
         synchronizer.onPollComplete(null);
      }).start();
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));

   }

   @Test(dataProvider = "getSynchronizer")
   public void concurrentNullPollAndEventReceived(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      new Thread(() -> {
         try {
            synchronizer.eventReceivedLatch.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onPollComplete(null);
      }).start();
      new Thread(() -> {
         synchronizer.onEvent("key1".getBytes());
      }).start();
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));

   }

   @Test(dataProvider = "getSynchronizer")
   public void concurrentPollAndEventReceived(TestableListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      new Thread(() -> {
         try {
            synchronizer.eventReceivedLatch.await();
         } catch (InterruptedException e) {
            // TODO Auto-generated catch block
         }
         synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      }).start();
      new Thread(() -> {
         synchronizer.onEvent("key1".getBytes());
      }).start();
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }
}

class TestableListenerSynchronizer extends BLPOP.PollListenerSynchronizer {
      public CountDownLatch eventReceivedLatch = new CountDownLatch(1);
      public CountDownLatch pollGotNullResultLatch = new CountDownLatch(1);

   public TestableListenerSynchronizer() {
      super(null);
   }
   private Runnable perform;

   public void setPerform(Runnable perform) {
      this.perform = perform;
   }
   @Override
   protected void performPollFirst() {
      perform.run();
   }
   @Override
   public void onEvent(byte[] key) {
      eventReceivedLatch.countDown();
      super.onEvent(key);
   }
   @Override
   protected void pollCompleteWithNull() {
      pollGotNullResultLatch.countDown();
      super.pollCompleteWithNull();
   }

}
