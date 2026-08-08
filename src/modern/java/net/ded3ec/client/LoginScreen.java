package net.ded3ec.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Custom AuthCore login screen (client companion) - Minecraft 26.2 (unobfuscated). */
public class LoginScreen extends Screen {

  private final Screen parent;
  private final ServerAddress address;
  private final ServerData info;
  private final boolean quickPlay;
  private final TransferState cookieStorage;

  private EditBox usernameField;
  private EditBox passwordField;

  public LoginScreen(
      Screen parent, ServerAddress address, ServerData info, boolean quickPlay, TransferState cookieStorage) {
    super(Component.literal("AuthCore Login"));
    this.parent = parent;
    this.address = address;
    this.info = info;
    this.quickPlay = quickPlay;
    this.cookieStorage = cookieStorage;
  }

  @Override
  protected void init() {
    int centerX = this.width / 2;
    this.usernameField = new EditBox(this.font, centerX - 100, this.height / 2 - 50, 200, 20, Component.literal("Username"));
    this.usernameField.setMaxLength(32);
    this.usernameField.setValue(Minecraft.getInstance().getUser().getName());
    this.passwordField = new EditBox(this.font, centerX - 100, this.height / 2 - 20, 200, 20, Component.literal("Password"));

    this.addRenderableWidget(Button.builder(Component.literal("Login"), b -> connect(false))
        .bounds(centerX - 100, this.height / 2 + 15, 95, 20).build());
    this.addRenderableWidget(Button.builder(Component.literal("Register"), b -> connect(true))
        .bounds(centerX + 5, this.height / 2 + 15, 95, 20).build());
    this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(this.parent))
        .bounds(centerX - 100, this.height / 2 + 45, 200, 20).build());

    this.addWidget(this.usernameField);
    this.addWidget(this.passwordField);
    this.setInitialFocus(this.usernameField);
  }

  private void connect(boolean registerMode) {
    String username = this.usernameField.getValue().trim();
    String password = this.passwordField.getValue();
    if (username.isEmpty() || password.isEmpty()) return;

    ClientAuthCore.pending = new ClientAuthCore.PendingLogin(username, password, registerMode);
    ConnectScreen.startConnecting(this.parent, this.minecraft, this.address, this.info, this.quickPlay, this.cookieStorage);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    this.extractBackground(graphics, mouseX, mouseY, delta);
    graphics.centeredText(this.font, Component.literal("AuthCore Login"), this.width / 2, this.height / 2 - 90, ClientAuthCore.themeTitleColor);
    graphics.centeredText(this.font, Component.literal("Server: " + this.address.getHost()), this.width / 2, this.height / 2 - 72, ClientAuthCore.themeSubtitleColor);
    graphics.centeredText(this.font, Component.literal("Username"), this.width / 2, this.height / 2 - 65, ClientAuthCore.themeLabelColor);
    graphics.centeredText(this.font, Component.literal("Password"), this.width / 2, this.height / 2 - 35, ClientAuthCore.themeLabelColor);
    super.extractRenderState(graphics, mouseX, mouseY, delta);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
