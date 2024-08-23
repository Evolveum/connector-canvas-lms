package com.evolveum.polygon.connector.canvas;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpHeaders;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;

/**
 * "Connection" class, but Client is harder to confuse with "Connector".
 * This manages the HTTP client and code around requests/response.
 */
public class CanvasClient {

    private static final Log LOG = Log.getLog(CanvasClient.class);

    private static final String API_BASE = "/api/v1";

    private final String apiBaseUrl;

    private final CloseableHttpClient httpClient;

    public CanvasClient(CanvasConfiguration configuration) {
        apiBaseUrl = configuration.getBaseUrl() + API_BASE;

        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();

        StringBuilder token = new StringBuilder();
        if (configuration.getAuthToken() != null) {
            configuration.getAuthToken().access(chars -> token.append(chars));
        }
        httpClientBuilder.setDefaultHeaders(List.of(
                new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        httpClient = httpClientBuilder.build();
    }

    /**
     * String is request part after /api/v1 prefix.
     * If no response handler is provided, {@link #throwIfNotSuccess} is used.
     * If handler is provided, the default is not used unless listed explicitly.
     * Handlers are checked in order, use more specific handlers first and "not success" last.
     */
    public CanvasResponse get(String apiRequest, ResponseHandler... responseHandlers) {
        return callRequest(new HttpGet(apiBaseUrl + apiRequest), handlersOrDefault(responseHandlers));
    }

    public CanvasResponse putJson(String apiRequest, String jsonBody, ResponseHandler... responseHandlers) {
        return jsonRequest(new HttpPut(apiBaseUrl + apiRequest), jsonBody, responseHandlers);
    }

    public CanvasResponse postJson(String apiRequest, String jsonBody, ResponseHandler... responseHandlers) {
        return jsonRequest(new HttpPost(apiBaseUrl + apiRequest), jsonBody, responseHandlers);
    }

    private CanvasResponse jsonRequest(
            HttpEntityEnclosingRequestBase request, String jsonBody, ResponseHandler... responseHandlers) {
        LOG.ok("request body: {0}", jsonBody);
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        return callRequest(request, handlersOrDefault(responseHandlers));
    }

    public CanvasResponse delete(String apiRequest, ResponseHandler... responseHandlers) {
        return callRequest(new HttpDelete(apiBaseUrl + apiRequest), handlersOrDefault(responseHandlers));
    }

    private static final ResponseHandler[] DEFAULT_RESPONSE_HANDLERS =
            new ResponseHandler[] { CanvasClient::throwIfNotSuccess };

    private ResponseHandler[] handlersOrDefault(ResponseHandler... responseHandlers) {
        if (responseHandlers == null || responseHandlers.length == 0) {
            return DEFAULT_RESPONSE_HANDLERS;
        }
        return responseHandlers;
    }

    /**
     * Page attribute can contain a number, or "bookmark:<url-friendly-base64>".
     * The bookmark is produced by <a href="https://github.com/instructure/canvas-lms/blob/master/gems/bookmarked_collection/lib/bookmarked_collection/collection.rb#L81">this code</a>
     * which uses <a href="https://github.com/instructure/canvas-lms/blob/master/gems/json_token/lib/json_token.rb#L27">this method</a>.
     * This creates "URL-friendly" Base64 (using - and _ instead of + and /) without padding (=).
     */
    private static final Pattern PAGE_PATTERN = Pattern.compile(".*[^_]page=([0-9A-Za-z:_-]+).*");

    private static final Pattern PER_PAGE_PATTERN = Pattern.compile(".*per_page=(\\d+).*");

    private CanvasResponse callRequest(HttpRequestBase request, ResponseHandler[] responseHandlers) {
        LOG.ok("request {0}: {1}", request.getMethod(), request.getURI());

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            LOG.ok("response: {0}", response);

            CanvasResponse canvasResponse = new CanvasResponse(request);
            canvasResponse.statusCode = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(response.getEntity());
            LOG.ok("response body: {0}", body);
            canvasResponse.body = body;

            for (ResponseHandler responseHandler : responseHandlers) {
                responseHandler.handle(canvasResponse);
            }

            // Storing info about next page for paginated results
            Arrays.stream(response.getHeaders("Link"))
                    .flatMap(h -> Arrays.stream(h.getElements()))
                    .forEach(e -> {
                        NameValuePair relParameter = e.getParameterByName("rel");
                        if (relParameter != null && Objects.equals(relParameter.getValue(), "next")) {
                            // The header element looks like this: <https://learning.k8s.evolveum.com/api/v1/accounts/1/users?page=3&per_page=100>; rel=next
                            // Using toString() and extracting the info from there is the easiest way.
                            String headerElementString = e.toString();
                            String nextPage = matchAndGet(headerElementString, PAGE_PATTERN);
                            if (nextPage != null) {
                                canvasResponse.nextPage = nextPage;
                                canvasResponse.pageSize = matchAndGet(headerElementString, PER_PAGE_PATTERN);
                            } else {
                                LOG.warn("Canvas pagination issue: Found 'next' page link, but did not recognize the 'page' value. Original link: {0}", headerElementString);
                            }
                        }
                    });

            return canvasResponse;
        } catch (IOException e) {
            throw new ConnectorIOException(e.getMessage(), e);
        }
    }

    /**
     * This method is used as a default {@link ResponseHandler} if no handler is provided
     * for higher-level methods like {@link #postJson} or {@link #get}.
     */
    public static void throwIfNotSuccess(CanvasResponse response) {
        if (response.isNotSuccess()) {
            throw new ConnectorIOException(
                    "Response status code " + response.statusCode + " for request:\n"
                            + response.request + "\nResponse BODY:\n" + response.bodyPreview(500));
        }
    }

    private String matchAndGet(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.matches() ? matcher.group(1) : null;
    }

    public void close() {
        try {
            this.httpClient.close();
        } catch (IOException e) {
            LOG.error("Error closing HTTP client: {0}", e.getMessage(), e);
        }
    }

    /**
     * Response handler contract and no-op implementation.
     * NOOP is important when you want no default handling, otherwise
     * {@link CanvasClient#callRequest(HttpRequestBase, ResponseHandler...)} throws if status is not 2xx.
     */
    public interface ResponseHandler {
        ResponseHandler NOOP = response -> {
        };

        void handle(CanvasResponse response);
    }

    /** Handler for 404 status. */
    public static class NotFoundResponseHandler implements ResponseHandler {
        private final Uid uid;
        private final ObjectClass objectClass;

        public NotFoundResponseHandler(Uid uid, ObjectClass objectClass) {
            this.uid = uid;
            this.objectClass = objectClass;
        }

        @Override
        public void handle(CanvasResponse response) {
            if (response.statusCode == 404) {
                throw new UnknownUidException(uid, objectClass);
            }
        }
    }
}
