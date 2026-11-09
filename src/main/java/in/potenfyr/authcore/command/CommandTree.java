package in.potenfyr.authcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import in.potenfyr.authcore.AuthCoreServer;
import in.potenfyr.authcore.models.User;
import java.lang.reflect.Field;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Command-tree shaping that Brigadier's builder API cannot express.
 *
 * <p>Brigadier (a plain library: identical, never obfuscated, on every Minecraft version and
 * loader) keeps a node's requirement in {@code CommandNode.requirement}. Wrapping it lets AuthCore
 *
 * <ul>
 *   <li><b>prune unusable branches</b> - an intermediate literal (e.g. {@code /authcore delete})
 *       whose permission check sits on a deeper argument is offered only when something below it
 *       is usable, instead of showing a dead end;
 *   <li><b>filter the limbo</b> - while a player is unauthenticated, every root command the lobby
 *       command gate would reject (vanilla {@code /gamemode}, other mods' commands ...) is left out
 *       of their tree and fails to parse, matching what the gate enforces.
 * </ul>
 *
 * <p>Both wrappers only ever NARROW the original requirement. If the field cannot be accessed on
 * some runtime, nothing is changed (tree visibility is cosmetic; the lobby gate and the executors
 * remain the enforcement).
 */
public final class CommandTree {

  private CommandTree() {}

  private static final Field REQUIREMENT = findRequirementField();

  private static Field findRequirementField() {
    try {
      Field field = CommandNode.class.getDeclaredField("requirement");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static <S> Predicate<S> requirementOf(CommandNode<S> node) {
    try {
      return REQUIREMENT == null ? null : (Predicate<S>) REQUIREMENT.get(node);
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  private static <S> boolean setRequirement(CommandNode<S> node, Predicate<S> requirement) {
    try {
      if (REQUIREMENT == null) return false;
      REQUIREMENT.set(node, requirement);
      return true;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return false;
    }
  }

  // ------------------------------------------------------------------ pruning

  /** Marker wrapper: original requirement AND "executable here, or a usable child". */
  private static final class UsableBranch<S> implements Predicate<S> {
    final Predicate<S> original;
    final CommandNode<S> node;

    UsableBranch(Predicate<S> original, CommandNode<S> node) {
      this.original = original;
      this.node = node;
    }

    @Override
    public boolean test(S source) {
      if (original != null && !original.test(source)) return false;
      if (node.getCommand() != null || node.getRedirect() != null) return true;
      for (CommandNode<S> child : node.getChildren()) if (child.canUse(source)) return true;
      return false;
    }
  }

  /**
   * Makes every branch of {@code root} (root included) visible only when it leads to something the
   * source may run. Idempotent.
   */
  public static <S> void pruneUnusable(CommandNode<S> root) {
    if (root == null || REQUIREMENT == null) return;
    for (CommandNode<S> child : root.getChildren()) pruneUnusable(child);
    if (root.getChildren().isEmpty()) return;
    Predicate<S> current = requirementOf(root);
    if (current instanceof UsableBranch) return;
    setRequirement(root, new UsableBranch<>(current, root));
  }

  // ------------------------------------------------------------------ execution guard

  private static final Field COMMAND = findCommandField();

  private static Field findCommandField() {
    try {
      Field field = CommandNode.class.getDeclaredField("command");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException | RuntimeException err) {
      return null;
    }
  }

  /**
   * Wraps EVERY executor in {@code node}'s subtree with the state rule: when the rule denies, its
   * message is sent and the command does not run. Several guards may be stacked (e.g. "logged in"
   * on /authcore plus "feature enabled" on one subcommand) - each is checked in turn.
   */
  @SuppressWarnings("unchecked")
  public static void guardExecution(
      CommandNode<CommandSourceStack> node, CommandVisibility.Rule rule) {
    if (node == null || rule == null) return;
    if (COMMAND == null) {
      AuthCoreServer.LOGGER.warn(
          false, "Command guard unavailable on this runtime - executors keep their own checks.");
      return;
    }
    try {
      com.mojang.brigadier.Command<CommandSourceStack> original =
          (com.mojang.brigadier.Command<CommandSourceStack>) COMMAND.get(node);
      if (original != null) {
        com.mojang.brigadier.Command<CommandSourceStack> guarded =
            ctx -> {
              Integer denied = CommandVisibility.guard(ctx.getSource(), rule);
              return denied != null ? denied : original.run(ctx);
            };
        COMMAND.set(node, guarded);
      }
    } catch (ReflectiveOperationException | RuntimeException err) {
      // leave this node unguarded - its executor still re-validates
    }
    for (CommandNode<CommandSourceStack> child : node.getChildren()) guardExecution(child, rule);
  }

  /** {@link #guardExecution} on the named child of {@code parent} (no-op when absent). */
  public static void guardChild(
      CommandNode<CommandSourceStack> parent, String name, CommandVisibility.Rule rule) {
    if (parent != null) guardExecution(parent.getChild(name), rule);
  }

  // ------------------------------------------------------------------ limbo filter

  /** Marker wrapper: original requirement AND "allowed by the lobby command gate when in limbo". */
  private static final class LimboGate implements Predicate<CommandSourceStack> {
    final Predicate<CommandSourceStack> original;
    final String name;

    LimboGate(Predicate<CommandSourceStack> original, String name) {
      this.original = original;
      this.name = name;
    }

    @Override
    public boolean test(CommandSourceStack source) {
      if (!in.potenfyr.authcore.models.Lobby.users.isEmpty() && inLimbo(source)) {
        User user = User.getUser((ServerPlayer) source.getEntity());
        if (!in.potenfyr.authcore.security.Security.isCommandAllowedInLobby(user, name)) return false;
      }
      return original == null || original.test(source);
    }

    private static boolean inLimbo(CommandSourceStack source) {
      try {
        if (!(source.getEntity() instanceof ServerPlayer player)) return false;
        User user = User.getUser(player);
        return user != null && user.isInLobby.get();
      } catch (RuntimeException | LinkageError err) {
        return false;
      }
    }
  }

  /**
   * Wraps every root command of the live dispatcher with the limbo gate (idempotent; new nodes
   * from a datapack /reload or late-registering mods are picked up on the next call).
   */
  public static void applyLimboFilter(CommandDispatcher<CommandSourceStack> dispatcher) {
    if (dispatcher == null || REQUIREMENT == null || AuthCoreServer.config == null) return;
    for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
      Predicate<CommandSourceStack> current = requirementOf(node);
      if (current instanceof LimboGate) continue;
      setRequirement(node, new LimboGate(current, node.getName()));
    }
  }
}
