package com.github.to2mbn.jmccc.mcdownloader.download;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.AsyncByteConsumer;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.protocol.HttpContext;
import com.github.to2mbn.jmccc.mcdownloader.download.concurrent.AsyncCallback;
import com.github.to2mbn.jmccc.mcdownloader.download.concurrent.AsyncCallbackGroup;
import com.github.to2mbn.jmccc.mcdownloader.download.concurrent.AsyncFuture;
import com.github.to2mbn.jmccc.mcdownloader.download.concurrent.Cancellable;

public class HttpAsyncDownloader implements Downloader {

	private static final Log LOGGER = LogFactory.getLog(HttpAsyncDownloader.class);

	private static class NullDownloadTaskListener<T> implements DownloadTaskListener<T> {

		@Override
		public void done(T result) {
		}

		@Override
		public void failed(Throwable e) {
		}

		@Override
		public void cancelled() {
		}

		@Override
		public void updateProgress(long done, long total) {
		}

		@Override
		public void retry(Throwable e, int current, int max) {
		}

	}

	private static interface RetryHandler {

		boolean doRetry(Throwable e);

	}

	private static final int DEFAULT_MAX_CONNECTIONS = 200;
	private static final int DEFAULT_MAX_CONNECTIONS_PRE_ROUTER = 20;

	private class TaskHandler<T> implements Runnable, Cancellable {

		class LifeCycleHandler<R> {

			AsyncCallback<R> proxied;

			LifeCycleHandler(AsyncCallback<R> proxied) {
				this.proxied = proxied;
			}

			void failed(Throwable e) {
				if (session != null) {
					try {
						session.failed(e);
					} catch (Throwable e1) {
						if (e != e1) {
							e.addSuppressed(e1);
						}
					}
				}
				if (retryHandler != null) {
					Lock lock = shutdownLock.readLock();
					lock.lock();
					try {
						if (!shutdown && retryHandler.doRetry(e)) {
							downloadFuture = null;
							session = null;
							start();
							return;
						}
					} finally {
						lock.unlock();
					}
				}
				proxied.failed(e);
			}

			void cancelled() {
				if (session != null) {
					try {
						session.cancelled();
					} catch (Throwable e) {
						proxied.failed(e);
						return;
					}
				}
				proxied.cancelled();
			}

			void done(R result) {
				proxied.done(result);
			}
		}

		class Inactiver implements AsyncCallback<T> {

			@Override
			public void done(T result) {
				inactive();
			}

			@Override
			public void failed(Throwable e) {
				inactive();
			}

			@Override
			public void cancelled() {
				inactive();
			}

			void inactive() {
				Lock lock = shutdownLock.readLock();
				lock.lock();
				try {
					activeTasks.remove(TaskHandler.this);
				} finally {
					lock.unlock();
				}

				if (shutdown & !shutdownComplete) {
					boolean doShutdown = false;
					Lock wlock = shutdownLock.writeLock();
					wlock.lock();
					try {
						if (!shutdownComplete) {
							shutdownComplete = true;
							doShutdown = true;
						}
					} finally {
						wlock.unlock();
					}

					if (doShutdown) {
						completeShutdown();
					}
				}
			}

		}

		DownloadTask<T> task;
		AsyncFuture<T> futuer;
		LifeCycleHandler<T> lifecycle;
		DownloadSession<T> session;
		DownloadTaskListener<T> listener;
		Future<T> downloadFuture;
		RetryHandler retryHandler;
		volatile boolean cancelled;
		volatile boolean mayInterruptIfRunning;

		TaskHandler(DownloadTask<T> task, DownloadTaskListener<T> listener, RetryHandler retryHandler) {
			this.task = task;
			this.listener = listener;
			this.futuer = new AsyncFuture<>(this);
			this.lifecycle = new LifeCycleHandler<>(AsyncCallbackGroup.group(new Inactiver(), futuer, listener));
			this.retryHandler = retryHandler;
		}

		void start() {
			bootstrapPool.submit(this);
		}

		@Override
		public void run() {
			try {
				Lock lock = shutdownLock.readLock();
				lock.lock();
				try {
					if (shutdown || cancelled) {
						lifecycle.cancelled();
						return;
					}

					downloadFuture = httpClient.execute(HttpAsyncMethods.createGet(task.getURI()), new AsyncByteConsumer<T>() {

						long contextLength = -1;
						long received = 0;

						@Override
						protected void onByteReceived(ByteBuffer buf, IOControl ioctrl) throws IOException {
							if (session == null) {
								session = task.createSession(8192);
							}
							received += buf.remaining();
							session.receiveData(buf);
							listener.updateProgress(received, contextLength);
						}

						@Override
						protected void onResponseReceived(HttpResponse response) throws HttpException, IOException {
							if (response.getStatusLine() != null) {
								int statusCode = response.getStatusLine().getStatusCode();
								if (statusCode < 200 || statusCode > 299) { // not 2xx
									throw new IOException("Illegal http response code: " + statusCode);
								}
							}
							if (session == null) {
								HttpEntity httpEntity = response.getEntity();
								if (httpEntity != null) {
									long contextLength = httpEntity.getContentLength();
									if (contextLength >= 0) {
										this.contextLength = contextLength;
									}
								}
								session = task.createSession(contextLength > 0 ? contextLength : 8192);
							}
						}

						@Override
						protected T buildResult(HttpContext context) throws Exception {
							return session.completed();
						}

					}, new FutureCallback<T>() {

						@Override
						public void completed(T result) {
							lifecycle.done(result);
						}

						@Override
						public void failed(Exception e) {
							lifecycle.failed(e);
						}

						@Override
						public void cancelled() {
							lifecycle.cancelled();
						}
					});
				} finally {
					lock.unlock();
				}

				if (cancelled) {
					downloadFuture.cancel(mayInterruptIfRunning);
				}
			} catch (Throwable e) {
				lifecycle.failed(e);
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			this.mayInterruptIfRunning |= mayInterruptIfRunning;
			if (downloadFuture != null) {
				downloadFuture.cancel(mayInterruptIfRunning);
			}
			return true;
		}

	}

	private class RetryHandlerImpl implements RetryHandler {

		DownloadTaskListener<?> proxied;
		int max;
		int current = 1;

		RetryHandlerImpl(DownloadTaskListener<?> proxied, int max) {
			this.proxied = proxied;
			this.max = max;
		}

		@Override
		public boolean doRetry(Throwable e) {
			if (e instanceof IOException && current < max) {
				proxied.retry(e, current++, max);
				return true;
			}
			return false;
		}

	}

	private CloseableHttpAsyncClient httpClient;
	private ExecutorService bootstrapPool;
	private volatile boolean shutdown = false;
	private volatile boolean shutdownComplete = false;
	private Set<TaskHandler<?>> activeTasks = Collections.newSetFromMap(new ConcurrentHashMap<TaskHandler<?>, Boolean>());
	// lock for shutdown, shutdownComplete, activeTasks
	private ReadWriteLock shutdownLock = new ReentrantReadWriteLock();

	public HttpAsyncDownloader() {
		httpClient = HttpAsyncClientBuilder.create().setMaxConnPerRoute(DEFAULT_MAX_CONNECTIONS_PRE_ROUTER).setMaxConnTotal(DEFAULT_MAX_CONNECTIONS).build();
		bootstrapPool = new ThreadPoolExecutor(0, Runtime.getRuntime().availableProcessors(), 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		httpClient.start();
	}

	@Override
	public <T> Future<T> download(DownloadTask<T> task, DownloadTaskListener<T> listener) {
		return download0(task, nonNullDownloadListener(listener), null);
	}

	@Override
	public <T> Future<T> download(DownloadTask<T> task, DownloadTaskListener<T> listener, int tries) {
		listener = nonNullDownloadListener(listener);
		return download0(task, listener, new RetryHandlerImpl(listener, tries));
	}

	@Override
	public void shutdown() {
		Lock lock = shutdownLock.writeLock();
		lock.lock();
		try {
			shutdown = true;
		} finally {
			lock.unlock();
		}

		bootstrapPool.shutdown();
		bootstrapPool = null;

		if (activeTasks.isEmpty()) {
			// cleanup in current thread
			shutdownComplete = true;
			completeShutdown();
		} else {
			// cancel all the tasks and let the latest task cleanup
			for (TaskHandler<?> task : activeTasks) {
				task.cancel(true);
			}
		}

	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	private <T> Future<T> download0(DownloadTask<T> task, DownloadTaskListener<T> listener, RetryHandler retryHandler) {
		Lock lock = shutdownLock.readLock();
		lock.lock();
		try {
			if (shutdown) {
				throw new RejectedExecutionException("already shutdown");
			}
			TaskHandler<T> handler = new TaskHandler<>(task, listener, retryHandler);
			activeTasks.add(handler);
			handler.start();
			return handler.futuer;
		} finally {
			lock.unlock();
		}
	}

	private <T> DownloadTaskListener<T> nonNullDownloadListener(DownloadTaskListener<T> o) {
		return o == null ? new NullDownloadTaskListener<T>() : o;
	}

	private void completeShutdown() {
		try {
			httpClient.close();
		} catch (IOException e) {
			LOGGER.error("an exception occurred during shutdown http client", e);
		}
		httpClient = null;
	}

}
