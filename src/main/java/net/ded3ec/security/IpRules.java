package net.ded3ec.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.ded3ec.AuthCoreServer;

/**
 * Per-IP allow/deny rules loaded from {@code config/authcore/ip-rules.conf}.
 *
 * <p>Format: one rule per line, {@code allow <ip>} or {@code deny <ip>}. Lines starting with
 * {@code #} are comments. The first matching rule wins; if no rule matches, the connection is
 * allowed (the default).
 */
public final class IpRules {

  private static final List<String[]> RULES = new ArrayList<>();

  private IpRules() {}

  /** (Re)loads the rule file from disk. */
  public static synchronized void load() {
    RULES.clear();

    if (AuthCoreServer.config == null
        || AuthCoreServer.config.session.security.ipRulesFile == null
        || AuthCoreServer.config.session.security.ipRulesFile.isBlank()) return;

    Path path = AuthCoreServer.configPath.resolve(AuthCoreServer.config.session.security.ipRulesFile);
    if (!Files.exists(path)) return;

    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 2
            && (parts[0].equalsIgnoreCase("allow") || parts[0].equalsIgnoreCase("deny")))
          RULES.add(new String[] {parts[0].toLowerCase(), parts[1]});
      }
      AuthCoreServer.LOGGER.info(true, "Loaded {} IP rule(s) from {}", RULES.size(), path.getFileName());
    } catch (IOException err) {
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
  public static synchronized boolean isDenied(String ip) {
    if (ip == null) return false;

    boolean hasAllowRule = false;
    for (String[] rule : RULES) {
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
      return net.ded3ec.network.ProxySupport.cidrMatches(ip, ruleValue);
    return false;
  }
}
