package net.ded3ec.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

/** Custom AuthCore login screen (client companion). */
public class LoginScreen extends Screen {

  private final Screen parent;
  private final ServerAddress address;
  private final ServerInfo info;
  private final boolean quickPlay;
  private final CookieStorage cookieStorage;

  private TextFieldWidget usernameField;
  private TextFieldWidget passwordField;

  public LoginScreen(
      Screen parent, ServerAddress address, ServerInfo info, boolean quickPlay, CookieStorage cookieStorage) {
    super(Text.literal("AuthCore Login"));
    this.parent = parent;
    this.address = address;
    this.info = info;
    this.quickPlay = quickPlay;
    this.cookieStorage = cookieStorage;
  }

  @Override
  protected void init() {
    int centerX = this.width / 2;
    this.usernameField = new TextFieldWidget(this.textRenderer, centerX - 100, this.height / 2 - 50, 200, 20, Text.literal("Username"));
    this.usernameField.setMaxLength(32);
    this.usernameField.setText(MinecraftClient.getInstance().getSession().getUsername());
    this.passwordField = new TextFieldWidget(this.textRenderer, centerX - 100, this.height / 2 - 20, 200, 20, Text.literal("Password"));

    this.addDrawableChild(ButtonWidget.builder(Text.literal("Login"), b -> connect(false))
        .dimensions(centerX - 100, this.height / 2 + 15, 95, 20).build());
    this.addDrawableChild(ButtonWidget.builder(Text.literal("Register"), b -> connect(true))
        .dimensions(centerX + 5, this.height / 2 + 15, 95, 20).build());
    this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.client.setScreen(this.parent))
        .dimensions(centerX - 100, this.height / 2 + 45, 200, 20).build());

    this.addSelectableChild(this.usernameField);
    this.addSelectableChild(this.passwordField);
    this.setInitialFocus(this.usernameField);
  }

  private void connect(boolean registerMode) {
    String username = this.usernameField.getText().trim();
    String password = this.passwordField.getText();
    if (username.isEmpty() || password.isEmpty()) return;

    ClientAuthCore.pending = new ClientAuthCore.PendingLogin(username, password, registerMode);
    ConnectScreen.connect(this.parent, this.client, this.address, this.info, this.quickPlay, this.cookieStorage);
  }

  @Override
  public void render(DrawContext context, int mouseX, int mouseY, float delta) {
    this.renderBackground(context, mouseX, mouseY, delta);
    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("AuthCore Login"), this.width / 2, this.height / 2 - 90, ClientAuthCore.themeTitleColor);
    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Server: " + this.address.getAddress()), this.width / 2, this.height / 2 - 72, ClientAuthCore.themeSubtitleColor);
    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Username"), this.width / 2, this.height / 2 - 65, ClientAuthCore.themeLabelColor);
    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Password"), this.width / 2, this.height / 2 - 35, ClientAuthCore.themeLabelColor);
    this.usernameField.render(context, mouseX, mouseY, delta);
    this.passwordField.render(context, mouseX, mouseY, delta);
    super.render(context, mouseX, mouseY, delta);
  }

  @Override
  public boolean shouldPause() {
    return false;
  }
}