package com.banjocreek.translatebot;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;

public class OauthHandler {

    private static final AmazonDynamoDBClient ddb = new AmazonDynamoDBClient();

    private static final String TableName = "TranslateSlack";

    public final Map<String, Object> handle(final Map<String, String> in) {

        final Map<String, String> authData = generateOutput(in);
        final String authBody = urlEncode(authData);

        try {
            final URL u = new URL("https://slack.com/api/oauth.access?" + authBody);
            System.out.println(u);
            final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setDoInput(true);
            conn.setRequestMethod("POST");
            try (InputStream is = conn.getInputStream();) {
                final JsonReader reader = Json.createReader(is);
                final JsonObject slackAuth = reader.readObject();
                final Map<String, Object> result = plainifyJsonObject(slackAuth);
                storeResult(result);
            } finally {
                conn.disconnect();
            }
        } catch (final Exception x) {
            System.err.println("Uh Oh");
            x.printStackTrace(System.err);
            return Collections.singletonMap("ok", false);
        }
        return Collections.singletonMap("ok", true);

    }

    private Map<String, String> generateOutput(final Map<String, String> in) {

        final String clientId = new DBValueRetriever("global:clientid").get();
        final String clientSecret = new DBValueRetriever("global:clientsecret").get();

        final HashMap<String, String> rval = new HashMap<>();
        rval.put("code", in.get("code"));
        rval.put("client_id", clientId);
        rval.put("client_secret", clientSecret);
        return Collections.unmodifiableMap(rval);
    }

    private PutRequest item(final String id, final String value) {
        final HashMap<String, AttributeValue> item = new HashMap<>();
        item.put("id", new AttributeValue(id));
        item.put("value", new AttributeValue(value));
        return new PutRequest().withItem(item);
    }

    private final String param(final Entry<String, String> entry) {
        return param(entry.getKey(), entry.getValue());
    }

    private final String param(final String name, final String value) {
        try {
            return new StringBuffer().append(name).append("=").append(URLEncoder.encode(value, "UTF-8")).toString();
        } catch (final UnsupportedEncodingException e) {
            throw new RuntimeException("cannot encode value", e);
        }
    }

    private List<Object> plainifyJsonArray(final JsonArray jary) {
        return jary.stream().map(this::plainifyJsonValue).collect(Collectors.toList());
    }

    private Map<String, Object> plainifyJsonObject(final JsonObject jobj) {
        final HashMap<String, Object> rval = new HashMap<>();
        jobj.entrySet().forEach(e -> rval.put(e.getKey(), plainifyJsonValue(e.getValue())));
        return Collections.unmodifiableMap(rval);
    }

    private Object plainifyJsonValue(final JsonValue jval) {
        switch (jval.getValueType()) {
        case ARRAY:
            return plainifyJsonArray((JsonArray) jval);
        case FALSE:
            return Boolean.FALSE;
        case TRUE:
            return Boolean.TRUE;
        case NULL:
            return null;
        case NUMBER:
            return ((JsonNumber) jval).bigDecimalValue();
        case OBJECT:
            return plainifyJsonObject((JsonObject) jval);
        case STRING:
            return ((JsonString) jval).getString();
        default:
            throw new RuntimeException("unexpected json type");
        }
    }

    private void storeResult(final Map<String, Object> result) {
        final String userId = (String) result.get("user_id");
        final String userToken = (String) result.get("access_token");
        final String teamId = (String) result.get("team_id");
        @SuppressWarnings("unchecked")
        final Map<String, Object> bot = (Map<String, Object>) result.get("bot");
        final String botToken = (String) bot.get("bot_access_token");
        final String botId = (String) bot.get("bot_user_id");

        final ArrayList<PutRequest> putRequests = new ArrayList<>();

        putRequests.add(item("user:" + userId + ":token", userToken));
        putRequests.add(item("user:" + botId + ":token", botToken));
        putRequests.add(item("team:" + teamId + ":botuser", botId));

        final List<WriteRequest> writeRequests = putRequests.stream()
                .map(WriteRequest::new)
                .collect(Collectors.toList());
        final HashMap<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(TableName, writeRequests);
        final BatchWriteItemRequest batchWriteItemRequest = new BatchWriteItemRequest().withRequestItems(requestItems);

        ddb.batchWriteItem(batchWriteItemRequest);
    }

    private final String urlEncode(final Map<String, String> params) {
        return params.entrySet().stream().map(this::param).collect(Collectors.joining("&"));
    }

}
