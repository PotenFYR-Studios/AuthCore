package in.potenfyr.authcore.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import in.potenfyr.authcore.AuthCoreServer;

/**
 * Per-IP allow/deny rules loaded from {@code config/authcore/ip-rules.conf}.
 *
 * <p>Format: one rule per line, {@code allow <ip>} or {@code deny <ip>}. Lines starting with
 * {@code #} are comments. The first matching rule wins; if no rule matches, the connection is
 * allowed (the default).
 */
public final class IpRules {

  /**
   * Immutable rule snapshot (copy-on-write). {@code isDenied} runs on the server thread for EVERY
   * join, while {@link #load()} does file I/O - sharing one monitor between them meant a honeypot
   * hit (which reloads the rules) or an admin reload could stall the server thread and freeze
   * joins. Readers now only touch this volatile reference.
   */
  private static volatile List<String[]> rules = List.of();

  private IpRules() {}

  /** (Re)loads the rule file from disk. Builds a new snapshot and swaps it in atomically. */
  public static void load() {
    List<String[]> parsed = new ArrayList<>();

    if (AuthCoreServer.config == null
        || AuthCoreServer.config.session.security.ipRulesFile == null
        || AuthCoreServer.config.session.security.ipRulesFile.isBlank()) {
      rules = List.of();
      return;
    }

    Path path = AuthCoreServer.configPath.resolve(AuthCoreServer.config.session.security.ipRulesFile);
    if (!Files.exists(path)) {
      rules = List.of();
      return;
    }

    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 2
            && (parts[0].equalsIgnoreCase("allow") || parts[0].equalsIgnoreCase("deny")))
          parsed.add(new String[] {parts[0].toLowerCase(), parts[1]});
      }
      rules = List.copyOf(parsed);
      AuthCoreServer.LOGGER.info(true, "Loaded {} IP rule(s) from {}", rules.size(), path.getFileName());
    } catch (IOException err) {
      // Keep the previous snapshot on a failed reload instead of silently unbanning everyone.
      AuthCoreServer.LOGGER.error(false, "Failed to load IP rules file:", err);
    }
  }

  /**
   * Checks the rules for the given IP.
   *
   * <p>The first matching rule wins. Rules match an exact IP or an IPv4 CIDR range
   * ({@code deny 203.0.113.0/24}) - the same matcher the proxy allowlist uses, so honeypot
   * bans and admin rules behave identically for single IPs and subnets. When the admin
   * configured at least one {@code allow} rule, unmatched IPs are denied (whitelist
   * semantics) - otherwise an {@code allow} line would be a silent no-op.
   *
   * @param ip the player IP
   * @return {@code true} if the IP is not allowed to join
   */
  public static boolean isDenied(String ip) {
    if (ip == null) return false;

    List<String[]> snapshot = rules;
    boolean hasAllowRule = false;
    for (String[] rule : snapshot) {
      if (rule[0].equals("allow")) hasAllowRule = true;
      if (ruleMatches(rule[1], ip)) return rule[0].equals("deny");
    }
    return hasAllowRule;
  }

  /** Exact-IP or IPv4 CIDR match of a rule value against a player IP. */
  private static boolean ruleMatches(String ruleValue, String ip) {
    if (ruleValue.equals(ip)) return true;
    int slash = ruleValue.indexOf('/');
    if (slash > 1 && ruleValue.indexOf(':') < 0 && ip.indexOf(':') < 0)
      return in.potenfyr.authcore.network.ProxySupport.cidrMatches(ip, ruleValue);
    return false;
  }
}
