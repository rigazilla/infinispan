package org.infinispan.test.concurrent;

import static org.infinispan.commons.test.Exceptions.unchecked;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.TestingUtil;

/**
 * Replaces a global component with a dynamic proxy that can interact with a {@link StateSequencer} when a method that
 * matches a {@link InvocationMatcher} is called.
 *
 * @author Dan Berindei
 * @since 7.0
 */
public class GlobalComponentSequencerAction<T> {
   protected final StateSequencer stateSequencer;
   protected final EmbeddedCacheManager cacheManager;
   protected final Class<T> componentClass;
   protected final InvocationMatcher matcher;
   protected ProxyInvocationHandler ourHandler;
   protected T originalComponent;

   GlobalComponentSequencerAction(StateSequencer stateSequencer, EmbeddedCacheManager cacheManager, Class<T> componentClass, InvocationMatcher matcher) {
      this.matcher = matcher;
      this.componentClass = componentClass;
      this.stateSequencer = stateSequencer;
      this.cacheManager = cacheManager;
   }

   /**
    * Set up a list of sequencer states before interceptor {@code interceptorClass} is called.
        * Each invocation accepted by {@code matcher} will enter/exit the next state from the list, and does nothing after the list is exhausted.
    */
   public GlobalComponentSequencerAction<T> before(String state1, String... additionalStates) {
      replaceComponent();
      ourHandler.beforeStates(StateSequencerUtil.concat(state1, additionalStates));
      return this;
   }

   protected void replaceComponent() {
      if (ourHandler == null) {
         originalComponent = TestingUtil.extractGlobalComponent(cacheManager, componentClass);
         if (originalComponent == null) {
            throw new IllegalStateException("Attempting to wrap a non-existing global component: " + componentClass);
         }
         ourHandler = new ProxyInvocationHandler(originalComponent, stateSequencer, matcher);
         T componentProxy = createComponentProxy(componentClass, ourHandler);
         TestingUtil.replaceComponent(cacheManager, componentClass, componentProxy, true);
      }
   }

   protected <T> T createComponentProxy(Class<T> componentClass, InvocationHandler handler) {
      return (T) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[]{componentClass},
            handler);
   }

   /**
    * Set up a list of sequencer states after interceptor {@code interceptorClass} has returned.
        * Each invocation accepted by {@code matcher} will enter/exit the next state from the list, and does nothing after the list is exhausted.
    */
   public GlobalComponentSequencerAction<T> after(String state1, String... additionalStates) {
      replaceComponent();
      ourHandler.afterStates(StateSequencerUtil.concat(state1, additionalStates));
      return this;
   }

   /**
    * Set up a list of sequencer states after interceptor {@code interceptorClass} has returned.
        * Each invocation accepted by {@code matcher} will enter/exit the next state from the list, and does nothing after the list is exhausted.
    */
   public GlobalComponentSequencerAction<T> afterAsync(String state1, String... additionalStates) {
      replaceComponent();
      ourHandler.afterStatesAsync(StateSequencerUtil.concat(state1, additionalStates));
      return this;
   }

   public T getOriginalComponent() {
      return originalComponent;
   }

   public static class ProxyInvocationHandler implements InvocationHandler {
      private final Object wrappedInstance;
      private final StateSequencer stateSequencer;
      private final InvocationMatcher matcher;
      private boolean async;
      private volatile List<String> statesBefore;
      private volatile List<String> statesAfter;

      public ProxyInvocationHandler(Object wrappedInstance, StateSequencer stateSequencer, InvocationMatcher matcher) {
         this.wrappedInstance = wrappedInstance;
         this.stateSequencer = stateSequencer;
         this.matcher = matcher;
      }

      public void beforeStates(List<String> states) {
         this.statesBefore = StateSequencerUtil.listCopy(states);
      }

      public void afterStates(List<String> states) {
         this.statesAfter = StateSequencerUtil.listCopy(states);
      }

      public void afterStatesAsync(List<String> states) {
         this.async = true;
         this.statesAfter = StateSequencerUtil.listCopy(states);
      }

      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
         boolean matches = matcher.accept(wrappedInstance, method.getName(), args);
         StateSequencerUtil.advanceMultiple(stateSequencer, matches, statesBefore);
         if (async) {
            CompletionStage<?> stage = (CompletionStage<?>) method.invoke(wrappedInstance, args);
            return stage.whenComplete((o, throwable) -> unchecked(() -> {
               StateSequencerUtil.advanceMultiple(stateSequencer, matches, statesAfter);
            }));
         }
         else {
            try {
               return method.invoke(wrappedInstance, args);
            } finally {
               StateSequencerUtil.advanceMultiple(stateSequencer, matches, statesAfter);
            }
         }
      }

      public Object getWrappedInstance() {
         return wrappedInstance;
      }
   }
}
