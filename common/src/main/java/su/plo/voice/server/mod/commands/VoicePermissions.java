package su.plo.voice.server.mod.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.players.PlayerList;
import su.plo.voice.api.player.VoicePlayer;
import su.plo.voice.server.VoiceServer;
import su.plo.voice.server.config.Configuration;
import su.plo.voice.server.mod.player.PlayerManagerMod;

import java.util.ArrayList;
import java.util.Map;

public class VoicePermissions {
    private static RequiredArgumentBuilder<CommandSourceStack, String> permissions() {
        return Commands.argument("permission", StringArgumentType.word()).suggests((ctx, builder) -> {
            Configuration section = VoiceServer.getInstance().getConfig().getSection("permissions");
            return SharedSuggestionProvider.suggest(new ArrayList<>(section.getKeys()), builder);
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // vperms <target> <set/unset/check> <permission> [boolean]
        dispatcher.register(Commands.literal("vperms")
                .requires(source ->
                        CommandManager.requiresPermission(source, "voice.permissions")
                )
                .then(Commands.argument("targets", GameProfileArgument.gameProfile()).suggests((commandContext, suggestionsBuilder) -> {
                            PlayerList playerList = commandContext.getSource().getServer().getPlayerList();
                            return SharedSuggestionProvider.suggest(playerList.getPlayers().stream()
                                    .map((serverPlayer) -> serverPlayer.getGameProfile().getName()), suggestionsBuilder);
                        })
                        .then(Commands.literal("set")
                                .then(permissions()
                                        .executes(ctx -> {
                                            GameProfileArgument.getGameProfiles(ctx, "targets").forEach(gameProfile -> {
                                                set(
                                                        ctx,
                                                        gameProfile,
                                                        StringArgumentType.getString(ctx, "permission"),
                                                        true
                                                );
                                            });
                                            return 1;
                                        })
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    GameProfileArgument.getGameProfiles(ctx, "targets").forEach(gameProfile -> {
                                                        set(
                                                                ctx,
                                                                gameProfile,
                                                                StringArgumentType.getString(ctx, "permission"),
                                                                BoolArgumentType.getBool(ctx, "value")
                                                        );
                                                    });
                                                    return 1;
                                                }))
                                )
                        )
                        .then(Commands.literal("unset")
                                .then(permissions()
                                        .executes(ctx -> {
                                            GameProfileArgument.getGameProfiles(ctx, "targets").forEach(gameProfile -> {
                                                unset(
                                                        ctx,
                                                        gameProfile,
                                                        StringArgumentType.getString(ctx, "permission")
                                                );
                                            });
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("check")
                                .then(permissions()
                                        .executes(ctx -> {
                                            GameProfileArgument.getGameProfiles(ctx, "targets").forEach(gameProfile -> {
                                                check(
                                                        ctx,
                                                        gameProfile,
                                                        StringArgumentType.getString(ctx, "permission")
                                                );
                                            });
                                            return 1;
                                        })
                                )
                        )
                )
        );
    }

    private static void check(CommandContext<CommandSourceStack> ctx, GameProfile profile, String permission) {
        Configuration section = VoiceServer.getInstance().getConfig().getSection("permissions");
        if (!section.getKeys().contains(permission)) {
            ctx.getSource().sendFailure(new TextComponent(VoiceServer.getInstance().getMessagePrefix("permissions.not_found")));
            return;
        }

        PlayerManagerMod playerManager = (PlayerManagerMod) VoiceServer.getAPI().getPlayerManager();
        VoicePlayer player = playerManager.getByUniqueId(profile.getId());

        ctx.getSource().sendSuccess(
                new TextComponent(
                        VoiceServer.getInstance().getMessagePrefix("permissions.check")
                                .replace("{player}", profile.getName())
                                .replace("{permission}", permission)
                                .replace("{value}", String.valueOf(player.hasPermission(permission)))
                ),
                false
        );
    }

    private static void set(CommandContext<CommandSourceStack> ctx, GameProfile profile, String permission, boolean value) {
        Configuration section = VoiceServer.getInstance().getConfig().getSection("permissions");
        if (!section.getKeys().contains(permission)) {
            ctx.getSource().sendFailure(new TextComponent(VoiceServer.getInstance().getMessagePrefix("permissions.not_found")));
            return;
        }

        PlayerManagerMod playerManager = (PlayerManagerMod) VoiceServer.getAPI().getPlayerManager();
        VoicePlayer player = playerManager.getByUniqueId(profile.getId());

        if (playerManager.hasPermission(player.getUniqueId(), permission) == value) {
            ctx.getSource().sendFailure(
                    new TextComponent(
                            VoiceServer.getInstance().getMessagePrefix("permissions.already")
                                    .replace("{player}", profile.getName())
                                    .replace("{permission}", permission)
                                    .replace("{value}", String.valueOf(value))
                    )
            );
            return;
        }

        playerManager.setPermission(player, permission, value);
        ctx.getSource().sendSuccess(
                new TextComponent(
                        VoiceServer.getInstance().getMessagePrefix("permissions.set")
                                .replace("{player}", profile.getName())
                                .replace("{permission}", permission)
                                .replace("{value}", String.valueOf(value))
                ),
                false
        );
    }

    private static void unset(CommandContext<CommandSourceStack> ctx, GameProfile profile, String permission) {
        Configuration section = VoiceServer.getInstance().getConfig().getSection("permissions");
        if (!section.getKeys().contains(permission)) {
            ctx.getSource().sendFailure(new TextComponent(VoiceServer.getInstance().getMessagePrefix("permissions.not_found")));
            return;
        }

        PlayerManagerMod playerManager = (PlayerManagerMod) VoiceServer.getAPI().getPlayerManager();
        VoicePlayer player = playerManager.getByUniqueId(profile.getId());

        Map<String, Boolean> perms = playerManager.getPermissions().get(profile.getId());
        if (perms == null || !perms.containsKey(permission)) {
            ctx.getSource().sendFailure(
                    new TextComponent(
                            VoiceServer.getInstance().getMessagePrefix("permissions.no_permission")
                                    .replace("{player}", profile.getName())
                                    .replace("{permission}", permission)
                    )
            );
            return;
        }

        playerManager.unSetPermission(player, permission);
        ctx.getSource().sendSuccess(
                new TextComponent(
                        VoiceServer.getInstance().getMessagePrefix("permissions.unset")
                                .replace("{player}", profile.getName())
                                .replace("{permission}", permission)
                ),
                false
        );
    }
}
