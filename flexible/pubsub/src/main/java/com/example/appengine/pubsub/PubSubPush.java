package com.example.appengine.pubsub;

import static java.lang.Thread.sleep;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.Transaction;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name = "PubSubPush", value = "/pubsub/push")
public class PubSubPush extends HttpServlet {
  private static final int MAX_ELEMENTS = 10;
  private static final String KEY = "message_list";
  private static final String FIELD = "messages";

  private long maxTimeout = 5000L; // 5 seconds
  private String entryKind = "pushed_messages";

  public void setTimeoutMilliSeconds(long timeout) {
    maxTimeout = timeout;
  }

  public void setEntryKind(String kind) {
    entryKind = kind;
  }

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws IOException, ServletException {
    final String apiToken = System.getenv("PUBSUB_VERIFICATION_TOKEN");

    try {
      // message = JSON.parse request.body.read
      JsonReader jsonReader = new JsonReader(req.getReader());

      // Token doesn't match apiToken
      if (req.getParameter("token").compareTo(apiToken) != 0) {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return;
      }

      Map<String, Map<String, String>> requestBody = new Gson()
          .fromJson(jsonReader, new TypeToken<HashMap<String, HashMap<String,
              String>>>(){}.getType());
      final String requestData = requestBody.get("message").get("data");

      // Decode data into String
      byte[] decodedData = Base64.getDecoder().decode(requestData);
      String stringData = new String(decodedData, StandardCharsets.UTF_8);

      // Save payload to be displayed later
      saveMessage(stringData);
    } catch (JsonParseException error) {
      resp.getWriter().print(error.toString());
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }
  }

  public void saveMessage(String message) {
    try {
      // set starting sleepTime
      long timeout = 1000;

      // Attempt to save message
      while (timeout < maxTimeout) {
        if (trySaveMessage(message)) {
          break;
        }
        sleep(timeout);
        timeout *= 2;
      }
    } catch (InterruptedException ie) {
      System.err.println(ie);
    }
  }

  private boolean trySaveMessage(String message) {
    // Start a new transaction
    Datastore datastoreService = DatastoreOptions.getDefaultInstance()
        .getService();
    Transaction transaction = datastoreService.newTransaction();

    // Create a Gson object to serialize messages LinkedList as a JSON string
    Gson gson = new Gson();

    // Transaction flag (assume it worked)
    boolean messagesSaved = true;

    try {
      // Create a keyfactory for entries of KIND
      KeyFactory keyFactory = datastoreService.newKeyFactory()
          .setKind(entryKind);

      // Lookup KEY
      Key key = keyFactory.newKey(KEY);
      Entity entity = transaction.get(key);

      // Entity doesn't exist so let's create it!
      if (entity == null) {
        List<String> messages = new LinkedList<>();
        messages.add(message);

        // Create update entity
        entity = Entity.newBuilder(key)
            .set(FIELD, gson.toJson(messages))
            .build();

      } else { // It does exist
        // Parse JSON into an LinkedList
        List<String> messages = gson.fromJson(entity.getString(FIELD),
            new TypeToken<LinkedList<String>>(){}.getType());

        // Add message to head of list
        messages.add(0,message);

        // End index
        int endIndex = Math.min(messages.size(), MAX_ELEMENTS);

        // subList out at most MAX_ELEMENTS messages
        messages = messages.subList(0, endIndex);

        // Update entity
        entity = Entity.newBuilder(entity)
            .set(FIELD, gson.toJson(messages))
            .build();
      }
      // Save and commit update
      transaction.put(entity);
      transaction.commit();
    } finally {
      if (transaction.isActive()) {
        // we don't have an entry yet transaction failed
        transaction.rollback();
        messagesSaved = false;
      }
    }
    // we have an entry to work with
    return messagesSaved;
  }
}

