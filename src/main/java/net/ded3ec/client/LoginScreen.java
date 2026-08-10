package net.ded3ec.client;

/*? if fabric {*/

import net.minecraft.client.Minecraft;
/*? if < 1.19.4 {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.TextComponent;
*//*?} else if < 26 {*/
import net.minecraft.client.gui.GuiGraphics;
/*?} else {*/
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*//*?}*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
/*? if >= 1.20.2 {*/
import net.minecraft.client.multiplayer.TransferState;
/*?}*/
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Custom AuthCore login screen (client companion) - Minecraft 26.2 (unobfuscated). */
public class LoginScreen extends Screen {

  private final Screen parent;
  private final ServerAddress address;
  private final ServerData info;
  private final boolean quickPlay;
  /*? if >= 1.20.2 {*/
  private final TransferState cookieStorage;
  /*?}*/

  private EditBox usernameField;
  private EditBox passwordField;

  public LoginScreen(
      Screen parent, ServerAddress address, ServerData info, boolean quickPlay,
      /*? if >= 1.20.2 {*/
      TransferState cookieStorage) {
      /*?} else {*/
      /*Object cookieStorage) {
      *//*?}*/
    super(text("AuthCore Login"));
    this.parent = parent;
    this.address = address;
    this.info = info;
    this.quickPlay = quickPlay;
    /*? if >= 1.20.2 {*/
    this.cookieStorage = cookieStorage;
    /*?}*/
  }

  /** Component.literal() on 1.19.4+, TextComponent before that. */
  private static Component text(String value) {
    /*? if < 1.19.4 {*/
    /*return new TextComponent(value);
    *//*?} else {*/
    return Component.literal(value);
    /*?}*/
  }

  @Override
  protected void init() {
    int centerX = this.width / 2;
    this.usernameField = new EditBox(this.font, centerX - 100, this.height / 2 - 50, 200, 20, text("Username"));
    this.usernameField.setMaxLength(32);
    this.usernameField.setValue(Minecraft.getInstance().getUser().getName());
    this.passwordField = new EditBox(this.font, centerX - 100, this.height / 2 - 20, 200, 20, text("Password"));

    /*? if < 1.19.4 {*/
    /*this.addRenderableWidget(new Button(centerX - 100, this.height / 2 + 15, 95, 20, text("Login"), b -> connect(false)));
    this.addRenderableWidget(new Button(centerX + 5, this.height / 2 + 15, 95, 20, text("Register"), b -> connect(true)));
    this.addRenderableWidget(new Button(centerX - 100, this.height / 2 + 45, 200, 20, text("Back"), b -> this.minecraft.setScreen(this.parent)));
    *//*?} else {*/
    this.addRenderableWidget(Button.builder(text("Login"), b -> connect(false))
        .bounds(centerX - 100, this.height / 2 + 15, 95, 20).build());
    this.addRenderableWidget(Button.builder(text("Register"), b -> connect(true))
        .bounds(centerX + 5, this.height / 2 + 15, 95, 20).build());
    this.addRenderableWidget(Button.builder(text("Back"), b -> this.minecraft.setScreenAndShow(this.parent))
        .bounds(centerX - 100, this.height / 2 + 45, 200, 20).build());
    /*?}*/

    this.addWidget(this.usernameField);
    this.addWidget(this.passwordField);
    this.setInitialFocus(this.usernameField);
  }

  private void connect(boolean registerMode) {
    String username = this.usernameField.getValue().trim();
    String password = this.passwordField.getValue();
    if (username.isEmpty() || password.isEmpty()) return;

    ClientAuthCore.pending = new ClientAuthCore.PendingLogin(username, password, registerMode);
    /*? if < 1.20.2 {*/
    /*ConnectScreen.startConnecting(this.parent, this.minecraft, this.address, this.info);
    *//*?} else {*/
    ConnectScreen.startConnecting(this.parent, this.minecraft, this.address, this.info, this.quickPlay, this.cookieStorage);
    /*?}*/
  }

  @Override
  /*? if < 1.19.4 {*/
  /*public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
    this.renderBackground(poseStack);
    this.drawCenteredString(poseStack, this.font, text("AuthCore Login"), this.width / 2, this.height / 2 - 90, ClientAuthCore.themeTitleColor);
    this.drawCenteredString(poseStack, this.font, text("Server: " + this.address.getHost()), this.width / 2, this.height / 2 - 72, ClientAuthCore.themeSubtitleColor);
    this.drawCenteredString(poseStack, this.font, text("Username"), this.width / 2, this.height / 2 - 65, ClientAuthCore.themeLabelColor);
    this.drawCenteredString(poseStack, this.font, text("Password"), this.width / 2, this.height / 2 - 35, ClientAuthCore.themeLabelColor);
    super.render(poseStack, mouseX, mouseY, delta);
    *//*?} else if < 26 {*/
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    this.renderBackground(graphics, mouseX, mouseY, delta);
    graphics.drawCenteredString(this.font, text("AuthCore Login"), this.width / 2, this.height / 2 - 90, ClientAuthCore.themeTitleColor);
    graphics.drawCenteredString(this.font, text("Server: " + this.address.getHost()), this.width / 2, this.height / 2 - 72, ClientAuthCore.themeSubtitleColor);
    graphics.drawCenteredString(this.font, text("Username"), this.width / 2, this.height / 2 - 65, ClientAuthCore.themeLabelColor);
    graphics.drawCenteredString(this.font, text("Password"), this.width / 2, this.height / 2 - 35, ClientAuthCore.themeLabelColor);
    super.render(graphics, mouseX, mouseY, delta);
    /*?} else {*/
    /*public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    this.extractBackground(graphics, mouseX, mouseY, delta);
    graphics.centeredText(this.font, text("AuthCore Login"), this.width / 2, this.height / 2 - 90, ClientAuthCore.themeTitleColor);
    graphics.centeredText(this.font, text("Server: " + this.address.getHost()), this.width / 2, this.height / 2 - 72, ClientAuthCore.themeSubtitleColor);
    graphics.centeredText(this.font, text("Username"), this.width / 2, this.height / 2 - 65, ClientAuthCore.themeLabelColor);
    graphics.centeredText(this.font, text("Password"), this.width / 2, this.height / 2 - 35, ClientAuthCore.themeLabelColor);
    super.extractRenderState(graphics, mouseX, mouseY, delta);
    *//*?}*/
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
/*?}*/
