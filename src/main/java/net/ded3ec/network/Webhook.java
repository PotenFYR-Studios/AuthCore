package net.ded3ec.network;

import net.ded3ec.models.Config;
import net.ded3ec.security.Security;
import net.ded3ec.util.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import net.ded3ec.AuthCoreServer;

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

    post(payload.toString());
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

    for (String url : urls) {
      if (url == null || url.isBlank()) continue;
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
