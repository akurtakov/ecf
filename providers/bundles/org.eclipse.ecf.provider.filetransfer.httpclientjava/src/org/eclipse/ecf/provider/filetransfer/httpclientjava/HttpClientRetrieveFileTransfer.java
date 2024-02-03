/****************************************************************************
 * Copyright (c) 2021, 2022 Composent, Inc., IBM
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *  Composent, Inc. - initial API and implementation
 *  Maarten Meijer  - bug 237936, added gzip encoded transfer default
 *  Henrich Kraemer - bug 263869, testHttpsReceiveFile fails using HTTP proxy
 *  Henrich Kraemer - bug 263613, [transport] Update site contacting / downloading is not cancelable
 *  Thomas Joiner   - HttpClient 4 implementation
 *  Yatta Solutions - HttpClient 4.5 implementation
 *  Christoph L�ubrich - Java HTTP client implementation
 *
 * SPDX-License-Identifier: EPL-2.0
 *****************************************************************************/
package org.eclipse.ecf.provider.filetransfer.httpclientjava;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.ecf.core.identity.ID;
import org.eclipse.ecf.core.security.Callback;
import org.eclipse.ecf.core.security.CallbackHandler;
import org.eclipse.ecf.core.security.IConnectContext;
import org.eclipse.ecf.core.security.NameCallback;
import org.eclipse.ecf.core.security.ObjectCallback;
import org.eclipse.ecf.core.security.UnsupportedCallbackException;
import org.eclipse.ecf.core.util.Proxy;
import org.eclipse.ecf.core.util.ProxyAddress;
import org.eclipse.ecf.core.util.Trace;
import org.eclipse.ecf.filetransfer.FileTransferJob;
import org.eclipse.ecf.filetransfer.IFileRangeSpecification;
import org.eclipse.ecf.filetransfer.IFileTransferPausable;
import org.eclipse.ecf.filetransfer.IFileTransferRunnable;
import org.eclipse.ecf.filetransfer.IRetrieveFileTransferOptions;
import org.eclipse.ecf.filetransfer.IncomingFileTransferException;
import org.eclipse.ecf.filetransfer.InvalidFileRangeSpecificationException;
import org.eclipse.ecf.filetransfer.events.IFileTransferConnectStartEvent;
import org.eclipse.ecf.filetransfer.identity.IFileID;
import org.eclipse.ecf.internal.provider.filetransfer.DebugOptions;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.Activator;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.AuthScope;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.Credentials;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.ECFHttpClientFactory;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.HttpClientProxyCredentialProvider;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.HttpHost;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.IHttpClientContext;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.IHttpClientFactory;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.Messages;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.NTLMProxyDetector;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.UsernamePasswordCredentials;
import org.eclipse.ecf.provider.filetransfer.identity.FileTransferID;
import org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer;
import org.eclipse.ecf.provider.filetransfer.retrieve.HttpHelper;
import org.eclipse.ecf.provider.filetransfer.util.JREProxyHelper;
import org.eclipse.ecf.provider.filetransfer.util.ProxySetupHelper;
import org.eclipse.osgi.util.NLS;

@SuppressWarnings("restriction")
public class HttpClientRetrieveFileTransfer extends AbstractRetrieveFileTransfer {

	private static final String IDENTITY_ENCODING = "identity";

	private static final String GZIP_ENCODING = "gzip";

	private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";

	private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";

	private static final String RANGE = "Range";

	private static final String USERNAME_PREFIX = Messages.HttpClientRetrieveFileTransfer_Username_Prefix;

	protected static final int HTTP_PORT = 80;

	protected static final int HTTPS_PORT = 443;

	protected static final int MAX_RETRY = 2;

	protected static final String HTTPS = "https"; //$NON-NLS-1$

	protected static final String HTTP = "http"; //$NON-NLS-1$

	protected static final String[] supportedProtocols = {HTTP, HTTPS};

	private volatile CompletableFuture<HttpResponse<InputStream>> httpResponse;

	private HttpClient httpClient;

	private IHttpClientContext httpContext;

	private HttpRequest.Builder requestConfigBuilder;

	private String username;

	private String password;

	private int responseCode = -1;
	private volatile boolean doneFired = false;

	private String remoteFileName;

	protected int httpVersion = 1;

	protected IFileID fileid = null;

	private ECFCredentialsProvider credentialsProvider;

	protected JREProxyHelper proxyHelper = null;

	private FileTransferJob connectJob;

	private HttpRequest httpRequest;

	public HttpClientRetrieveFileTransfer(HttpClient client) {
		Assert.isNotNull(client);
		this.httpClient = client;

		IHttpClientFactory httpClientFactory = Activator.getDefault().getHttpClientFactory();
		credentialsProvider = new ECFCredentialsProvider();
		Authenticator contextCredentialsProvider = ECFHttpClientFactory.modifyCredentialsProvider(credentialsProvider);
		httpContext = httpClientFactory.newClientContext();
		httpContext.setCredentialsProvider(contextCredentialsProvider);
		proxyHelper = new JREProxyHelper();
	}

	@Override
	public String getRemoteFileName() {
		return remoteFileName;
	}

	@Override
	public synchronized void cancel() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "cancel"); //$NON-NLS-1$
		if (isCanceled()) {
			return; // break job cancel recursion
		}
		setDoneCanceled(exception);
		boolean fireDoneEvent = true;
		if (connectJob != null) {
			Trace.trace(Activator.PLUGIN_ID, "calling connectJob.cancel()"); //$NON-NLS-1$
			connectJob.cancel();
		}
		synchronized (jobLock) {
			if (job != null) {
				// Its the transfer jobs responsibility to throw the event.
				fireDoneEvent = false;
				Trace.trace(Activator.PLUGIN_ID, "calling transfer job.cancel()"); //$NON-NLS-1$
				job.cancel();
			}
		}
		if (httpResponse != null && !httpResponse.isCancelled()) {
			Trace.trace(Activator.PLUGIN_ID, "calling getMethod.abort()"); //$NON-NLS-1$
			httpResponse.cancel(true);
		}
		hardClose();
		if (fireDoneEvent) {
			fireTransferReceiveDoneEvent();
		}
		Trace.exiting(Activator.PLUGIN_ID, DebugOptions.METHODS_EXITING, this.getClass(), "cancel");//$NON-NLS-1$

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#hardClose()
	 */
	@Override
	protected void hardClose() {
		// changed for addressing bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=389292
		httpRequest = null;

		if (httpResponse != null) {
			httpResponse.cancel(true);
		}

		// Close output stream...if we're supposed to
		try {
			if (localFileContents != null && closeOutputStream)
				localFileContents.close();
		} catch (final IOException e) {
			Activator.getDefault().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, IStatus.ERROR, String.format("hardClose localFileContents.close() exception. url=%s",remoteFileURL), e)); //$NON-NLS-1$
		}
		// clear input and output streams
		remoteFileContents = null;
		localFileContents = null;
		// reset response code
		responseCode = -1;
		// If we're done and proxy helper still exists, then dispose
		if (proxyHelper != null && isDone()) {
			proxyHelper.dispose();
			proxyHelper = null;
		}
	}

	/**
	 * @return Credentials file request credentials
	 * @throws UnsupportedCallbackException if some problem
	 * @throws IOException if some problem
	 * @since 5.0
	 */
	protected Credentials getFileRequestCredentials() throws UnsupportedCallbackException, IOException {
		if (connectContext == null)
			return null;
		final CallbackHandler callbackHandler = connectContext.getCallbackHandler();
		if (callbackHandler == null)
			return null;
		final NameCallback usernameCallback = new NameCallback(USERNAME_PREFIX);
		final ObjectCallback passwordCallback = new ObjectCallback();
		callbackHandler.handle(new Callback[] {usernameCallback, passwordCallback});
		username = usernameCallback.getName();
		password = (String) passwordCallback.getObject();
		return new UsernamePasswordCredentials(username, password==null?null:password.toCharArray());
	}

	@Override
	protected void setupProxies() {
		// If it's been set directly (via ECF API) then this overrides platform settings
		if (proxy == null) {
			try {
				// give SOCKS priority see https://bugs.eclipse.org/bugs/show_bug.cgi?id=295030#c61
				proxy = ProxySetupHelper.getSocksProxy(getRemoteFileURL());
				if (proxy == null) {
					proxy = ProxySetupHelper.getProxy(getRemoteFileURL().toExternalForm());
				}
			} catch (NoClassDefFoundError e) {
				// If the proxy API is not available a NoClassDefFoundError will be thrown here.
				// If that happens then we just want to continue on.
				Activator.logNoProxyWarning(e);
			}
		}
		if (proxy != null)
			setupProxy(proxy);
	}

	@Override
	protected synchronized void resetDoneAndException() {
		// Doesn't match the description, but since it should be cleared before it is
		// reused, this is the best place.
		clearProxy();
		httpRequest = null;
		requestConfigBuilder = null;

		super.resetDoneAndException();
	}

	private synchronized HttpRequest.Builder getRequestConfigBuilder() {
		if (requestConfigBuilder == null) {
			requestConfigBuilder = Activator.getDefault().getHttpClientFactory().newRequestConfig(httpContext, getOptions());
		}
		return requestConfigBuilder;
	}

	protected void setupAuthentication(String urlString) throws UnsupportedCallbackException, IOException {
		Credentials credentials = null;
		if (username == null) {
			credentials = getFileRequestCredentials();
		}

		if (credentials != null && username != null) {
			final AuthScope authScope = new AuthScope(getHostFromURL(urlString), getPortFromURL(urlString));
			Trace.trace(Activator.PLUGIN_ID, "retrieve credentials=" + credentials); //$NON-NLS-1$
			credentialsProvider.setCredentials(authScope, credentials);
		}
	}

	protected void setRequestHeaderValues(Builder builder) throws InvalidFileRangeSpecificationException {
		final IFileRangeSpecification rangeSpec = getFileRangeSpecification();
		setRangeHeader(rangeSpec, -1, builder);

		int maxAge = Integer.getInteger("org.eclipse.ecf.http.cache.max-age", 0); //$NON-NLS-1$
		// set max-age for cache control to 0 for bug
		// https://bugs.eclipse.org/bugs/show_bug.cgi?id=249990
		// fix the fix for bug 249990 with bug 410813
		builder.header(HttpClientFileSystemBrowser.CACHE_CONTROL_HEADER, "max-age=" + maxAge); //$NON-NLS-1$
		setRequestHeaderValuesFromOptions(builder);
		// Setup Basic Authentication
		if (username != null) {
			byte[] credentials = Base64.getEncoder().encode((username + ":" + password).getBytes(StandardCharsets.UTF_8));
			requestConfigBuilder.header("Authorization", "Basic " + new String(credentials, StandardCharsets.UTF_8));
		}
	}

	private void setRangeHeader(final IFileRangeSpecification rangeSpec, final long resumePosition, Builder builder)
			throws InvalidFileRangeSpecificationException {
		final long startPosition;
		final long endPosition;
		if (rangeSpec != null) {
			startPosition = Math.max(resumePosition, rangeSpec.getStartPosition());
			endPosition = rangeSpec.getEndPosition();
			if (startPosition < 0) {
				throw new InvalidFileRangeSpecificationException(Messages.HttpClientRetrieveFileTransfer_RESUME_START_POSITION_LESS_THAN_ZERO, rangeSpec);
			}
			if (endPosition != -1L && endPosition <= startPosition) {
				throw new InvalidFileRangeSpecificationException(Messages.HttpClientRetrieveFileTransfer_RESUME_ERROR_END_POSITION_LESS_THAN_START, rangeSpec);
			}
		} else if (resumePosition > 0) {
			startPosition = resumePosition;
			endPosition = -1L;
		} else {
			// No range header needed
			return;
		}
		String rangeHeader = "bytes=" + startPosition + "-" + ((endPosition == -1L) ? "" : ("" + endPosition)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		Trace.trace(Activator.PLUGIN_ID, "retrieve range header=" + rangeHeader); //$NON-NLS-1$
		builder.setHeader(RANGE, rangeHeader);
	}

	private void setRequestHeaderValuesFromOptions(Builder builder) {
		Map<?, ?> localOptions = getOptions();
		if (localOptions != null) {
			Object o = localOptions.get(IRetrieveFileTransferOptions.REQUEST_HEADERS);
			if (o != null && o instanceof Map) {
				Map<?, ?> requestHeaders = (Map<?, ?>) o;
				for (Object n : requestHeaders.keySet()) {
					Object v = requestHeaders.get(n);
					if (n != null && n instanceof String && v != null && v instanceof String) {
						builder.setHeader((String) n, (String) v);
					}
				}
			}
		}
	}

	private boolean isHTTP11() {
		return (httpVersion >= 1);
	}

	public int getResponseCode() {
		if (responseCode != -1 || httpResponse == null || httpResponse.isCancelled())
			return responseCode;
		responseCode = httpResponse.join().statusCode();
		Trace.trace(Activator.PLUGIN_ID, "retrieve resp=" + responseCode); //$NON-NLS-1$
		return responseCode;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.core.identity.IIdentifiable#getID()
	 */
	@Override
	public ID getID() {
		return fileid;
	}

	private long getLastModifiedTimeFromHeader() throws IOException {
		long lastModified = HttpClientFileSystemBrowser.getLastModifiedTimeFromHeader(getHeader());
		if (lastModified == 0) {
			throw new IOException(Messages.HttpClientRetrieveFileTransfer_INVALID_LAST_MODIFIED_TIME);
		}
		return lastModified;
	}

	protected void getResponseHeaderValues() throws IOException {
		if (getResponseCode() == -1)
			throw new IOException(Messages.HttpClientRetrieveFileTransfer_INVALID_SERVER_RESPONSE_TO_PARTIAL_RANGE_REQUEST);
		HttpHeaders headers = getHeader();
		if (headers.firstValue(HttpClientFileSystemBrowser.LAST_MODIFIED_HEADER).isPresent()) {
			setLastModifiedTime(getLastModifiedTimeFromHeader());
		}
		setFileLength(headers.firstValueAsLong(HttpClientFileSystemBrowser.CONTENT_LENGTH_HEADER).orElse(-1));
		fileid = new FileTransferID(getRetrieveNamespace(), getRemoteFileURL());

		// Get content disposition header and get remote file name from it if possible.
		String contentDispositionHeader = headers.firstValue(HttpHelper.CONTENT_DISPOSITION_HEADER).orElse(null);
		if (contentDispositionHeader != null) {
			remoteFileName = HttpHelper.getRemoteFileNameFromContentDispositionHeader(contentDispositionHeader);
		}
		// If still null, get the path from httpclient.getMethod()
		if (remoteFileName == null) {
			// No name could be extracted using Content-Disposition. Let's try the
			// path from the getMethod.
			String pathStr = httpRequest.uri().toASCIIString();
			if (pathStr != null && pathStr.length() > 0) {
				IPath path = Path.fromPortableString(pathStr);
				if (path.segmentCount() > 0)
					remoteFileName = path.lastSegment();
			}
			// If still null, use the input file name
			if (remoteFileName == null)
				// Last resort. Use the path of the initial URL request
				remoteFileName = super.getRemoteFileName();
		}
	}

	private HttpHeaders getHeader() {
		return httpResponse.join().headers();
	}

	final class ECFCredentialsProvider extends HttpClientProxyCredentialProvider {

		@Override
		protected Proxy getECFProxy() {
			return getProxy();
		}

		@Override
		protected boolean allowNTLMAuthentication() {
			return ECFHttpClientFactory.getNTLMProxyHandler(httpContext).allowNTLMAuthentication(getOptions());
		}

	}

	Proxy getProxy() {
		return proxy;
	}

	@Override
	protected void setInputStream(InputStream ins) {
		remoteFileContents = ins;
	}

	@Override
	protected InputStream wrapTransferReadInputStream(InputStream inputStream, IProgressMonitor monitor) {
		// Added to address bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=389292
		return new NoCloseWrapperInputStream(inputStream);
	}

	// Added to address bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=389292
	class NoCloseWrapperInputStream extends FilterInputStream {

		protected NoCloseWrapperInputStream(InputStream in) {
			super(in);
		}

		@Override
		public void close() {
			// do nothing
		}
	}

	@Override
	protected int getSocketReadTimeout() {
		int result = ECFHttpClientFactory.DEFAULT_READ_TIMEOUT;
		Map<?, ?> localOptions = getOptions();
		if (localOptions != null) {
			// See if the connect timeout option is present, if so set
			Object o = localOptions.get(IRetrieveFileTransferOptions.READ_TIMEOUT);
			if (o != null) {
				if (o instanceof Integer) {
					result = ((Integer) o).intValue();
				} else if (o instanceof String) {
					result = Integer.parseInt((String) o);
				}
				return result;
			}
			o = localOptions.get(HttpClientOptions.RETRIEVE_READ_TIMEOUT_PROP);
			if (o != null) {
				if (o instanceof Integer) {
					result = ((Integer) o).intValue();
				} else if (o instanceof String) {
					result = Integer.parseInt((String) o);
				}
			}
		}
		return result;
	}

	/**
	 * @return int connect timeout
	 * @since 4.0
	 */
	protected int getConnectTimeout() {
		int result = ECFHttpClientFactory.DEFAULT_CONNECTION_TIMEOUT;
		Map<?, ?> localOptions = getOptions();
		if (localOptions != null) {
			// See if the connect timeout option is present, if so set
			Object o = localOptions.get(IRetrieveFileTransferOptions.CONNECT_TIMEOUT);
			if (o != null) {
				if (o instanceof Integer) {
					result = ((Integer) o).intValue();
				} else if (o instanceof String) {
					result = Integer.parseInt((String) o);
				}
				return result;
			}
			o = localOptions.get(HttpClientOptions.RETRIEVE_CONNECTION_TIMEOUT_PROP);
			if (o != null) {
				if (o instanceof Integer) {
					result = ((Integer) o).intValue();
				} else if (o instanceof String) {
					result = Integer.parseInt((String) o);
				}
			}
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#openStreams()
	 */
	@Override
	protected void openStreams() throws IncomingFileTransferException {

		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "openStreams"); //$NON-NLS-1$
		final String urlString = getRemoteFileURL().toString();
		this.doneFired = false;

		int code = -1;

		try {
			HttpRequest.Builder rcfgBuilder = getRequestConfigBuilder();
			rcfgBuilder.timeout(Duration.ofMillis(getConnectTimeout()));
			rcfgBuilder.uri(new URI(urlString));
			rcfgBuilder.GET();

			setupAuthentication(urlString);

			// Define a CredentialsProvider - found that possibility while debugging in org.apache.commons.httpclient.HttpMethodDirector.processProxyAuthChallenge(HttpMethod)
			// Seems to be another way to select the credentials.
			setRequestHeaderValues(rcfgBuilder);

			Trace.trace(Activator.PLUGIN_ID, "retrieve=" + urlString); //$NON-NLS-1$
			// Set request header for possible gzip encoding, but only if
			// 1) The file range specification is null (we want the whole file)
			// 2) The target remote file does *not* end in .gz (see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=280205)
			boolean contentCompressionEnabled;
			if (getFileRangeSpecification() == null && !targetHasGzSuffix(super.getRemoteFileName())) {
				// The interceptors to provide gzip are always added and are enabled by default
				Trace.trace(Activator.PLUGIN_ID, "Accept-Encoding: gzip,deflate added to request header"); //$NON-NLS-1$
				setContentCompressionEnabled(rcfgBuilder, true);
				contentCompressionEnabled = true;
			} else {
				// Disable the interceptors to provide gzip
				Trace.trace(Activator.PLUGIN_ID, "Accept-Encoding NOT added to header"); //$NON-NLS-1$
				setContentCompressionEnabled(rcfgBuilder, false);
				contentCompressionEnabled = false;
			}
			httpRequest = rcfgBuilder.build();

			fireConnectStartEvent();
			if (checkAndHandleDone()) {
				return;
			}

			// Actually execute get and get response code (since redirect is set to true, then
			// redirect response code handled internally
			if (connectJob == null) {
				performConnect(new NullProgressMonitor());
			} else {
				connectJob.schedule();
				connectJob.join();
				connectJob = null;
			}
			if (checkAndHandleDone()) {
				return;
			}

			code = responseCode;

			responseHeaders = getResponseHeaders();

			Trace.trace(Activator.PLUGIN_ID, "retrieve resp=" + code); //$NON-NLS-1$

			// Check for NTLM proxy in response headers
			// This check is to deal with bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=252002
			boolean ntlmProxyFound = NTLMProxyDetector.detectNTLMProxy(httpContext);
			if (ntlmProxyFound)
				ECFHttpClientFactory.getNTLMProxyHandler(httpContext).handleNTLMProxy(getProxy(), code);

			if (NTLMProxyDetector.detectSPNEGOProxy(httpContext))
				ECFHttpClientFactory.getNTLMProxyHandler(httpContext).handleSPNEGOProxy(getProxy(), code);

			if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
				getResponseHeaderValues();
				HttpResponse<InputStream> response = httpResponse.join();
				InputStream body = response.body();
				if (contentCompressionEnabled) {
					if (GZIP_ENCODING
							.equalsIgnoreCase(response.headers().firstValue(CONTENT_ENCODING_HEADER).orElse(null))) {
						body = new GZIPInputStream(body);
					}
				}
				setInputStream(body);
				fireReceiveStartEvent();
			} else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
				consume(httpResponse);
				throw new IncomingFileTransferException(NLS.bind("File not found: {0}", urlString), code); //$NON-NLS-1$
			} else if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
				consume(httpResponse);
				throw new IncomingFileTransferException(Messages.HttpClientRetrieveFileTransfer_Unauthorized, code);
			} else if (code == HttpURLConnection.HTTP_FORBIDDEN) {
				consume(httpResponse);
				throw new IncomingFileTransferException("Forbidden", code); //$NON-NLS-1$
			} else if (code == HttpURLConnection.HTTP_PROXY_AUTH) {
				consume(httpResponse);
				throw new IncomingFileTransferException(Messages.HttpClientRetrieveFileTransfer_Proxy_Auth_Required, code);
			} else {
				consume(httpResponse);
				throw new IncomingFileTransferException(NLS.bind(Messages.HttpClientRetrieveFileTransfer_ERROR_GENERAL_RESPONSE_CODE, Integer.valueOf(code)), code);
			}
		} catch (final Exception e) {
			Trace.throwing(Activator.PLUGIN_ID, DebugOptions.EXCEPTIONS_THROWING, this.getClass(), "openStreams", e); //$NON-NLS-1$
			if (code == -1) {
				if (!isDone()) {
					setDoneException(e);
				}
				fireTransferReceiveDoneEvent();
			} else {
				IncomingFileTransferException ex = (IncomingFileTransferException) ((e instanceof IncomingFileTransferException) ? e : new IncomingFileTransferException(NLS.bind(Messages.HttpClientRetrieveFileTransfer_EXCEPTION_COULD_NOT_CONNECT, urlString), e, code));
				throw ex;
			}
		}
		Trace.exiting(Activator.PLUGIN_ID, DebugOptions.METHODS_EXITING, this.getClass(), "openStreams"); //$NON-NLS-1$
	}

	private void consume(CompletableFuture<HttpResponse<InputStream>> httpResponse) {
		httpResponse.thenAccept(resp -> {
			InputStream stream = resp.body();
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
				}
			}
		});
	}

	private void setContentCompressionEnabled(Builder builder, boolean value) {
		if (value) {
			builder.setHeader(ACCEPT_ENCODING_HEADER, GZIP_ENCODING);
		} else {
			builder.setHeader(ACCEPT_ENCODING_HEADER, IDENTITY_ENCODING);
		}
	}

	private Map<String, List<String>> getResponseHeaders() {
		if (httpResponse == null || httpResponse.isCancelled())
			return null;
		return Collections.unmodifiableMap(getHeader().map());
	}

	private boolean checkAndHandleDone() {
		if (isDone()) {
			// for cancel the done event should have been fired always.
			if (!doneFired) {
				fireTransferReceiveDoneEvent();
			}
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.filetransfer.IRetrieveFileTransferContainerAdapter#setConnectContextForAuthentication(org.eclipse.ecf.core.security.IConnectContext)
	 */
	@Override
	public void setConnectContextForAuthentication(IConnectContext connectContext) {
		super.setConnectContextForAuthentication(connectContext);
		this.username = null;
		this.password = null;
	}

	protected static String getHostFromURL(String url) {
		String result = url;
		final int colonSlashSlash = url.indexOf("://"); //$NON-NLS-1$
		if (colonSlashSlash < 0)
			return ""; //$NON-NLS-1$
		if (colonSlashSlash >= 0) {
			result = url.substring(colonSlashSlash + 3);
		}

		final int colonPort = result.indexOf(':');
		final int requestPath = result.indexOf('/');

		int substringEnd;

		if (colonPort > 0 && requestPath > 0)
			substringEnd = Math.min(colonPort, requestPath);
		else if (colonPort > 0)
			substringEnd = colonPort;
		else if (requestPath > 0)
			substringEnd = requestPath;
		else
			substringEnd = result.length();

		return result.substring(0, substringEnd);

	}

	protected static int getPortFromURL(String url) {
		final int colonSlashSlash = url.indexOf("://"); //$NON-NLS-1$
		if (colonSlashSlash < 0)
			return urlUsesHttps(url) ? HTTPS_PORT : HTTP_PORT;
		// This is wrong as if the url has no colonPort before '?' then it should return the default

		int colonPort = url.indexOf(':', colonSlashSlash + 1);
		if (colonPort < 0)
			return urlUsesHttps(url) ? HTTPS_PORT : HTTP_PORT;
		// Make sure that the colonPort is not from some part of the rest of the URL
		int nextSlash = url.indexOf('/', colonSlashSlash + 3);
		if (nextSlash != -1 && colonPort > nextSlash)
			return urlUsesHttps(url) ? HTTPS_PORT : HTTP_PORT;
		// Make sure the colonPort is not part of the credentials in URI
		final int atServer = url.indexOf('@', colonSlashSlash + 1);
		if (atServer != -1 && colonPort < atServer && atServer < nextSlash)
			colonPort = url.indexOf(':', atServer + 1);
		if (colonPort < 0)
			return urlUsesHttps(url) ? HTTPS_PORT : HTTP_PORT;

		final int requestPath = url.indexOf('/', colonPort + 1);

		int end;
		if (requestPath < 0)
			end = url.length();
		else
			end = requestPath;

		return Integer.parseInt(url.substring(colonPort + 1, end));
	}

	protected static boolean urlUsesHttps(String url) {
		url = url.trim();
		return url.startsWith(HTTPS + ":"); //$NON-NLS-1$
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.internal.provider.filetransfer.AbstractRetrieveFileTransfer#supportsProtocol(java.lang.String)
	 */
	public static boolean supportsProtocol(String protocolString) {
		for (String supportedProtocol : supportedProtocols) {
			if (supportedProtocol.equalsIgnoreCase(protocolString)) {
				return true;
			}
		}
		return false;
	}

	protected boolean isConnected() {
		return (httpRequest != null && httpResponse != null && !httpResponse.isCancelled());
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#doPause()
	 */
	@Override
	protected boolean doPause() {
		if (isPaused() || !isConnected() || isDone())
			return false;
		this.paused = true;
		return this.paused;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#doResume()
	 */
	@Override
	protected boolean doResume() {
		if (!isPaused() || isConnected())
			return false;
		return openStreamsForResume();
	}

	protected void setResumeRequestHeaderValues(Builder builder)
			throws IOException, InvalidFileRangeSpecificationException {
		if (this.bytesReceived <= 0 || this.fileLength <= this.bytesReceived)
			throw new IOException(Messages.HttpClientRetrieveFileTransfer_RESUME_START_ERROR);
		setRequestHeaderValues(builder);
		final IFileRangeSpecification rangeSpec = getFileRangeSpecification();
		setRangeHeader(rangeSpec, bytesReceived, builder);
		setRequestHeaderValuesFromOptions(builder);
	}

	private boolean openStreamsForResume() {

		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "openStreamsForResume"); //$NON-NLS-1$
		final String urlString = getRemoteFileURL().toString();
		this.doneFired = false;

		int code = -1;

		try {
			Builder builder = requestConfigBuilder.copy();
			setContentCompressionEnabled(builder, false);

			setupAuthentication(urlString);

			// Define a CredentialsProvider - found that possibility while debugging in org.apache.commons.httpclient.HttpMethodDirector.processProxyAuthChallenge(HttpMethod)
			// Seems to be another way to select the credentials.
			setResumeRequestHeaderValues(builder);

			Trace.trace(Activator.PLUGIN_ID, "resume=" + urlString); //$NON-NLS-1$
			httpRequest = builder.build();

			// Gzip encoding is not an option for resume
			fireConnectStartEvent();
			if (checkAndHandleDone()) {
				return false;
			}

			// Actually execute get and get response code (since redirect is set to true, then
			// redirect response code handled internally
			if (connectJob == null) {
				performConnect(new NullProgressMonitor());
			} else {
				connectJob.schedule();
				connectJob.join();
				connectJob = null;
			}
			if (checkAndHandleDone()) {
				return false;
			}

			code = responseCode;

			responseHeaders = getResponseHeaders();

			Trace.trace(Activator.PLUGIN_ID, "retrieve resp=" + code); //$NON-NLS-1$

			if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
				getResumeResponseHeaderValues();
				setInputStream(httpResponse.join().body());
				this.paused = false;
				fireReceiveResumedEvent();
			} else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
				consume(httpResponse);
				throw new IncomingFileTransferException(NLS.bind("File not found: {0}", urlString), code); //$NON-NLS-1$
			} else if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
				consume(httpResponse);
				throw new IncomingFileTransferException(Messages.HttpClientRetrieveFileTransfer_Unauthorized, code);
			} else if (code == HttpURLConnection.HTTP_FORBIDDEN) {
				consume(httpResponse);
				throw new IncomingFileTransferException("Forbidden", code); //$NON-NLS-1$
			} else if (code == HttpURLConnection.HTTP_PROXY_AUTH) {
				consume(httpResponse);
				throw new IncomingFileTransferException(Messages.HttpClientRetrieveFileTransfer_Proxy_Auth_Required,
						code);
			} else {
				consume(httpResponse);
				throw new IncomingFileTransferException(
						NLS.bind(Messages.HttpClientRetrieveFileTransfer_ERROR_GENERAL_RESPONSE_CODE,
								Integer.valueOf(code)),
						code);
			}
			Trace.exiting(Activator.PLUGIN_ID, DebugOptions.METHODS_EXITING, this.getClass(), "openStreamsForResume", Boolean.TRUE); //$NON-NLS-1$
			return true;
		} catch (final Exception e) {
			Trace.catching(Activator.PLUGIN_ID, DebugOptions.EXCEPTIONS_CATCHING, this.getClass(), "openStreamsForResume", e); //$NON-NLS-1$
			if (code == -1) {
				if (!isDone()) {
					setDoneException(e);
				}
			} else {
				setDoneException((e instanceof IncomingFileTransferException) ? e : new IncomingFileTransferException(NLS.bind(Messages.HttpClientRetrieveFileTransfer_EXCEPTION_COULD_NOT_CONNECT, urlString), e, code, responseHeaders));
			}
			fireTransferReceiveDoneEvent();
			Trace.exiting(Activator.PLUGIN_ID, DebugOptions.METHODS_EXITING, this.getClass(), "openStreamsForResume", Boolean.FALSE); //$NON-NLS-1$
			return false;
		}
	}

	protected void getResumeResponseHeaderValues() throws IOException {
		if (getResponseCode() != HttpURLConnection.HTTP_PARTIAL)
			throw new IOException();
		if (lastModifiedTime != getLastModifiedTimeFromHeader())
			throw new IOException(Messages.HttpClientRetrieveFileTransfer_EXCEPTION_FILE_MODIFIED_SINCE_LAST_ACCESS);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#getAdapter(java.lang.Class)
	 */
	@Override
	public <T> T getAdapter(Class<T> adapter) {
		if (adapter == null)
			return null;
		if (adapter.equals(IFileTransferPausable.class) && isHTTP11())
			return adapter.cast(this);
		return super.getAdapter(adapter);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ecf.provider.filetransfer.retrieve.AbstractRetrieveFileTransfer#setupProxy(org.eclipse.ecf.core.util.Proxy)
	 */
	@Override
	protected void setupProxy(Proxy proxy) {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, HttpClientRetrieveFileTransfer.class, "setupProxy " + proxy); //$NON-NLS-1$
		if (proxy.getType().equals(Proxy.Type.HTTP)) {
			final ProxyAddress address = proxy.getAddress();
			httpContext.setProxy(new HttpHost(address.getHostName(), address.getPort()));
		} else if (proxy.getType().equals(Proxy.Type.SOCKS)) {
			Trace.trace(Activator.PLUGIN_ID, "retrieve socksproxy=" + proxy.getAddress()); //$NON-NLS-1$
			httpContext.setProxy(null);
			proxyHelper.setupProxy(proxy);
		}
	}

	/**
	 * This method will clear out the proxy information (so that if this is
	 * reused for a request without a proxy, it will work correctly).
	 * @since 5.0
	 */
	protected void clearProxy() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, HttpClientRetrieveFileTransfer.class, "clearProxy()"); //$NON-NLS-1$
		httpContext.setProxy(null);
	}

	protected void fireConnectStartEvent() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "fireConnectStartEvent"); //$NON-NLS-1$
		// TODO: should the following be in super.fireReceiveStartEvent();
		listener.handleTransferEvent(new IFileTransferConnectStartEvent() {
			@Override
			public IFileID getFileID() {
				return remoteFileID;
			}

			@Override
			public void cancel() {
				HttpClientRetrieveFileTransfer.this.cancel();
			}

			@Override
			public FileTransferJob prepareConnectJob(FileTransferJob j) {
				return HttpClientRetrieveFileTransfer.this.prepareConnectJob(j);
			}

			@Override
			public void connectUsingJob(FileTransferJob j) {
				HttpClientRetrieveFileTransfer.this.connectUsingJob(j);
			}

			@Override
			public String toString() {
				final StringBuffer sb = new StringBuffer("IFileTransferConnectStartEvent["); //$NON-NLS-1$
				sb.append(getFileID());
				sb.append("]"); //$NON-NLS-1$
				return sb.toString();
			}

			@Override
			public <T> T getAdapter(Class<T> adapter) {
				return adapter.cast(HttpClientRetrieveFileTransfer.this.getAdapter(adapter));
			}
		});
	}

	protected String createConnectJobName() {
		return getRemoteFileURL().toString() + createRangeName() + Messages.HttpClientRetrieveFileTransfer_CONNECTING_JOB_NAME;
	}

	protected FileTransferJob prepareConnectJob(FileTransferJob cjob) {
		if (cjob == null) {
			// Create our own
			cjob = new FileTransferJob(createJobName());
		}
		cjob.setFileTransfer(this);
		cjob.setFileTransferRunnable(fileConnectRunnable);
		return cjob;
	}

	protected void connectUsingJob(FileTransferJob cjob) {
		Assert.isNotNull(cjob);
		this.connectJob = cjob;
	}

	private IFileTransferRunnable fileConnectRunnable = new IFileTransferRunnable() {
		@Override
		public IStatus performFileTransfer(IProgressMonitor monitor) {
			return performConnect(monitor);
		}
	};

	private IStatus performConnect(IProgressMonitor monitor) {
		// there might be more ticks in the future perhaps for
		// connect socket, certificate validation, send request, authenticate,
		int ticks = 1;
		monitor.beginTask(getRemoteFileURL().toString() + Messages.HttpClientRetrieveFileTransfer_CONNECTING_TASK_NAME, ticks);
		try {
			if (monitor.isCanceled()) {
				setDoneCanceled();
				return Status.CANCEL_STATUS;
			}
			httpResponse = httpClient.sendAsync(httpRequest, BodyHandlers.ofInputStream());
			responseCode = httpResponse.get(getConnectTimeout(),TimeUnit.MILLISECONDS).statusCode();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			setDoneCanceled();
			return Status.CANCEL_STATUS;
		} catch (CancellationException e) {
			setDoneCanceled();
			return Status.CANCEL_STATUS;
		} catch (Exception e) {
			if (e instanceof ExecutionException) {
				Throwable cause = ((ExecutionException) e).getCause();
				if (cause instanceof Exception) {
					e = (Exception) cause;
				}
			}
			Trace.catching(Activator.PLUGIN_ID, DebugOptions.EXCEPTIONS_CATCHING, this.getClass(), "performConnect", e); //$NON-NLS-1$
			if (!isDone()) {
				setDoneException(e);
			}
		} finally {
			monitor.done();
		}
		return Status.OK_STATUS;

	}

	@Override
	protected void fireReceiveResumedEvent() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "fireReceiveResumedEvent len=" + fileLength + ";rcvd=" + bytesReceived); //$NON-NLS-1$ //$NON-NLS-2$
		super.fireReceiveResumedEvent();
	}

	@Override
	protected void fireTransferReceiveDataEvent() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "fireTransferReceiveDataEvent len=" + fileLength + ";rcvd=" + bytesReceived); //$NON-NLS-1$ //$NON-NLS-2$
		super.fireTransferReceiveDataEvent();
	}

	@Override
	protected void fireTransferReceiveDoneEvent() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "fireTransferReceiveDoneEvent len=" + fileLength + ";rcvd=" + bytesReceived); //$NON-NLS-1$ //$NON-NLS-2$
		this.doneFired = true;
		super.fireTransferReceiveDoneEvent();
	}

	@Override
	protected void fireTransferReceivePausedEvent() {
		Trace.entering(Activator.PLUGIN_ID, DebugOptions.METHODS_ENTERING, this.getClass(), "fireTransferReceivePausedEvent len=" + fileLength + ";rcvd=" + bytesReceived); //$NON-NLS-1$ //$NON-NLS-2$
		super.fireTransferReceivePausedEvent();
	}

}
