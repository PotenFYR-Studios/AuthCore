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
 * <p>Format: one rule per line, {@code allow <ip-or-cidr>} or {@code deny <ip-or-cidr>}, where the
 * value is an exact IPv4/IPv6 address or a CIDR range ({@code 203.0.113.0/24},
 * {@code 2001:db8::/32}). Lines starting with {@code #} are comments; a trailing {@code # note}
 * after a rule is ignored. The first matching rule wins; if no rule matches, the connection is
 * allowed - unless at least one {@code allow} rule exists (whitelist semantics). Malformed lines
 * are skipped with a console warning naming the line number. {@code /authcore reload} re-reads
 * the file.
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
      int lineNo = 0;
      int skipped = 0;
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        lineNo++;
        String trimmed = line.trim();
        int comment = trimmed.indexOf('#');
        if (comment >= 0) trimmed = trimmed.substring(0, comment).trim();
        if (trimmed.isEmpty()) continue;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 2
            && (parts[0].equalsIgnoreCase("allow") || parts[0].equalsIgnoreCase("deny"))
            && isValidRuleValue(parts[1])) {
          parsed.add(new String[] {parts[0].toLowerCase(), parts[1]});
        } else {
          skipped++;
          if (skipped <= 20)
            AuthCoreServer.LOGGER.warn(
                false,
                "{} line {}: ignoring malformed rule '{}' (expected 'allow <ip|cidr>' or 'deny <ip|cidr>').",
                path.getFileName(),
                lineNo,
                trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed);
        }
      }
      if (skipped > 20)
        AuthCoreServer.LOGGER.warn(
            false, "{}: {} malformed rule line(s) ignored in total.", path.getFileName(), skipped);
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
   * <p>The first matching rule wins. Rules match an exact IP or an IPv4/IPv6 CIDR range
   * ({@code deny 203.0.113.0/24}, {@code deny 2001:db8::/32}) - the same matcher the proxy allowlist uses, so honeypot
   * bans and admin rules behave identically for single IPs and subnets. When the admin
   * configured at least one {@code allow} rule, unmatched IPs are denied (whitelist
   * semantics) - otherwise an {@code allow} line would be a silent no-op.
   *
   * @param ip the player IP
   * @return {@code true} if the IP is not allowed to join
   */
  public static boolean isDenied(String ip) {
    if (ip == null) return false;
    ip = normalizeIp(ip);

    List<String[]> snapshot = rules;
    boolean hasAllowRule = false;
    for (String[] rule : snapshot) {
      if (rule[0].equals("allow")) hasAllowRule = true;
      if (ruleMatches(rule[1], ip)) return rule[0].equals("deny");
    }
    return hasAllowRule;
  }

  /** Exact-IP (value-compared, so IPv6 spellings match) or IPv4/IPv6 CIDR match. */
  public static boolean ruleMatches(String ruleValue, String ip) {
    if (ruleValue.indexOf('/') > 0)
      return in.potenfyr.authcore.network.ProxySupport.cidrMatches(ip, ruleValue);
    return in.potenfyr.authcore.network.ProxySupport.sameAddress(ruleValue, ip);
  }

  /** A rule value must be an IP literal or a CIDR range of one. */
  public static boolean isValidRuleValue(String value) {
    int slash = value.indexOf('/');
    if (slash < 0) return in.potenfyr.authcore.network.ProxySupport.isValidIp(value);
    String base = value.substring(0, slash);
    String bits = value.substring(slash + 1);
    if (!in.potenfyr.authcore.network.ProxySupport.isValidIp(base) || !bits.matches("[0-9]{1,3}"))
      return false;
    return Integer.parseInt(bits) <= (base.indexOf(':') >= 0 ? 128 : 32);
  }

  /** Strips brackets, a {@code :port} suffix and an IPv6 zone index from a socket address. */
  public static String normalizeIp(String ip) {
    String s = ip.trim();
    if (s.startsWith("/")) s = s.substring(1);
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      s = close > 0 ? s.substring(1, close) : s.substring(1);
    } else if (s.indexOf(':') > 0 && s.indexOf(':') == s.lastIndexOf(':')) {
      s = s.substring(0, s.indexOf(':')); // IPv4 "a.b.c.d:port"
    }
    int zone = s.indexOf('%');
    if (zone > 0) s = s.substring(0, zone);
    return s;
  }
}
