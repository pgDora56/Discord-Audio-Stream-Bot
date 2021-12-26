package net.runee.commands.user;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildChannel;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.runee.DiscordAudioStreamBot;
import net.runee.errors.CommandException;
import net.runee.misc.Utils;
import net.runee.misc.discord.Command;

import java.util.List;

public class JoinVoiceCommand extends Command {
    public JoinVoiceCommand() {
        super(new CommandData("join", "Join a voice channel"));
        data.addOption(OptionType.CHANNEL, "channel", "Voice channel in question", false);
        data.addOption(OptionType.BOOLEAN, "public", "Whether to show this command to others or not", false);
    }

    @Override
    public void run(SlashCommandEvent ctx) throws CommandException {
        _public = getOptionalBoolean(ctx, "public", false);

        // parse args
        final Guild guild = ensureAdminOrOwnerPermission(ctx);

        OptionMapping channelOpt = ctx.getOption("channel");
        VoiceChannel channel;
        if (channelOpt != null) {
            // join specific
            GuildChannel guildChannel = ensureOptionPresent(ctx, "channel").getAsGuildChannel();
            if (guildChannel instanceof VoiceChannel) {
                channel = (VoiceChannel) guildChannel;
            } else {
                reply(ctx, "Channel is not a voice channel", Utils.colorRed);
                return;
            }
        } else {
            // join automatically
            List<VoiceChannel> channelMatches = Utils.findSuitableVoiceChannel(guild, ctx.getMember());
            switch (channelMatches.size()) {
                case 0:
                    reply(ctx, "No voice channel found!", Utils.colorRed);
                    return;
                default:
                    channel = channelMatches.get(0);
                    break;
            }
        }


        // execute
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.VOICE_CONNECT)) {
            // missing permissions
            reply(ctx, "Insufficient permissions to join a voice channel!", Utils.colorRed);
            return;
        }

        DiscordAudioStreamBot.getInstance().joinVoice(channel);
        reply(ctx, "Joined voice channel `" + channel.getName() + "`.", Utils.colorGreen);
    }
}