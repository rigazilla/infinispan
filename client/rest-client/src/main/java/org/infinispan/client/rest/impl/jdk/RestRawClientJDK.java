package org.infinispan.client.rest.impl.jdk;

import static org.infinispan.client.rest.RestClient.LOG;
import static org.infinispan.client.rest.RestHeaders.ACCEPT;
import static org.infinispan.client.rest.RestHeaders.ACCEPT_ENCODING;
import static org.infinispan.client.rest.RestHeaders.CONTENT_TYPE;

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import org.infinispan.client.rest.RestEntity;
import org.infinispan.client.rest.RestEventListener;
import org.infinispan.client.rest.RestRawClient;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.client.rest.configuration.AuthenticationConfiguration;
import org.infinispan.client.rest.configuration.RestClientConfiguration;
import org.infinispan.client.rest.configuration.ServerConfiguration;
import org.infinispan.client.rest.configuration.SslConfiguration;
import org.infinispan.client.rest.impl.jdk.auth.AutoDetectAuthenticator;
import org.infinispan.client.rest.impl.jdk.auth.BasicAuthenticator;
import org.infinispan.client.rest.impl.jdk.auth.BearerAuthenticator;
import org.infinispan.client.rest.impl.jdk.auth.DigestAuthenticator;
import org.infinispan.client.rest.impl.jdk.auth.HttpAuthenticator;
import org.infinispan.client.rest.impl.jdk.auth.NegotiateAuthenticator;
import org.infinispan.client.rest.impl.jdk.sse.EventSubscriber;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.util.SslContextFactory;

public class RestRawClientJDK implements RestRawClient, AutoCloseable {
   private static final AtomicLong CLIENT_IDS = new AtomicLong();
   private final RestClientConfiguration configuration;
   private final HttpAuthenticator authenticator;
   private final HttpClient httpClient;
   private final String baseURL;
   private final boolean managedExecutorService;
   private final ExecutorService executorService;
   private final AtomicLong activeRequests = new AtomicLong(0);
   private final AtomicLong totalRequests = new AtomicLong(0);
   private final AtomicLong activeListeners = new AtomicLong(0);

   RestRawClientJDK(RestClientConfiguration configuration) {
      this.configuration = configuration;
      HttpClient.Builder builder = HttpClient.newBuilder();
      ExecutorService executorService = configuration.executorService();
      if (executorService == null) {
         executorService = new ThreadPoolExecutor(0, 10,
               60L, TimeUnit.SECONDS,
               new LinkedBlockingQueue<>(),
               new RestClientThreadFactory(CLIENT_IDS.incrementAndGet()));
         managedExecutorService = true;
      } else {
         managedExecutorService = false;
      }
      this.executorService = executorService;
      builder
            .connectTimeout(Duration.ofMillis(configuration.connectionTimeout()))
            .followRedirects(configuration.followRedirects() ? HttpClient.Redirect.ALWAYS : HttpClient.Redirect.NEVER);
      builder.executor(executorService);
      SslConfiguration ssl = configuration.security().ssl();
      if (ssl.enabled()) {
         SSLContext sslContext = ssl.sslContext();
         if (sslContext == null) {
            SslContextFactory sslContextFactory = new SslContextFactory()
                  .keyStoreFileName(ssl.keyStoreFileName())
                  .keyStorePassword(ssl.keyStorePassword())
                  .keyStoreType(ssl.keyStoreType())
                  .trustStoreFileName(ssl.trustStoreFileName())
                  .trustStorePassword(ssl.trustStorePassword())
                  .trustStoreType(ssl.trustStoreType())
                  .classLoader(Thread.currentThread().getContextClassLoader());
            sslContext = sslContextFactory.build().sslContext();
         }
         builder.sslContext(sslContext);
      }
      switch (configuration.protocol()) {
         case HTTP_11:
            builder.version(HttpClient.Version.HTTP_1_1);
            break;
         case HTTP_20:
            builder.version(HttpClient.Version.HTTP_2);
            break;
      }
      httpClient = builder.build();
      AuthenticationConfiguration authentication = configuration.security().authentication();
      if (authentication.enabled()) {
         switch (authentication.mechanism()) {
            case "AUTO":
               authenticator = new AutoDetectAuthenticator(httpClient, authentication);
               break;
            case "SPNEGO":
               authenticator = new NegotiateAuthenticator(httpClient, authentication);
               break;
            case "DIGEST":
               authenticator = new DigestAuthenticator(httpClient, authentication);
               break;
            case "BASIC":
               authenticator = new BasicAuthenticator(httpClient, authentication);
               break;
            case "BEARER_TOKEN":
               authenticator = new BearerAuthenticator(httpClient, authentication);
               break;
            default:
               throw new IllegalArgumentException("Cannot handle " + authentication.mechanism());
         }
      } else {
         authenticator = null;
      }
      ServerConfiguration server = configuration.servers().get(0);
      baseURL = String.format("%s://%s:%d", ssl.enabled() ? "https" : "http", server.host(), server.port());
      if (configuration.pingOnCreate()) {
         try {
            head("/").toCompletableFuture().get();
         } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
         }
      }
   }

   @Override
   public CompletionStage<RestResponse> post(String path, Map<String, String> headers, RestEntity entity) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.POST(entity.bodyPublisher());
      if (entity.contentType() != null) {
         builder.header(CONTENT_TYPE, entity.contentType().toString());
      }
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public CompletionStage<RestResponse> put(String path, Map<String, String> headers, RestEntity entity) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.PUT(entity.bodyPublisher());
      if (entity.contentType() != null) {
         builder.header(CONTENT_TYPE, entity.contentType().toString());
      }
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public CompletionStage<RestResponse> get(String path, Map<String, String> headers) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.GET().uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public CompletionStage<RestResponse> get(String path, Map<String, String> headers, Supplier<HttpResponse.BodyHandler<?>> supplier) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.GET().uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      return execute(builder, supplier);
   }

   @Override
   public CompletionStage<RestResponse> delete(String path, Map<String, String> headers) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.DELETE();
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public CompletionStage<RestResponse> options(String path, Map<String, String> headers) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public CompletionStage<RestResponse> head(String path, Map<String, String> headers) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
      return execute(builder, bodyHandlerSupplier(headers));
   }

   @Override
   public Closeable listen(String path, Map<String, String> headers, RestEventListener listener) {
      HttpRequest.Builder builder = HttpRequest.newBuilder();
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      configuration.headers().forEach(builder::header);
      EventSubscriber subscriber = new EventSubscriber(listener);

      long listenerId = activeListeners.incrementAndGet();
      LOG.warnf("Starting SSE listener #%d for path: %s", listenerId, path);

      execute(builder, subscriber::bodyHandler).handle((r, t) -> {
         if (t != null) {
            listener.onError(t, r);
         } else {
            int status = r.status();
            if (status >= 300) {
               listener.onError(null, r);
            }
         }
         long remaining = activeListeners.decrementAndGet();
         LOG.warnf("SSE listener #%d completed (active listeners: %d)", listenerId, remaining);
         return null;
      });
      return subscriber;
   }

   private Supplier<HttpResponse.BodyHandler<?>> bodyHandlerSupplier(Map<String, String> headers) {
      String accept = headers.get(ACCEPT);
      String encoding = headers.get(ACCEPT_ENCODING);
      if (accept == null && encoding == null) {
         return HttpResponse.BodyHandlers::ofString;
      } else {
         if (encoding != null && !"identity".equals(encoding)) {
            return HttpResponse.BodyHandlers::ofByteArray;
         }
         MediaType mediaType = MediaType.parseList(accept).findFirst().get();
         return switch (mediaType.getTypeSubtype()) {
            case MediaType.APPLICATION_OCTET_STREAM_TYPE, MediaType.APPLICATION_PROTOSTREAM_TYPE,
                 MediaType.APPLICATION_SERIALIZED_OBJECT_TYPE -> HttpResponse.BodyHandlers::ofByteArray;
            case MediaType.APPLICATION_GZIP_TYPE -> HttpResponse.BodyHandlers::ofInputStream;
            default -> HttpResponse.BodyHandlers::ofString;
         };
      }
   }

   private <T> CompletionStage<RestResponse> execute(HttpRequest.Builder builder, Supplier<HttpResponse.BodyHandler<?>> handlerSupplier) {
      // Add configured headers
      configuration.headers().forEach(builder::header);
      HttpRequest request = builder.build();
      LOG.tracef("Request %s", request);
      if (authenticator != null && authenticator.supportsPreauthentication()) {
         authenticator.preauthenticate(builder);
      }

      // Track active requests
      long active = activeRequests.incrementAndGet();
      long total = totalRequests.incrementAndGet();
      LOG.tracef("Starting request #%d (active=%d): %s", total, active, request.uri());

      CompletionStage<RestResponse> stage = handle(httpClient.sendAsync(request, handlerSupplier.get()), handlerSupplier)
            .thenApply(RestResponseJDK::new);

      return stage.whenComplete((response, throwable) -> {
         long remaining = activeRequests.decrementAndGet();
         LOG.tracef("Completed request to %s (active=%d, status=%s)",
               request.uri(), remaining,
               response != null ? response.status() : "error");
      });
   }

   private <T> CompletionStage<HttpResponse<T>> handle(CompletionStage<HttpResponse<T>> response, Supplier<HttpResponse.BodyHandler<?>> handlerSupplier) {
      return response.thenCompose(r -> {
         if (r.statusCode() == 401 && authenticator != null) {
            CompletionStage<HttpResponse<T>> authenticate = authenticator.authenticate(r, handlerSupplier.get());
            if (authenticate == null) {
               // The authenticator has given up, return the failure as is
               return CompletableFuture.completedFuture(r);
            } else {
               return handle(authenticate, handlerSupplier);
            }
         } else {
            return CompletableFuture.completedFuture(r);
         }
      });
   }

   @Override
   public void close() throws Exception {
      if (Runtime.version().feature() >= 21) {
         // Log state before attempting to close
         LOG.warnf("Attempting to close HttpClient for %s. Active requests: %d, Total requests: %d, Active listeners: %d, " +
               "ExecutorService state before close: isShutdown=%s, isTerminated=%s, " +
               "Active threads: %d, Pool size: %d, Queue size: %d",
               baseURL, activeRequests.get(), totalRequests.get(), activeListeners.get(),
               executorService.isShutdown(), executorService.isTerminated(),
               executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getActiveCount() : -1,
               executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getPoolSize() : -1,
               executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getQueue().size() : -1);

         // Wrap HttpClient.close() with timeout to prevent indefinite hang
         // IMPORTANT: Create a separate thread instead of using executorService to avoid deadlock
         // (the executorService might have pending requests that close() needs to wait for)
         CompletableFuture<Void> closeFuture = new CompletableFuture<>();
         Thread closeThread = new Thread(() -> {
            try {
               long startTime = System.currentTimeMillis();
               LOG.warnf("Starting HttpClient.close() call");
               ((AutoCloseable) httpClient).close(); // close() was only introduced in JDK 21
               long duration = System.currentTimeMillis() - startTime;
               LOG.warnf("HttpClient.close() completed successfully in %d ms", duration);
               closeFuture.complete(null);
            } catch (Exception e) {
               LOG.errorf(e, "Exception during HttpClient.close()");
               closeFuture.completeExceptionally(e);
            }
         }, "HttpClient-close-" + baseURL);
         closeThread.setDaemon(true);
         closeThread.start();

         try {
            closeFuture.get(30, TimeUnit.SECONDS);
            LOG.warnf("HttpClient closed successfully within timeout");
         } catch (java.util.concurrent.TimeoutException e) {
            // Dump all threads to help diagnose what's blocking
            Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
               if (thread.getName().contains("REST") || thread.getName().contains("HttpClient") ||
                   thread.getName().contains("InnocuousThread") || thread.isDaemon()) {
                  StringBuilder sb = new StringBuilder();
                  sb.append("Thread: ").append(thread.getName())
                    .append(" (").append(thread.getState()).append(", daemon=").append(thread.isDaemon()).append(")\n");
                  for (StackTraceElement element : stackTrace) {
                     sb.append("  at ").append(element).append("\n");
                  }
                  LOG.warnf("Active thread during HttpClient.close() timeout:\n%s", sb);
               }
            });

            LOG.warnf("HttpClient.close() timed out after 30 seconds. " +
                  "Base URL: %s, Active requests: %d, Active listeners: %d, Total requests: %d, " +
                  "ExecutorService state: isShutdown=%s, isTerminated=%s, " +
                  "Active threads: %d, Pool size: %d, Queue size: %d, Completed tasks: %d",
                  baseURL, activeRequests.get(), activeListeners.get(), totalRequests.get(),
                  executorService.isShutdown(), executorService.isTerminated(),
                  executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getActiveCount() : -1,
                  executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getPoolSize() : -1,
                  executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getQueue().size() : -1,
                  executorService instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) executorService).getCompletedTaskCount() : -1);
            closeFuture.cancel(true);
            throw e;
         }
      }
      if (managedExecutorService) {
         LOG.warnf("Shutting down managed ExecutorService");
         executorService.shutdownNow();
         boolean terminated = executorService.awaitTermination(5, TimeUnit.SECONDS);
         if (!terminated) {
            LOG.warnf("ExecutorService did not terminate within 5 seconds after shutdownNow()");
         } else {
            LOG.warnf("ExecutorService terminated successfully");
         }
      }
   }

   RestClientConfiguration getConfiguration() {
      return configuration;
   }

   @Override
   public CompletionStage<RestResponse> execute(String method, String path, Map<String, String> headers, RestEntity entity) {
      HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofMillis(configuration.socketTimeout()));
      builder.uri(URI.create(baseURL + path));
      headers.forEach(builder::header);
      builder.method(method, entity.bodyPublisher());
      if (entity.contentType() != null) {
         builder.header(CONTENT_TYPE, entity.contentType().toString());
      }
      return execute(builder, bodyHandlerSupplier(headers));
   }
}
