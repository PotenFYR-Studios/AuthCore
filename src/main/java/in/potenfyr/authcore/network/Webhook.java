package in.potenfyr.authcore.network;

import in.potenfyr.authcore.security.Security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import in.potenfyr.authcore.AuthCoreServer;

/**
 * Discord webhook notifications for login/security events. Runs asynchronously on the shared I/O
 * executor so the server thread is never blocked. Disabled when no webhook URL is configured.
 */
public final class Webhook {

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private Webhook() {}

  /** Sends a plain message to the configured Discord webhook (async, fire-and-forget). */
  public static void send(String content) {
    if (!isEnabled()) return;

    JsonObject payload = new JsonObject();
    payload.addProperty("content", content);

    noMentions(payload);
    post(payload.toString());
  }

  /**
   * Sends an embed-based security event to the configured Discord webhook (async).
   *
   * @param title event title (e.g. "Login Alert")
   * @param description event description
   * @param color decimal color (e.g. 0x2ECC71 = green, 0xE74C3C = red)
   */
  public static void sendEmbed(String title, String description, int color) {
    if (!isEnabled()) return;

    JsonObject embed = new JsonObject();
    embed.addProperty("title", title);
    embed.addProperty("description", description);
    embed.addProperty("color", color);
    embed.addProperty("timestamp", Instant.now().toString());

    JsonArray embeds = new JsonArray();
    embeds.add(embed);

    JsonObject payload = new JsonObject();
    payload.addProperty("username", "AuthCore Security");
    payload.add("embeds", embeds);

    noMentions(payload);
    post(payload.toString());
  }

  /**
   * Registration announcement for Discord bots (role sync). Goes to
   * {@code security.role-sync-webhook-url} when set - that setting used to be read nowhere - and
   * falls back to the regular security webhooks otherwise.
   */
  public static void sendRegistration(String content) {
    String roleSync =
        AuthCoreServer.config == null ? null : AuthCoreServer.config.session.security.roleSyncWebhookUrl;
    if (roleSync == null || roleSync.isBlank()) {
      send(content);
      return;
    }
    JsonObject payload = new JsonObject();
    payload.addProperty("content", content);
    noMentions(payload);
    postTo(roleSync.trim(), payload.toString());
  }

  /**
   * Disables every Discord mention in a payload. The messages embed player-chosen text (names,
   * nicknames): on an offline-mode server a player named "@everyone" or "<@&roleId>" turned each
   * AuthCore alert into a mass ping of the staff channel.
   */
  private static void noMentions(JsonObject payload) {
    JsonObject allowed = new JsonObject();
    allowed.add("parse", new JsonArray());
    payload.add("allowed_mentions", allowed);
  }

  /** Whether a webhook URL is configured. */
  public static boolean isEnabled() {
    return AuthCoreServer.config != null
        && AuthCoreServer.config.session.security.webhookUrl != null
        && !AuthCoreServer.config.session.security.webhookUrl.isBlank();
  }

  /** Posts the JSON payload to the configured webhooks (primary + extras) asynchronously. */
  private static void post(String json) {
    java.util.List<String> urls = new java.util.ArrayList<>();
    urls.add(AuthCoreServer.config.session.security.webhookUrl);
    if (AuthCoreServer.config.session.security.extraWebhookUrls != null)
      urls.addAll(AuthCoreServer.config.session.security.extraWebhookUrls);

    for (String url : urls) postTo(url, json);
  }

  /** Posts the JSON payload to one webhook URL asynchronously. */
  private static void postTo(String url, String json) {
    {
      if (url == null || url.isBlank()) return;
      AuthCoreServer.LOGGER.debug(
          false, "Webhook | posting alert to {}", url.length() > 40 ? url.substring(0, 40) + "..." : url);
      AuthCoreServer.IO_EXECUTOR.execute(
          () -> {
            try {
              HttpRequest request =
                  HttpRequest.newBuilder(URI.create(url))
                      .timeout(Duration.ofSeconds(5))
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString(json))
                      .build();

              HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception err) {
              AuthCoreServer.LOGGER.debug(null, "Webhook delivery failed:", err);
            }
          });
    }
  }
}
