package com.smallaswater.npc.dialog;

import cn.lanink.gamecore.form.windows.AdvancedFormWindowDialog;
import cn.lanink.gamecore.utils.packet.ProtocolVersion;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.Config;
import com.smallaswater.npc.RsNPC;
import com.smallaswater.npc.entitys.EntityRsNPC;
import com.smallaswater.npc.utils.Utils;
import com.smallaswater.npc.variable.VariableManage;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author LT_Name
 */
public class DialogPages {

    private final String name;
    private final Config config;

    private String defaultPage;
    private final HashMap<String, DialogPage> dialogPageMap = new HashMap<>();

    public DialogPages(@NotNull String name, @NotNull Config config) {
        this.name = name;
        this.config = config;
        this.load();
    }

    private void load() {
        this.defaultPage = config.getString("defaultPage");
        this.config.getMapList("pages").forEach(page -> {
            try {
                DialogPage dialogPage = new DialogPage(this, page);
                this.dialogPageMap.put(dialogPage.getKey(), dialogPage);
            } catch (Exception e) {
                RsNPC.getInstance().getLogger().error(RsNPC.getInstance().getLanguage().translateString("plugin.load.dialog.dataError", this.name + "." + page.get("key")), e);
            }
        });
        Objects.requireNonNull(getDefaultDialogPage(), "Default dialog page cannot be null");
    }

    public DialogPage getDefaultDialogPage() {
        return this.getDialogPage(this.defaultPage);
    }

    public DialogPage getDialogPage(@NotNull String key) {
        return this.dialogPageMap.get(key);
    }

    public static class DialogPage {

        private final DialogPages dialogPages;
        @Getter
        private final String key;
        private final String title;
        private final String content;
        private final Sound sound;
        private final ArrayList<Button> buttons = new ArrayList<>();

        private String closeGo;

        public DialogPage (@NotNull DialogPages dialogPages, @NotNull Map<String, Object> map) {
            this.dialogPages = dialogPages;
            this.key = (String) map.get("key");
            this.title = (String) map.get("title");
            this.content = (String) map.get("content");
            this.sound = new Sound((Map<String, Object>) map.getOrDefault("sound", new HashMap<>()));
            ((List<Map<String, Object>>) map.get("buttons")).forEach(button -> this.buttons.add(new Button(button)));
            if (map.containsKey("close")) {
                Map<String, Object> closeMap = (Map<String, Object>) map.get("close");
                if (closeMap.containsKey("go")) {
                    this.closeGo = (String) closeMap.get("go");
                }
            }
        }

        public void send(@NotNull EntityRsNPC entityRsNpc, @NotNull Player player) {
            //RsNPC的对话框没有实现编辑界面，创造玩家先转为冒险模式，再发送对话框，最后恢复玩家的游戏模式
            int beforeGameMode = -1;
            if (player.getGamemode() == Player.CREATIVE) {
                beforeGameMode = player.getGamemode();
                player.setGamemode(Player.ADVENTURE);
            }
            final int finalBeforeGameMode = beforeGameMode;

            //1.19.40 有两个关闭按钮，上面的关闭按钮无法监听，这里使用Task延迟处理
            Server.getInstance().getScheduler().scheduleDelayedTask(RsNPC.getInstance(), () -> {
                if (finalBeforeGameMode != -1) {
                    player.setGamemode(finalBeforeGameMode);
                }

                //修复 1.19.40+ 未知原因导致的不显示NPC名称问题
                if (ProtocolInfo.CURRENT_PROTOCOL >= ProtocolVersion.v1_19_40) {
                    String nameTag = entityRsNpc.getNameTag();
                    entityRsNpc.setNameTag("re" + nameTag);
                    entityRsNpc.setNameTag(nameTag);
                }
            }, 5);

            if (this.sound.isEnable() && !"".equals(this.sound.getIdentifier())) {
                Utils.playSound(player, this.sound.getIdentifier());
            }

            AdvancedFormWindowDialog windowDialog = new AdvancedFormWindowDialog(
                    VariableManage.stringReplace(player, this.title, entityRsNpc.getConfig()),
                    VariableManage.stringReplace(player, this.content, entityRsNpc.getConfig()),
                    entityRsNpc
            );

            windowDialog.setSkinData("{\"picker_offsets\":{\"scale\":[1.75,1.75,1.75],\"translate\":[0,0,0]},\"portrait_offsets\":{\"scale\":[1.75,1.75,1.75],\"translate\":[0,-50,0]}}");

            this.buttons.forEach(button -> {
                windowDialog.addAdvancedButton(button.getText()).onClicked((p, response) -> {
                    for (Button.ButtonAction buttonAction : button.getButtonActions()) {
                        if (buttonAction.getType() == Button.ButtonActionType.ACTION_CLOSE) {
                            windowDialog.close(p, response);
                        } else if (buttonAction.getType() == Button.ButtonActionType.GOTO) {
                            dialogPages.getDialogPage(buttonAction.getData()).send(entityRsNpc, player);
                        } else if (buttonAction.getType() == Button.ButtonActionType.EXECUTE_COMMAND) {
                            Server.getInstance().getScheduler().scheduleDelayedTask(RsNPC.getInstance(), () -> {
                                Utils.executeCommand(p, entityRsNpc.getConfig(), buttonAction.getListData());
                            }, 10);
                        }

                        if (button.getSound().isEnable() && !"".equals(button.getSound().getIdentifier())) {
                            Utils.playSound(player, button.getSound().getIdentifier());
                        }

                        //TODO 其他点击操作
                    }
                });
            });

            windowDialog.onClosed((p, response) -> {
                if (this.closeGo != null) {
                    this.dialogPages.getDialogPage(this.closeGo).send(entityRsNpc, player);
                }
            });

            windowDialog.send(player);
        }

        @Getter
        public static class Sound {

            private final boolean enable;
            private final String identifier;

            public Sound() {
                this.enable = false;
                this.identifier = "";
            }

            public Sound(@NotNull Map<String, Object> map) {
                this.enable = (boolean) map.getOrDefault("enable", false);
                this.identifier = (String) map.getOrDefault("identifier", "");
            }
        }

        @Getter
        public static class Button {

            private final String text;

            private final List<ButtonAction> buttonActions = new ArrayList<>();

            private final Sound sound;

            public Button(@NotNull Map<String, Object> map) {
                this.text = (String) map.get("text");
                if (map.containsKey("action")) {
                    ButtonAction buttonAction = new ButtonAction(ButtonActionType.ACTION, String.valueOf(map.get("action")));
                    if ("close".equalsIgnoreCase(buttonAction.getData())) {
                        buttonAction.setType(ButtonActionType.ACTION_CLOSE);
                    }
                    this.buttonActions.add(buttonAction);
                }
                if (map.containsKey("go")) {
                    ButtonAction buttonAction = new ButtonAction(ButtonActionType.GOTO, String.valueOf(map.get("go")));
                    this.buttonActions.add(buttonAction);
                }
                if (map.containsKey("cmd")) {
                    ButtonAction buttonAction = new ButtonAction(ButtonActionType.EXECUTE_COMMAND);
                    buttonAction.getListData().clear();
                    buttonAction.getListData().addAll((List<String>) map.get("cmd"));
                    this.buttonActions.add(buttonAction);
                }

                if (this.buttonActions.isEmpty()) {
                    this.buttonActions.add(new ButtonAction(ButtonActionType.ACTION_CLOSE));
                }

                if (map.containsKey("sound")) {
                    this.sound = new Sound((Map<String, Object>) map.get("sound"));
                } else {
                    this.sound = new Sound();
                }
            }

            @Setter
            @Getter
            public static class ButtonAction {

                private ButtonActionType type;

                private String data;

                private List<String> listData = new ArrayList<>();

                public ButtonAction(@NotNull ButtonActionType type) {
                    this(type, null);
                }

                public ButtonAction(@NotNull ButtonActionType type, String data) {
                    this.type = type;
                    this.data = data;
                }

            }

            public enum ButtonActionType {
                ACTION,
                ACTION_CLOSE,
                GOTO,
                EXECUTE_COMMAND,
                ;
            }
        }

    }

}
