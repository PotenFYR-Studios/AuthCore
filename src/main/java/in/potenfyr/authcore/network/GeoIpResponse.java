package in.potenfyr.authcore.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Strict provider normalization and bounded HTTP response collection. */
public final class GeoIpResponse {
  private GeoIpResponse() {}

  public static JsonObject parse(String body, boolean ipWhoIs) {
    try {
      var element = JsonParser.parseString(body);
      if (!element.isJsonObject()) return null;
      JsonObject input = element.getAsJsonObject();
      JsonObject output = new JsonObject();
      String country;
      String org;
      String continent;
      if (ipWhoIs) {
        var success = input.get("success");
        if (success == null || !success.isJsonPrimitive()
            || !success.getAsJsonPrimitive().isBoolean() || !success.getAsBoolean()) return null;
        country = text(input, "country");
        continent = text(input, "continent_code");
        org = input.get("connection") instanceof JsonObject conn ? text(conn, "org") : null;
      } else {
        if (!"success".equalsIgnoreCase(text(input, "status"))) return null;
        country = text(input, "CountryName");
        org = text(input, "org");
        continent = text(input, "continentCode");
      }
      if (country == null || country.isBlank() || org == null || org.isBlank()) return null;
      output.addProperty("status", "success");
      output.addProperty("CountryName", country);
      output.addProperty("org", org);
      if (continent != null && continent.matches("[A-Z]{2}"))
        output.addProperty("continentCode", continent);
      return output;
    } catch (RuntimeException err) {
      return null;
    }
  }

  private static String text(JsonObject object, String key) {
    var value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
    String text = value.getAsString();
    if (text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) return null;
    return text;
  }

  public static HttpResponse.BodyHandler<String> boundedBody() {
    return info -> new HttpResponse.BodySubscriber<>() {
      private final CompletableFuture<String> body = new CompletableFuture<>();
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private Flow.Subscription subscription;

      public CompletionStage<String> getBody() { return body; }
      public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        subscription.request(1);
      }
      public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
          if (buffer.remaining() > 65536 - bytes.size()) {
            subscription.cancel();
            body.completeExceptionally(new java.io.IOException("GeoIP response exceeds 64 KiB"));
            return;
          }
          byte[] chunk = new byte[buffer.remaining()];
          buffer.get(chunk);
          bytes.writeBytes(chunk);
        }
        subscription.request(1);
      }
      public void onError(Throwable error) { body.completeExceptionally(error); }
      public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
    };
  }
}
