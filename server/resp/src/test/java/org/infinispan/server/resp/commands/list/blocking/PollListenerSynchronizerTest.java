package org.infinispan.server.resp.commands.list.blocking;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Test(groups = "unit", testName = "server.resp.commands.list.blocking.PollListenerSynchronizerTest")
public class PollListenerSynchronizerTest {

   @DataProvider
   private Object[] getSynchronizer() {
      var synchronizer = new BLPOP.PollListenerSynchronizer();
      synchronizer.operation = () -> {
         synchronizer.complete(Arrays.asList(synchronizer.getSavedKey(), "test-val".getBytes()));
      };
      return new Object[] { synchronizer };
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventThenNullPollTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(null);
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onNullPollThenEventTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(null);
      synchronizer.onEvent("key1".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onPollTest(BLPOP.PollListenerSynchronizer synchronizer) throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(Arrays.asList("key1".getBytes(), "val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onPollAndEventTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      synchronizer.onEvent("key1".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventAndPollTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onMultipleEventsAndNullPollTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onEvent("key2".getBytes());
      synchronizer.onPollComplete(null);
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onMultipleEventsAndPollTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onEvent("key2".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventAndPollAndEventTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
      synchronizer.onEvent("key2".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void onEventNullPollThenEventTest(BLPOP.PollListenerSynchronizer synchronizer)
         throws InterruptedException, ExecutionException {
      synchronizer.onEvent("key1".getBytes());
      synchronizer.onPollComplete(null);
      synchronizer.onEvent("key2".getBytes());
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("key1".getBytes(), "test-val".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void raceEventAndPoll(BLPOP.PollListenerSynchronizer synchronizer)
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
   public void raceEventsAndPoll(BLPOP.PollListenerSynchronizer synchronizer)
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
      assertThat(synchronizer.getResultFuture().get())
            .containsExactlyElementsOf(Arrays.asList("poll-key1".getBytes(), "poll-val1".getBytes()));
   }

   @Test(dataProvider = "getSynchronizer")
   public void concurrentEventAndPollGotNullResult(BLPOP.PollListenerSynchronizer synchronizer)
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
   public void concurrentNullPollAndEventReceived(BLPOP.PollListenerSynchronizer synchronizer)
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
   public void concurrentPollAndEventReceived(BLPOP.PollListenerSynchronizer synchronizer)
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
