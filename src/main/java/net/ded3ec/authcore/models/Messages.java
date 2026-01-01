package net.ded3ec.authcore.models;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

// Messages class containing all configurable message templates for the AuthCore mod
@ConfigSerializable
public class Messages {

  @Comment(
      """
            Message shown to a player who has not yet registered.
            • Encourages the player to register using /register <password> <confirm-password>.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserNotRegistered =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "You are not registered on the server. Please register again in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Warning shown when a player attempts to reuse an existing password during registration.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserDuplicatePassword =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Given password already exists!. Please try again";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Success message displayed when a player logs in correctly.
            • Default display: Action bar in GREEN""")
  public ColTemplate promptUserLoggedInSuccessfully =
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
            Error message shown when a player provides an incorrect password during login.
            • Default display: Title in RED""")
  public ColTemplate promptUserWrongPassword =
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
            Message displayed when a player is successfully logged out (e.g., via /logout).
            • Default display: Title with subtitle in GREEN/WHITE""")
  public ColTemplate promptUserLoggedOut =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Successfully Logging You Out!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text = "You will be logged out after 5 seconds!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Warning shown to an already authenticated player who attempts to /register or /login again.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserAlreadyRegistered =
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
            Success message shown after a player successfully registers for the first time.
            • Default display: Action bar in GREEN""")
  public ColTemplate promptUserRegisteredSuccessfully =
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
            Notification sent to administrators when a registered player is currently inactive (not logged in).
            • Default display: Action bar in RED""")
  public ColTemplate promptAdminUserIsNotActive =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "User is not Active in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Notification sent to administrators when a player's session is forcibly destroyed (e.g., via admin command).
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Player username""")
  public ColTemplate promptAdminUserSessionDestroyed =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "User %1$s session has been destroyed and kicked from the Server!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Message sent to administrators listing players of a specific type (e.g., registered, online, etc.).
            • Default display: Chat message in GREEN
            • Placeholders:
              • %1$s - Type/category of players
              • %2$s - Comma-separated list of player names""")
  public ColTemplate promptAdminListOfPlayers =
      new ColTemplate() {
        {
          message =
              new Message() {
                {
                  text = "List of '%1$s' in Authcore: %2$s";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Confirmation message shown to a player after successfully changing their password.
            • Default display: Action bar in GREEN""")
  public ColTemplate promptUserPasswordChangedSuccessfully =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Your password has been changed successfully!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Notification sent to administrators when a player's password has been changed via admin command.
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Player username""")
  public ColTemplate promptAdminUserPasswordChangedSuccessfully =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "User %1$s's password has been changed successfully!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Detailed player information shown to administrators via '/authcore whois <username>' or similar command.
            • Default display: Chat message in GREEN
            • Placeholders:
              • %1$s - Username
              • %2$s - UUID
              • %3$s - Platform type (Java/Bedrock)
              • %4$s - Mode (online/offline)
              • %5$s - IP address
              • %6$s - Status
              • %7$s - Registration date
              • %8$s - Country
              • %9$s - User Creation Time
              • %10$s - Authentication Status""")
  public ColTemplate promptAdminWhoIsUser =
      new ColTemplate() {
        {
          message =
              new Message() {
                {
                  text =
                      "Information about '%1$s':\nUUID: %2$s\nPlatform: %3$s\nMode: %4$s\nIP-Address: %5$s\nStatus: %6$s\nOffline Registered: %7$s\nCountry: %8$s\nuser Created (date): %9$s\nAuthenticated: %10$s";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Confirmation sent to administrators after changing a player's account mode (online/offline).
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Player username
              • %2$s - New mode""")
  public ColTemplate promptAdminChangeUserMode =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "User %1$s's mode has been set %2$s successfully!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Confirmation sent to administrators after updating the limbo/lobby spawn location.
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Dimension
              • %2$s - X Coordinate
              • %3$s - Y Coordinate
              • %4$s - Z Coordinate""")
  public ColTemplate promptAdminSpawnLocationUpdated =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text =
                      "New Spawn Location for Limbo has been configured with World: %1$s | X Coordinate: %2$s | Y Coordinate: %3$s | Z Coordinate: %4$s";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Validation error: The password field was left empty.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserPasswordIsBlank =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Password field <password> cannot be empty!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Prompt shown when a premium account is detected and auto-login succeeds.
            • Default display: Title in GREEN with subtitle
            """)
  public ColTemplate promptUserPremiumAutoLogin =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Premium Account Detected!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text = "Enjoy! Your account has been auto-logged in to the Server!";
                          color = "GREEN";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Prompt shown when an active session is resumed successfully.
            • Default display: Title in GREEN with subtitle
            """)
  public ColTemplate promptUserSessionResumed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Active Session Detected!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text = "Enjoy! Your session has been resumed into the Server!";
                          color = "GREEN";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Validation error: The confirm-password field was left empty.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserConfirmPasswordIsBlank =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "Confirm Password field <confirm-password> cannot be empty!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Validation error: The password and confirmation do not match.
            • Default display: Action bar in RED""")
  public ColTemplate promptUserPasswordDoesNotMatch =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text =
                      "Password <password> and Confirm Password <confirm-password> fields do not match!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Validation error: Password does not contain enough uppercase letters.
            • Default display: Action bar in RED
            • Placeholders:
              • %1$d - Minimum required uppercase letters
              • %2$d - Maximum allowed uppercase letters""")
  public ColTemplate promptUserUpperCaseNotPresent =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "Password must contain between %1$d - %2$d uppercase letter!";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Validation error: Password does not contain enough lowercase letters.
            • Default display: Action bar in RED
            • Placeholders:
              • %1$d - Minimum required lowercase letters
              • %2$d - Maximum allowed lowercase letters""")
  public ColTemplate promptUserLowerCaseNotPresent =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "Password must contain between %1$d - %2$d lowercase letter";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Validation error: Password does not contain enough digits.
            • Default display: Action bar in RED
            • Placeholders:
              • %1$d - Minimum required digits
              • %2$d - Maximum allowed digits""")
  public ColTemplate promptUserDigitNotPresent =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "Password must contain between %1$d - %2$d digits";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Validation error: Password length is outside the allowed range.
            • Default display: Action bar in RED
            • Placeholders:
              • %1$d - Minimum password length
              • %2$d - Maximum password length""")
  public ColTemplate promptUserPasswordLengthIssue =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "Password length should be between %1$d - %2$d!";
                          color = "RED";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Confirmation message sent to administrators after reloading the AuthCore configuration.
            • Default display: Action bar in GREEN""")
  public ColTemplate promptAdminReloadedConfiguration =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "AuthCore configuration files has been reloaded successfully!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to break a block while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserBreakBlockNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to break blocks in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to place or interact with a block while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserUseBlockNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to place blocks in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to use an item while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserUseItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to use items in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to shift-use or move items while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserShiftItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
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
            Restricted mode violation: Player attempted to attack another player while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackPlayerNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack players in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to attack a hostile mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackHostileMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack hostile mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to attack a passive/friendly mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackFriendlyMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack friendly mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to attack an animal while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackAnimalNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack animals in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to attack a neutral mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackNeutralMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to attack neutral mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with another player while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractPlayersNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with players in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with an animal while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractAnimalsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with animals in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with a friendly mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractFriendlyMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with friendly mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to mount or interact with a rideable entity while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractMountableEntityNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "You are not allowed to interact with mountable entities in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with any other entity while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractEntityNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with other entities in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with a neutral mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractNeutralMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with neutral mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to interact with a hostile mob while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserInteractHostileMobsNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to interact with hostile mobs in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to send a chat message while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserChatNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to chat in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to attack another player currently in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserAttackLobbyUserNotAllowed =
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
                          text = "You are not allowed to attack lobby users in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to drop an item while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserDropItemNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to drop items from your Inventory!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to change their game mode while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserChangeGameModeNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to change game mode in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to move while movement is restricted in lobby/limbo.
            • Default display: Title + subtitle (WHITE)""")
  public ColTemplate promptUserPlayerMovementNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to move in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Restricted mode violation: Player attempted to execute a command while in lobby/limbo.
            • Default display: Title + subtitle (WHITE)
            • Placeholders:
              • %1$s - Command name""")
  public ColTemplate promptUserCommandExecutionNotAllowed =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Violation Detected!";
                  color = "RED";
                  subtitle =
                      new Template() {
                        {
                          text = "You are not allowed to use the command '%1$s' in Lobby!";
                          color = "WHITE";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Periodic reminder shown in the action bar to unregistered players in lobby/limbo.
            • Encourages registration.
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Time Left for Timeout!""")
  public ColTemplate promptUserRegisterCommandReminderInterval =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text =
                      "Use the '/register' command to register yourself. You are left with %1$s!";
                  color = "YELLOW";
                }
              };
        }
      };

  @Comment(
      """
            Periodic reminder shown in the action bar to registered but unauthenticated players in lobby/limbo.
            • Encourages login.
            • Default display: Action bar in GREEN
            • Placeholders:
              • %1$s - Time Left for Timeout!""")
  public ColTemplate promptUserLoginCommandReminderInterval =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text =
                      "Use the '/login' command to authenticate yourself. You are left with %1$s!";
                  color = "YELLOW";
                }
              };
        }
      };

  @Comment(
      """
            Welcome message displayed when a player enters the lobby/limbo area.
            • Default display: Title in GREEN""")
  public ColTemplate promptUserWelcomeLobbyUser =
      new ColTemplate() {
        {
          title =
              new Title() {
                {
                  text = "Welcome to the Lobby!";
                  color = "GREEN";
                  subtitle =
                      new Template() {
                        {
                          text =
                              "Use the '/login' OR '/register' command to authenticate yourself!";
                          color = "GREEN";
                        }
                      };
                }
              };
        }
      };

  @Comment(
      """
            Error message shown when a command is used with missing required arguments.
            • Default display: Action bar in RED
            • Placeholders:
              • %1$s - Missing parameter name
              • %2$s - Command name""")
  public ColTemplate promptMissingParameter =
      new ColTemplate() {
        {
          actionBar =
              new ActionBar() {
                {
                  text = "You are missing '%1$s' parameter in '%2$s' command!";
                  color = "RED";
                }
              };
        }
      };

  // ==================== Kick Templates ====================

  @Comment(
      """
            Kick reason: A proxy or VPN connection was detected and blocked.
            • Displayed on the disconnect screen.
            • Delay: 0 seconds""")
  public KickTemplate promptUserProxyNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Proxy connections are not allowed on the server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player logged in from a different IP than their active session.
            • Security protection against session hijacking.
            • Delay: 0 seconds""")
  public KickTemplate promptUserDifferentIpLoginNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Login from a different IP address is not allowed!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
           Kick reason: Player's data has been deleted from the server/database.
           • Request based interaction with User!.
           • Placeholders:
              • %1$s - Data Source (deleted from!)
           • Delay: 0 seconds""")
  public KickTemplate promptUserDataDeleted =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your Data has been deleted! From the %1$s!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player used a premium (paid) username that is restricted on this server.
            • Delay: 0 seconds""")
  public KickTemplate promptUserPremiumNameNotAllowed =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You are not allowed to use an online-mode username!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Bedrock/Floodgate players are not permitted on this Java server.
            • Delay: 0 seconds""")
  public KickTemplate promptUserBedrockPlayersNotAllowed =
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
              Confirmation message shown after a player has been successfully unregistered.
              • Default display: Action bar in GREEN""")
  public KickTemplate promptUserUnRegisteredSuccessfully =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You have been unregistered successfully!";
                  color = "GREEN";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player rejoined too quickly after a previous kick (anti-spam protection).
            • Placeholders:
              • %1$s - Remaining cooldown time
            • Delay: 0 seconds""")
  public KickTemplate promptUserCooldownAfterKickNotExpired =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You cannot login until the %1$s cooldown expires!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Maximum number of players allowed in lobby/limbo has been reached.
            • Delay: 0 seconds""")
  public KickTemplate promptUserMaxLobbyUsersReached =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "The Queue is full. Maximum number of lobby users reached!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player must rejoin after successful registration to log in.
            • Delay: 5 seconds""")
  public KickTemplate promptUserReJoinAfterRegister =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "You must re-join the server and login with your credentials!";
                  color = "GREEN";
                  delaySec = 5;
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player session expired due to inactivity timeout.
            • Delay: 5 seconds""")
  public KickTemplate promptUserSessionExpired =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your session has expired. Please re-login to the server!";
                  color = "WHITE";
                  delaySec = 5;
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Authentication timeout expired (player took too long to /register OR /login).
            • Placeholders:
              • %1$s - Session duration
            • Delay: 5 seconds""")
  public KickTemplate promptUserAuthenticationExpiredTimeout =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Authentication session has been expired after %1$s!";
                  color = "RED";
                  delaySec = 5;
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player account data could not be loaded or found.
            • Delay: 0 seconds""")
  public KickTemplate promptUserNotFoundData =
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
            Kick reason: Targeted player's data could not be found (admin command feedback shown as kick message).
            • Placeholders:
              • %1$s - Username | UUID (Unique ID)
            • Delay: 0 seconds""")
  public KickTemplate promptAdminUserNotFound =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text =
                      "User %1$s's data could not be found. Please tell them to register to the server.";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Player exceeded the maximum allowed login attempts.
            • Places player back into lobby/limbo.
            • Placeholders:
              • %1$d - Maximum allowed attempts
              • %2$s - Cooldown duration
            • Delay: 0 seconds""")
  public KickTemplate promptUserExceededLoginAttempts =
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
            Kick reason: Player was forcibly kicked by an administrator (session destroyed).
            • Delay: 0 seconds""")
  public KickTemplate promptUserKickedByAdmin =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your session has been destroyed by an Admin!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Another client logged in with the same account (concurrent login/session detected).
            • Delay: 0 seconds""")
  public KickTemplate promptUserAnotherAccountSession =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Account with your username is already logged in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason: Another client registering with the same account (concurrent register detected).
            • Delay: 0 seconds""")
  public KickTemplate promptUserAnotherAccountIsRegistering =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Account with your username is already registering in the Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason (shown to admin as feedback): Targeted player's account has been deleted.
            • Placeholders:
              • %1$s - Username
            • Delay: 0 seconds""")
  public KickTemplate promptAdminUserDataDeleted =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "User '%1$s' account has been deleted from the Database & Server!";
                  color = "RED";
                }
              };
        }
      };

  @Comment(
      """
           Kick reason (shown to user as feedback): User's account has been deleted.
           • Placeholders:
             • %1$s - Mode Type (online-mode/offline-mode)
           • Delay: 0 seconds""")
  public KickTemplate promptUserModeUpdated =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text = "Your mode has been changed to %1$s by an Admin!";
                  color = "YELLOW";
                }
              };
        }
      };

  @Comment(
      """
            Kick reason (shown to user as feedback): UUID of the Premium user is different from the database!.
            • Delay: 0 seconds""")
  public KickTemplate promptUserPremiumDifferentUUID =
      new KickTemplate() {
        {
          logout =
              new LogoutTemplate() {
                {
                  text =
                      "Your Authentication Token is invalid! Player with the same name is already present in the server!";
                  color = "RED";
                }
              };
        }
      };

  // ==================== Inner Classes ====================

  @ConfigSerializable
  public static class KickTemplate extends ColTemplate {

    @Comment(
        """
                Message displayed on the player's disconnect/kick screen.
                • Uses LogoutTemplate (plain text only, no title or action bar).
                • delaySec controls how long the message is shown before full disconnect.""")
    public LogoutTemplate logout = new LogoutTemplate();
  }

  @ConfigSerializable
  public static class ColTemplate {

    @Comment(
        """
                Standard chat message sent to the player.
                • Appears in the regular chat window.
                • Supports a delay (in seconds) before sending.""")
    public Message message = new Message();

    @Comment(
        """
                Large centered title message.
                • Can include a subtitle and custom fade timings.""")
    public Title title = new Title();

    @Comment(
        """
                Small message displayed above the hotbar (action bar).
                • Ideal for quick, unobtrusive notifications.""")
    public ActionBar actionBar = new ActionBar();
  }

  @ConfigSerializable
  public static class Message extends Template {

    @Comment(
        """
                Delay in seconds before sending the chat message.
                • Default: 0 (immediate)""")
    public int delay = 0;
  }

  @ConfigSerializable
  public static class Title extends Template {

    @Comment("Optional subtitle shown below the main title.")
    public Template subtitle = new Template();

    @Comment(
        """
                Delay in seconds before the title appears.
                • Default: 0 (immediate)""")
    public int delay = 0;

    @Comment(
        """
                Fade-in duration in seconds.
                • Default: 1 second""")
    public int fadeInSec = 1;

    @Comment(
        """
                Duration the title remains fully visible in seconds.
                • Default: 3 seconds""")
    public int staySec = 3;

    @Comment(
        """
                Fade-out duration in seconds.
                • Default: 1 second""")
    public int fadeOutSec = 1;
  }

  @ConfigSerializable
  public static class ActionBar extends Template {

    @Comment(
        """
                Delay in seconds before showing the action bar message.
                • Default: 0 (immediate)""")
    public int delay = 0;
  }

  @ConfigSerializable
  public static class LogoutTemplate extends Template {

    @Comment(
        """
                Delay in seconds before the client is fully disconnected after displaying the kick message.
                • Allows the player time to read the reason.
                • Default: 0 (immediate disconnect)""")
    public int delaySec = 0;
  }

  @ConfigSerializable
  public static class Template {

    @Comment(
        """
                The text content to display.
                • Example: 'Welcome to the Server!'""")
    public String text = "";

    @Comment(
        """
                Font resources to apply (in order of preference).
                • First available font will be used.
                • Default: ["minecraft", "default"]""")
    public String[] font = new String[] {"minecraft", "default"};

    @Comment(
        """
                Text color.
                • Accepts Minecraft color names (RED, GREEN, etc.) or hex codes (#RRGGBB).
                • Default: GREEN""")
    public String color = "GREEN";

    @Comment(
        """
                Strength of the drop shadow behind the text.
                • Higher values produce a stronger shadow.
                • Default: 10""")
    public int shadowStrength = 10;

    @Comment(
        """
                Whether to render a drop shadow behind the text.
                • Default: true""")
    public boolean shadow = true;

    @Comment("Apply bold styling. Default: false")
    public boolean bold = true;

    @Comment("Apply italic styling. Default: false")
    public boolean italic = false;

    @Comment("Apply underline styling. Default: false")
    public boolean underline = false;

    @Comment("Apply strikethrough styling. Default: false")
    public boolean strikethrough = false;

    @Comment("Apply obfuscated (randomly changing characters) effect. Default: false")
    public boolean obfuscate = false;
  }
}
