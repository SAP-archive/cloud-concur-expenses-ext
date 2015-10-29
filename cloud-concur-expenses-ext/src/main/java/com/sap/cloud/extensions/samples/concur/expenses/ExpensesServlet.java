package com.sap.cloud.extensions.samples.concur.expenses;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;

import javax.annotation.Resource;
import javax.naming.ConfigurationException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.xml.bind.DatatypeConverter;

import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.cloud.account.TenantContext;
import com.sap.core.connectivity.api.configuration.ConnectivityConfiguration;
import com.sap.core.connectivity.api.configuration.DestinationConfiguration;

/**
 * Servlet which demonstrates connecting to Concur API and listing users' expenses. <br>
 * For information about SAP HANA Cloud Platform specific configuration:
 *
 * @see <a href="https://help.hana.ondemand.com/help/frameset.htm?b068356dd7c34cf7ad6b6023deeb317d.html">HTTP
 *      Destinations</a>
 *
 * @see <a href="https://help.hana.ondemand.com/help/frameset.htm?d872cfb4801c4b54896816df4b75c75d.html">HTTP Proxy for
 *      On-Premise Connectivity</a>
 *
 * @see <a href="https://help.hana.ondemand.com/help/frameset.htm?d553d78bf9bd4ecbac201b873f557db6.html">Cloud
 *      Environment Variables</a>
 */
public class ExpensesServlet extends HttpServlet {

	private static final long serialVersionUID = -8860779673525432453L;
	private static final Logger LOGGER = LoggerFactory.getLogger(ExpensesServlet.class);

	private static final int BUFFER_SIZE = 1024;
	private static final int INDENT_FACTOR = 4;

	private static final String EXPENSES_PATH = "/v3.0/expense/entries?user=all";
	private static final String API_DESTINATION = "concur-api";
	private static final String AUTH_DESTINATION = "concur-auth";

	/**
	 * OnPremise proxy type - the application can connect to an on-premise back-end system through HANA Cloud connector.
	 */
	private static final String ON_PREMISE_PROXY = "OnPremise";

	/**
	 * Host and port of Proxy for Internet connectivity which must be passed as VM arguments if running locally.
	 */
	private static final String INTERNET_PROXY_HOST = "http.proxyHost";
	private static final String INTERNET_PROXY_PORT = "http.proxyPort";

	/**
	 * Used to get the consumer account id and propagate via SAP-Connectivity-ConsumerAccount header.
	 */
	@Resource
	private TenantContext tenantContext;
	private static final String CONSUMER_ACCOUNT_HEADER = "SAP-Connectivity-ConsumerAccount";

	/**
	 * Host and port of Proxy for On-Premise connectivity, which are set from HCP.
	 */
	private static final String ON_PREMISE_PROXY_HOST = "HC_OP_HTTP_PROXY_HOST";
	private static final String ON_PREMISE_PROXY_PORT = "HC_OP_HTTP_PROXY_PORT";

	private ConnectivityConfiguration configuration;
	private static final String CONNECTIVITY_CONFIGURATION = "java:comp/env/connectivityConfiguration";

	@Override
	public void init() throws ServletException {
		// Look up the connectivity configuration API
		try {
			Context ctx = new InitialContext();
			configuration = (ConnectivityConfiguration) ctx.lookup(CONNECTIVITY_CONFIGURATION);
		} catch (NamingException e) {
			LOGGER.error("Could not lookup Connectivity Configuration [ {} ] ", CONNECTIVITY_CONFIGURATION, e);
			throw new UnavailableException("Could not lookup Connectivity Configuration. See logs for details.");
		}

		if (configuration == null) {
			throw new UnavailableException("Looking up Conenctivity Configuration returned null.");
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		HttpSession session = request.getSession(true);

		String authToken = (String) session.getAttribute("authToken");
		if (authToken == null) {
			logDebug("Authentication token not found. Creating new one.");
			try {
				authToken = createOAuthToken();
			} catch (IOException | ConfigurationException e) {
				LOGGER.error("Exception occurred while trying to create authentication token.", e);
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"Exception occurred while trying to create authentication token."
								+ " Hint: Make sure to have the destination configured. See the logs for more details.");
				return;
			}

			if (authToken == null || authToken.isEmpty()) {
				LOGGER.error("Empty authentication token created!");
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Invalid authentication token.");
				return;
			}

			logDebug("Setting authentication token for the current user session");
			session.setAttribute("authToken", authToken);
		}

		HttpURLConnection apiConnection = null;
		try {
			apiConnection = getAPIConnection();
			// set OAuth header which is required for connecting to concur
			apiConnection.setRequestProperty("Authorization", authToken);

			String result = getJsonResult(apiConnection);
			PrintWriter writer = response.getWriter();
			writer.println(result);
		} catch (ConfigurationException e) {
			LOGGER.error("Exception occurred while configuring API URL Connection", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Configuring HttpUrlConnection failed. Hint: Make sure to have the destination configured. See the logs for more details.");
		}
	}

	/**
	 * Returns an OAuth token by extracting it from the response from the HttpURLConnection for concur-auth
	 */
	private String createOAuthToken() throws IOException, ConfigurationException {
		String authToken;
		HttpURLConnection authConnection = getAuthConnection();

		InputStream inputStream = authConnection.getInputStream();
		authToken = "OAuth " + extractToken(inputStream);
		return authToken;
	}

	/**
	 * Extracts the token from the response returned from authenticating to Concur
	 */
	private String extractToken(InputStream inputStream) throws IOException {
		String stringResult = getStringFromStream(inputStream);

		JsonParser parser = new JsonParser();
		JsonObject authTokenResult = parser.parse(stringResult).getAsJsonObject();
		JsonObject accessToken = authTokenResult.getAsJsonObject("Access_Token");
		return accessToken.get("Token").getAsString();
	}

	/**
	 * Returns a properly configured HttpURLConnection for consuming the concur-auth destination
	 */
	private HttpURLConnection getAuthConnection() throws IOException, ConfigurationException {
		logDebug("Configuring HttpUrlConnection for authentication");
		Map<String, String> authProperties = getDestinationProperties(AUTH_DESTINATION);
		String authURL = authProperties.get("URL");
		String user = authProperties.get("User");
		String password = authProperties.get("Password");
		String consuemrKey = authProperties.get("X-ConsumerKey");
		String proxyType = authProperties.get("ProxyType");

		/*
		 * ----------- Concur documentation ----------
		 *
		 * The format of the call is: GET https://www.concursolutions.com/net2/oauth2/accesstoken.ashx
		 * Authorization: Basic {Base64 encoded LoginID:Password}
		 * X-ConsumerKey: {Consumer Key}
		 *
		 * ----------- Concur documentation ----------
		 */

		// get the destination url
		URL url = new URL(authURL);
		Proxy proxy = getProxy(proxyType);
		HttpURLConnection authConnection = (HttpURLConnection) url.openConnection(proxy);

		// Set Authorization, X-ConsumerKey and, if on-premise connectivity,
		// the customer account for the proxy
		injectProxyHeader(authConnection, proxyType);
		injectAuthHeaders(authConnection, consuemrKey);

		String authorization = authenticate(user, password);
		authConnection.setRequestProperty("Authorization", "Basic " + authorization);

		return authConnection;
	}

	/**
	 * Returns a properly configured HttpURLConnection for consuming the concur-api destination
	 */
	private HttpURLConnection getAPIConnection() throws IOException, ConfigurationException {
		logDebug("Configuring HttpUrlConnection for the API");
		Map<String, String> apiProperties = getDestinationProperties(API_DESTINATION);

		/*
		 * The only difference between the travel example and the expenses example is in the path that we append after
		 * the API URL.
		 */
		String expensesURL = apiProperties.get("URL") + EXPENSES_PATH;
		String proxyType = apiProperties.get("ProxyType");

		// get the destination url
		URL url = new URL(expensesURL);
		Proxy proxy = getProxy(proxyType);
		HttpURLConnection apiConnection = (HttpURLConnection) url.openConnection(proxy);
		// set proxy header if on-premise
		injectProxyHeader(apiConnection, proxyType);

		return apiConnection;
	}

	/**
	 * Given a user and password, combines them in a proper format and returns a Base64 encoded String for
	 * authentication.
	 */
	private String authenticate(String user, String password) {
		// combine credentials into proper format for encoding
		StringBuilder buffer = new StringBuilder();
		buffer.append(user).append(":").append(password);

		// Encode credentials in base64
		return DatatypeConverter.printBase64Binary(buffer.toString().getBytes());
	}

	/**
	 * Returns a map containing the destination properties for the given destination
	 */
	private Map<String, String> getDestinationProperties(String destinationName) throws ConfigurationException {
		DestinationConfiguration destConfiguration = configuration.getConfiguration(destinationName);
		if (destConfiguration == null) {
			throw new ConfigurationException(
					String.format("Destination [ %s ] not found. Hint: Make sure to have the destination configured.",
							destinationName));
		}
		logDebug("Getting destination properties for destination [ {} ]", destinationName);
		return destConfiguration.getAllProperties();
	}

	/**
	 * Extracts the contents of a HttpURLConnection's InputStream and converts it to a Json-formatted string
	 */
	private String getJsonResult(HttpURLConnection urlConnection) throws IOException {
		InputStream apiInStream = urlConnection.getInputStream();
		String xmlContentFromStream = getStringFromStream(apiInStream);
		JSONObject jsonObject = XML.toJSONObject(xmlContentFromStream);
		return jsonObject.toString(INDENT_FACTOR);
	}

	/**
	 * Gets the content of an input stream and returns it as a String
	 */
	private String getStringFromStream(InputStream inputStream) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[BUFFER_SIZE];
		int length = 0;
		while ((length = inputStream.read(buffer)) != -1) {
			baos.write(buffer, 0, length);
		}
		return new String(baos.toByteArray());
	}

	/**
	 * Returns a proxy, configured for the given proxy type, which will be used by an HttpURLConnection
	 */
	private Proxy getProxy(String proxyType) {
		String proxyHost;
		int proxyPort;

		if (ON_PREMISE_PROXY.equals(proxyType)) {
			logDebug("Configuring on-premise proxy");
			// Get proxy for on-premise destinations
			proxyHost = System.getenv(ON_PREMISE_PROXY_HOST);
			proxyPort = Integer.parseInt(System.getenv(ON_PREMISE_PROXY_PORT));
		} else {
			logDebug("Configuring internet proxy");
			// Get proxy for internet destinations
			proxyHost = System.getProperty(INTERNET_PROXY_HOST);
			proxyPort = Integer.parseInt(System.getProperty(INTERNET_PROXY_PORT));
		}
		return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
	}

	/**
	 * Inserts header for an on-premise connectivity, containing the consumer account name
	 */
	private void injectProxyHeader(HttpURLConnection urlConnection, String proxyType) {
		if (ON_PREMISE_PROXY.equals(proxyType)) {
			urlConnection.setRequestProperty(CONSUMER_ACCOUNT_HEADER, tenantContext.getTenant().getAccount().getId());
		}
	}

	/**
	 * Inserts Accept header to receive result in JSON format and X-ConsumerKey which is required for Concur
	 * authentication
	 */
	private void injectAuthHeaders(HttpURLConnection urlConnection, String consumerKey) {
		urlConnection.setRequestProperty("Accept", "application/json");
		urlConnection.setRequestProperty("X-ConsumerKey", consumerKey);
	}

	/**
	 * Logs the message with the specified arguments if debugging is enabled for the logger
	 */
	private void logDebug(String message, Object... arguments) {
		if (LOGGER.isDebugEnabled()) {
			if (arguments.length == 1) {
				LOGGER.debug(message, arguments[0]);
			} else if (arguments.length == 2) {
				LOGGER.debug(message, arguments[0], arguments[1]);
			} else {
				LOGGER.debug(message, arguments);
			}
		}
	}
}
