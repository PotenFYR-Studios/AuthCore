package net.ded3ec.authcore.models;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

// Messages class containing all configurable message templates for the AuthCore mod
@ConfigSerializable
public class Messages {

  @Comment(
      """

            Message shown when no user data is found for the player.
            • Typically displayed during login attempts when the player has no stored account.
            • Default: ActionBar in RED""")
  public KickTemplate userNotFoundData =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your data could not be found. Please re-login to the server.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when the player exceeds the maximum allowed login attempts.
            • Triggers Lobby/restricted mode.
            • Default: ActionBar in RED
            • %1$d - Max Login Attempts number.
            • %2$s - Cooldown Time expires""")
  public KickTemplate exceededLoginAttempts =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text =
                      "You have exceeded the maximum login attempts (%1$d). Please re-join the server after %2$s.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

                  Concurrent Login not allowed message on kick.
                  • Default: Message in RED
                  • %1$s - Username Information""")
  public KickTemplate anotherAccountLoggedIn =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "An account with username '%1$s' has already joined the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when the player is not registered yet.
            • Prompts the player to use /register.
            • Default: ActionBar in RED
            • %1$s - Username Information""")
  public ColTemplate userNotRegistered =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text =
                      "'%1$s' is not registered on the server. Please register again in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

                  Password resuse restriction message
                  • Default: ActionBar in RED
                  • %1$s - Password""")
  public ColTemplate duplicatePassword =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Given password '%1$s' already exists!. Please try again";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when the player is not registered yet.
            • Prompts the player to use /register.
            • Default: ActionBar in RED
            • %1$s - Username Information""")
  public ColTemplate userNotFound =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "User Not Found!";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "'%1$s' is not registered on the server. Please re-join the server";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Success message shown when the player successfully logs in.
            • Default: Title in GREEN""")
  public ColTemplate userLoggedIn =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Welcome to the Server!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when the player enters an incorrect password.
            • Default: Title in RED""")
  public ColTemplate wrongPassword =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Incorrect Password!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when the player successfully logs out.
            • Default: Title in RED""")
  public ColTemplate userLoggedOut =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Successfully Logged Out!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Message shown when an already authenticated player tries to register or login again.
            • Default: ActionBar in RED""")
  public ColTemplate userAlreadyRegistered =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "You are already registered on the server.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Success message shown after a player successfully registers.
            • Default: ActionBar in GREEN""")
  public ColTemplate userRegistered =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "You are now Registered!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

         Password Change Confirmation message.
         • Default: ActionBar in GREEN
         • %1$s - Username Information""")
  public ColTemplate passwordChanged =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "User '%1$s' password has been changed!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password field is blank.
            • Default: ActionBar in RED""")
  public ColTemplate passwordIsBlank =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password cannot be empty";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Confirm password field is blank.
            • Default: ActionBar in RED""")
  public ColTemplate confirmPasswordIsBlank =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Confirm Password cannot be empty";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password and confirmation do not match.
            • Default: ActionBar in RED""")
  public ColTemplate passwordDoesNotMatch =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password and Confirm Password do not match!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password missing uppercase letter.
            • Default: ActionBar in RED
            • %1$d - Minimum Uppercase count required
            • %2$d - Maximum Uppercase count required""")
  public ColTemplate upperCaseNotPresent =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password must contain between %1$d - %2$d uppercase letter!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password missing lowercase letter.
            • Default: ActionBar in RED
            • %1$d - Minimum lowercase count required
            • %2$d - Maximum lowercase count required""")
  public ColTemplate lowerCaseNotPresent =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password must contain between %1$d - %2$d lowercase letter";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password missing digit.
            • Default: ActionBar in RED
            • %1$d - Minimum digit count required
            • %2$d - Maximum digit count required""")
  public ColTemplate digitNotPresent =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password must contain between %1$d - %2$d digits";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Validation error: Password is too short.
            • Default: ActionBar in RED
            • %1$d - Minimum Password length required
            • %2$d - Maximum Password length required""")
  public ColTemplate PasswordLengthIssue =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password length should be between %1$d - %2$d!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Confirmation message sent when the configuration is reloaded.
            • Default: Chat message in GREEN""")
  public ColTemplate reloadedConfiguration =
      new ColTemplate() {
        {
          message =
              new Message() {
                {
                  text = "AuthCore configuration has been reloaded";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

            Main title shown when a player enters restricted/jail mode.
            • Used as the primary title for most jail restriction messages.
            • Default: Title in RED""")
  public ColTemplate restrictedMode =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode enabled";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to break a block.
            • Title + Subtitle (WHITE)""")
  public ColTemplate breakBlockNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to break blocks in Lobby";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to use/interact with a block.
            • Title + Subtitle (WHITE)""")
  public ColTemplate useBlockNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to place blocks in Lobby";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to use an item.
            • Title + Subtitle (WHITE)""")
  public ColTemplate useItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to use items in Lobby";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to shift-click (sneak + use item).
            • Title + Subtitle (WHITE)""")
  public ColTemplate shiftItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to move items in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack another player.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackPlayerNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack players in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack a hostile mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackHostileMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack hostile mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack a friendly/passive mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackFriendlyMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack friendly mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack an animal.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackAnimalNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack animals in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack a neutral mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackNeutralMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack neutral mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with another player.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactPlayersNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with players in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with an animal.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactAnimalsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with animals in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with a friendly mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactFriendlyMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with friendly mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with mountable entities.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactMountableEntityNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "You are not allowed to interact with mountable entities in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with other entities.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactEntityNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with other entities in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with a neutral mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactNeutralMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with neutral mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to interact with a hostile mob.
            • Title + Subtitle (WHITE)""")
  public ColTemplate interactHostileMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with hostile mobs in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to chat.
            • Title + Subtitle (WHITE)""")
  public ColTemplate chatNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to chat in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Message shown when a jailed player is not allowed to perform certain actions.
            • Standalone title (used in specific contexts).""")
  public ColTemplate userNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "You are not Allowed.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to attack another jailed player.
            • Title + Subtitle (WHITE)""")
  public ColTemplate attackJailedUserNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "You are not Allowed.";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack jailed users in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to drop an item.
            • Title + Subtitle (WHITE)""")
  public ColTemplate dropItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to drop items from your Inventory.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to change game mode.
            • Title + Subtitle (WHITE)""")
  public ColTemplate changeGameModeNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to change game mode in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to move.
            • Title + Subtitle (WHITE)""")
  public ColTemplate playerMovementNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to move in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Restricted mode: Player attempted to execute a command.
            • Title + Subtitle (WHITE)
            • %1$s - Command Name""")
  public ColTemplate commandExecutionNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Restricted Mode!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to use the command '%1$s' in Lobby.";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

            Periodic reminder shown in action bar to unregistered players in jail.
            • Encourages using /register.
            • Default: ActionBar in GREEN""")
  public ColTemplate registerCommandReminderInterval =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Use the '/register' command to register yourself.";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

            Periodic reminder shown in action bar to unauthenticated players in jail.
            • Encourages using /login.
            • Default: ActionBar in GREEN""")
  public ColTemplate loginCommandReminderInterval =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Use the '/login' command to authenticate yourself.";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

            Welcome message shown to players when they enter jail/restricted mode.
            • Default: ActionBar in GREEN""")
  public ColTemplate welcomeJailUser =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Welcome to Restricted Mode.";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """

                  When user is already in the same mode.
                  • Default: ActionBar in RED
                  • %1$s Username Placeholder
                  • %2$s Account mode [online-mode/offline-mode]""")
  public ColTemplate userIsInSameMode =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Player '%1$s' are already in the '%2$s' mode.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

                        Prompt for successful transfer to cracked account.""")
  public ColTemplate transferredToCrackedAccount =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Transferred to Cracked Account!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "Notice: Your account has been transfer to offline-mode. Please login with the new password you provided!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

           Prompt for successful transfer to premium account.""")
  public ColTemplate transferredToPremiumAccount =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Transferred to Premium Account!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "WARNING: Your account has been transfer to online-mode. You won't be able to login with credentials and account will be treated as online account!";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """

           If Username isn't from the Premium account.
           • %1$s Username Placeholder""")
  public ColTemplate usernameIsNotPremium =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Your username '%1$s' isn't a online-mod account!";
                  color = "RED";
                }
              };
        }
      };

  // ==================== Kick Templates ====================

  @Comment(
      """

            Kick reason: Proxy/VPN login detected and disallowed.
            • Shown on disconnect screen with delay 0.""")
  public KickTemplate proxyNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Proxy connections are not allowed on the server.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player tried to login from multiple locations simultaneously.
            • Shown on disconnect screen with delay 0.""")
  public KickTemplate duplicateLoginNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Duplicate logins are not allowed on the server.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player logged in from a different IP than their session.
            • Security feature.""")
  public KickTemplate differentIpLoginNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Login from a different IP address is not allowed.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player using a premium (paid) Minecraft name that is restricted.""")
  public KickTemplate premiumNameNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You are not allowed to use an online-mode username.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Bedrock players are not allowed.""")
  public KickTemplate bedrockPlayersNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Bedrock Players are not allowed in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player tried to rejoin too soon after being kicked.
            • %1$s - Time remaining""")
  public KickTemplate cooldownAfterKickNotExpired =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You cannot login until the %1$s cooldown expires.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Maximum number of jailed players reached.""")
  public KickTemplate maxJailedUsersReached =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "The Queue is full. Maximum number of jailed users reached.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player must rejoin after successful registration.
            • Delay: 5 seconds""")
  public KickTemplate reJoinAfterRegister =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You must re-join the server and login with your credentials.";
                  color = "GREEN";
                  delaySec = 5;
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Player session has expired (inactivity timeout).
            • Delay: 5 seconds""")
  public KickTemplate sessionExpired =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your session has expired. Please re-login to the server.";
                  color = "WHITE";
                  delaySec = 5;
                }
              };
        }
      };

  @Comment(
      """

            Kick reason: Authentication timeout expired (took too long to login/register).
            • Delay: 5 seconds
            • %1$s - Session Time""")
  public KickTemplate authenticationTimeoutExpired =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Authentication session has been expired after %1$s.";
                  color = "RED";
                  delaySec = 5;
                }
              };
        }
      };

  // ==================== Inner Classes ====================

  @ConfigSerializable
  public static class KickTemplate extends ColTemplate {

    @Comment(
        """

                The message shown on the disconnect/kick screen.
                • Uses a LogoutTemplate (no title/actionbar, only logout message).
                • Delay controls how long the message stays visible before the client fully disconnects.""")
    public LogoutTemplate logout = new LogoutTemplate();
  }

  @ConfigSerializable
  public static class ColTemplate {

    @Comment(
        """

                Standard chat message sent to the player.
                • Appears in the chat box.
                • Supports delay (in ticks) before sending.""")
    public Message message = new Message();

    @Comment(
        """

                Title message displayed in the center of the screen.
                • Can include a subtitle and custom fade-in/stay/fade-out timings.""")
    public Title title = new Title();

    @Comment(
        """

                Action bar message displayed above the hotbar.
                • Quick, non-intrusive feedback.""")
    public ActionBar actionBar = new ActionBar();
  }

  @ConfigSerializable
  public static class Message extends Template {

    @Comment(
        """

                Delay (in seconds) before the chat message is sent.
                • Default: 0 (immediate)""")
    public int delay = 0;
  }

  @ConfigSerializable
  public static class Title extends Template {

    @Comment("Optional subtitle displayed below the main title.")
    public Template subtitle = new Template();

    @Comment(
        """

                Delay (in seconds) before the title is shown.
                • Default: 0 (immediate)""")
    public int delay = 0;

    @Comment(
        """

                Fade-in time for the title (in seconds).
                • Default: 1 second""")
    public int fadeInSec = 1;

    @Comment(
        """

                How long the title stays fully visible (in seconds).
                • Default: 3 seconds""")
    public int staySec = 3;

    @Comment(
        """

                Fade-out time for the title (in seconds).
                • Default: 1 second""")
    public int fadeOutSec = 1;
  }

  @ConfigSerializable
  public static class ActionBar extends Template {

    @Comment(
        """

                Delay (in seconds) before the action bar message appears.
                • Default: 0 (immediate)""")
    public int delay = 0;
  }

  @ConfigSerializable
  public static class LogoutTemplate extends Template {

    @Comment(
        """

                Delay (in seconds) before the client is fully disconnected after showing the kick message.
                • Gives the player time to read the reason.
                • Default: 0 (immediate disconnect)""")
    public int delaySec = 0;
  }

  @ConfigSerializable
  public static class Template {

    @Comment(
        """

                Text to display via multiple adapters..
                • Example: 'Welcome to the Server!'""")
    public String text = "";

    @Comment(
        """

                Fonts to apply to the text.
                • First available font in the list will be used.
                • Default: ["minecraft", "default"] (falls back to vanilla font)""")
    public String[] font = new String[] {"minecraft", "default"};

    @Comment(
        """

                Text color.
                • Supports Minecraft color names (e.g., "RED", "GREEN") or hex (#RRGGBB).
                • Default: GREEN""")
    public String color = "GREEN";

    @Comment(
        """

                Strength of the text shadow/drop effect.
                • Higher values = stronger shadow.
                • Default: 10""")
    public int shadowStrength = 10;

    @Comment(
        """

                Whether to render a shadow behind the text.
                • Default: true""")
    public boolean shadow = true;

    @Comment(
        """

                Bold text styling.
                • Default: false""")
    public boolean bold = false;

    @Comment(
        """

                Italic text styling.
                • Default: false""")
    public boolean italic = false;

    @Comment(
        """

                Underlined text styling.
                • Default: false""")
    public boolean underline = false;

    @Comment(
        """

                Strikethrough text styling.
                • Default: false""")
    public boolean strikethrough = false;

    @Comment(
        """

                Obfuscated/magic text (random characters).
                • Default: false""")
    public boolean obfuscate = false;
  }
}
