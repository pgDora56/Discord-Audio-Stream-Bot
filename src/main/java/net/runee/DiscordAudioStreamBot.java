package net.runee;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.runee.commands.bot.*;
import net.runee.commands.settings.AutoJoinAudioCommand;
import net.runee.commands.settings.BindCommand;
import net.runee.commands.settings.FollowAudioCommand;
import net.runee.commands.user.*;
import net.runee.errors.BassException;
import net.runee.errors.CommandException;
import net.runee.gui.MainFrame;
import net.runee.misc.Utils;
import net.runee.misc.discord.Command;
import net.runee.model.Config;
import net.runee.model.GuildConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.awt.*;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class DiscordAudioStreamBot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAudioStreamBot.class);
    public static final String NAME = "Discord Audio Stream Bot";
    public static final String GITHUB_URL = "https://github.com/BinkanSalaryman/Discord-Audio-Stream-Bot";

    private static DiscordAudioStreamBot instance;
    public static final File configPath = new File("config.json");
    private static Config config;
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setLenient()
            .create();

    public static DiscordAudioStreamBot getInstance() {
        if (instance == null) {
            instance = new DiscordAudioStreamBot();
        }
        return instance;
    }

    public static boolean hasInstance() {
        return instance != null;
    }

    public static Config getConfig() {
        if (config == null) {
            // load config
            try {
                if (configPath.exists()) {
                    config = gson.fromJson(Utils.readAllText(configPath), Config.class);
                } else {
                    config = new Config();
                    saveConfig();
                }
            } catch (IOException ex) {
                logger.warn("Failed to load or create new config file", ex);
            }
        }
        return config;
    }

    public static void setConfig(Config config) {
        DiscordAudioStreamBot.config = config;
    }

    public static void saveConfig() throws IOException {
        Utils.writeAllText(configPath, gson.toJson(config));
    }

    // data
    private JDA jda;

    // convenience
    private Map<String, Command> commands;

    private DiscordAudioStreamBot() {

    }

    public void login() throws LoginException {
        logger.info("Logging in...");
        jda = JDABuilder.create(config.botToken,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MESSAGES,
                        //GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.DIRECT_MESSAGES
                        //GatewayIntent.DIRECT_MESSAGE_REACTIONS
                )
                .addEventListeners(this)
                .setEnableShutdownHook(false)
                .build()
        ;
        jda.setRequiredScopes("applications.commands"); // necessary for invite url which enables /command interactivity within discord client

        jda.updateCommands()
                .addCommands(getCommands().values()
                        .stream()
                        .map(Command::getData)
                        .collect(Collectors.toList())
                )
                .queue();
    }

    public void logoff() {
        logger.info("Logging off...");
        if (jda != null) {
            jda.shutdown();
        }
    }

    public JDA getJDA() {
        return jda;
    }

    public String getInviteUrl() {
        return jda.getInviteUrl(Permission.EMPTY_PERMISSIONS);
    }

    public Map<String, Command> getCommands() {
        if (commands == null) {
            List<Command> commands = Arrays.asList(
                    // bot
                    new AboutCommand(),
                    new ExitCommand(),
                    new InviteCommand(),
                    new LeaveAudioAllCommand(),
                    new StopCommand(),
                    // bot user
                    new ActivityCommand(),
                    new JoinAudioCommand(),
                    new LeaveGuildCommand(),
                    new LeaveAudioCommand(),
                    new StatusCommand(),
                    new StageCommand(),
                    // settings
                    new AutoJoinAudioCommand(),
                    new BindCommand(),
                    new FollowAudioCommand()
            );

            this.commands = new HashMap<>();
            for (Command cmd : commands) {
                this.commands.put(cmd.getData().getName(), cmd);
            }
        }
        return commands;
    }

    @Override
    public void onReady(@Nonnull ReadyEvent e) {
        autoJoin();
    }

    private void autoJoin() {
        for (GuildConfig guildConfig : Utils.nullListToEmpty(getConfig().guildConfigs)) {
            for (int step = 0; true; step++) {
                switch (step) {
                    case 0:
                        if (guildConfig.followedUserId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn("Failed to retrieve guild with id '" + guildConfig.guildId + "' to follow voice");
                                continue;
                            }
                            Member target = guild.getMemberById(guildConfig.followedUserId);
                            if (target == null) {
                                logger.warn("User with id '" + guildConfig.followedUserId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            AudioChannel target_channel = target.getVoiceState().getChannel();
                            if (target_channel != null) {
                                joinAudio(target_channel);
                                return;
                            }
                        }
                        continue;
                    case 1:
                        if (guildConfig.autoJoinAudioChannelId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn("Failed to retrieve guild with id '" + guildConfig.guildId + "' to auto-join voice");
                                continue;
                            }
                            AudioChannel channel;
                            channel = guild.getVoiceChannelById(guildConfig.autoJoinAudioChannelId);
                            if(channel == null) {
                                channel = guild.getStageChannelById(guildConfig.autoJoinAudioChannelId);
                            }
                            if (channel == null) {
                                logger.warn("Voice channel with id '" + guildConfig.autoJoinAudioChannelId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            joinAudio(channel);
                            return;
                        }
                        continue;
                    default:
                        return;
                }
            }
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        // Ignore bot's own voice state changes to prevent infinite loops
        // Use ID comparison for more reliable detection (especially in JDA beta versions)
        if(event.getMember().getId().equals(jda.getSelfUser().getId())) {
            logger.debug("Ignoring bot's own voice state change in guild: " + event.getGuild().getName());
            return;
        }
        
        if(!isFollowedVoiceTarget(event.getMember())) {
            return;
        }
        
        logger.info("Voice update detected for followed user: " + event.getMember().getEffectiveName() + 
                    " in guild: " + event.getGuild().getName());

        if(event.getChannelJoined() != null && event.getChannelLeft() != null) {
            // audio channel moved
            if(!Objects.equals(event.getChannelJoined().getGuild(), event.getChannelLeft().getGuild())) {
                // moved to another guild's audio channel, disconnect audio manager of left guild
                leaveAudio(event.getChannelLeft().getGuild());
            }
            joinAudio(event.getChannelJoined());
        } else if(event.getChannelJoined() != null) {
            // audio channel joined
            joinAudio(event.getChannelJoined());
        } else if(event.getChannelLeft() != null) {
            // audio channel left
            leaveAudio(event.getChannelLeft().getGuild());
        }
    }

    private boolean isFollowedVoiceTarget(Member member) {
        GuildConfig guildConfig = getConfig().getGuildConfig(member.getGuild());
        return guildConfig.followedUserId != null && Objects.equals(member.getId(), guildConfig.followedUserId);
    }

    @Override
    public void onShutdown(@Nonnull ShutdownEvent e) {

    }

    @Override
    public void onStatusChange(@Nonnull StatusChangeEvent e) {
        switch (e.getNewValue()) {
            case CONNECTED:
                logger.info("Logged in");
                break;
            case SHUTDOWN:
                logger.info("Logged off");
                break;
            case FAILED_TO_LOGIN:
                logger.info("Failed to login");
                break;
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent e) {
        Command cmd = getCommands().get(e.getName());
        if (cmd != null) {
            try {
                cmd.run(e);
            } catch (CommandException ex) {
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription(ex.getReplyMessage())
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            } catch (Exception ex) {
                logger.error("Failed to execute command " + e.getName(), ex);
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription("Failed to execute command; details are in the log.")
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            }
        } else {
            e.replyEmbeds(new EmbedBuilder()
                    .setDescription("Unrecognized command: `" + e.getName() + "`!")
                    .setColor(Utils.colorRed)
                    .build()
            ).setEphemeral(true).queue();
        }
    }

    public void sendDirect(User user, MessageCreateData message) {
        user.openPrivateChannel().queue(chan -> {
            chan.sendMessage(message).queue();
        });
    }

    public void sendDirect(User user, String message) {
        sendDirect(user, MessageCreateData.fromContent(message));
    }

    public void sendDirect(User user, MessageEmbed embed) {
        sendDirect(user, MessageCreateData.fromEmbeds(embed));
    }

    public void joinAudio(AudioChannel channel) {
        AudioManager audioManager = channel.getGuild().getAudioManager();
        
        logger.info("=== JOIN AUDIO REQUEST === Channel: " + channel.getName() + 
                    " (ID: " + channel.getId() + "), Guild: " + channel.getGuild().getName() + 
                    ", Type: " + (channel instanceof StageChannel ? "STAGE" : "VOICE"));
        
        // Check if already connected to the same channel to prevent redundant operations
        if (audioManager.isConnected() && channel.equals(audioManager.getConnectedChannel())) {
            logger.debug("Already connected to channel: " + channel.getName());
            return;
        }
        
        // Log current connection state
        if (audioManager.isConnected()) {
            logger.info("Currently connected to: " + audioManager.getConnectedChannel().getName());
        } else {
            logger.info("Not currently connected to any channel in this guild");
        }
        
        // Setup handlers before connecting - but don't fail if devices aren't available
        // This allows the bot to connect even without audio devices configured
        try {
            updateSpeakState(audioManager, null, null);
            logger.debug("Speak handler setup completed");
        } catch (Exception ex) {
            logger.warn("Failed to setup speak handler, continuing with connection anyway", ex);
        }
        
        try {
            updateListenState(audioManager, null, null);
            logger.debug("Listen handler setup completed");
        } catch (Exception ex) {
            logger.warn("Failed to setup listen handler, continuing with connection anyway", ex);
        }
        
        audioManager.setConnectionListener(new ConnectionListener() {
            @Override
            public void onPing(long ping) {
                EventQueue.invokeLater(() -> MainFrame.getInstance().onPing(ping));
            }

            @Override
            public void onStatusChange(@Nonnull ConnectionStatus status) {
                logger.info("*** Voice Connection Status Changed *** Status: " + status + 
                           ", Channel: " + channel.getName() + 
                           ", Guild: " + audioManager.getGuild().getName());
                
                try {
                    switch (status) {
                        case CONNECTED: {
                            logger.info("Successfully CONNECTED to voice channel: " + channel.getName());
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                try {
                                    ((SpeakHandler) sendingHandler).setPlaying(true);
                                    logger.debug("Speak handler started playing");
                                } catch (Exception ex) {
                                    logger.warn("Failed to start speak handler after connection", ex);
                                }
                            }
                            // Handle stage channel - request to speak after connection
                            if (channel instanceof StageChannel) {
                                StageChannel stageChannel = (StageChannel) channel;
                                logger.info("Stage channel detected, requesting to speak...");
                                try {
                                    // Request to speak (or become speaker if bot has permission)
                                    stageChannel.requestToSpeak().queue(
                                        success -> logger.info("Successfully requested to speak on stage channel: " + stageChannel.getName()),
                                        error -> logger.warn("Failed to request to speak on stage channel: " + stageChannel.getName(), error)
                                    );
                                } catch (Exception ex) {
                                    logger.warn("Error while requesting to speak on stage channel", ex);
                                }
                            }
                            break;
                        }
                        case DISCONNECTED:
                            logger.warn("Voice connection DISCONNECTED from channel: " + channel.getName());
                            // Don't try to restart handlers on disconnect to prevent loops
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                try {
                                    ((SpeakHandler) sendingHandler).setPlaying(false);
                                    logger.debug("Speak handler stopped");
                                } catch (Exception ex) {
                                    logger.warn("Failed to pause speak handler", ex);
                                }
                            }
                            break;
                        case ERROR:
                            logger.error("Voice connection ERROR occurred for channel: " + channel.getName());
                            AudioSendHandler errorHandler = audioManager.getSendingHandler();
                            if (errorHandler instanceof SpeakHandler) {
                                try {
                                    ((SpeakHandler) errorHandler).setPlaying(false);
                                    logger.debug("Speak handler stopped due to error");
                                } catch (Exception ex) {
                                    logger.warn("Failed to pause speak handler after error", ex);
                                }
                            }
                            break;
                        case AUDIO_REGION_CHANGE:
                            logger.info("Audio region changed for channel: " + channel.getName());
                            AudioSendHandler regionHandler = audioManager.getSendingHandler();
                            if (regionHandler instanceof SpeakHandler) {
                                try {
                                    ((SpeakHandler) regionHandler).setPlaying(false);
                                    logger.debug("Speak handler paused for region change");
                                } catch (Exception ex) {
                                    logger.warn("Failed to pause speak handler for region change", ex);
                                }
                            }
                            break;
                        case CONNECTING_AWAITING_ENDPOINT:
                            logger.debug("Connecting to voice channel (awaiting endpoint)...");
                            break;
                        case CONNECTING_AWAITING_WEBSOCKET_CONNECT:
                            logger.debug("Connecting to voice channel (awaiting websocket)...");
                            break;
                        case CONNECTING_AWAITING_AUTHENTICATION:
                            logger.debug("Connecting to voice channel (awaiting authentication)...");
                            break;
                        case CONNECTING_ATTEMPTING_UDP_DISCOVERY:
                            logger.debug("Connecting to voice channel (attempting UDP discovery)...");
                            break;
                        case CONNECTING_AWAITING_READY:
                            logger.debug("Connecting to voice channel (awaiting ready)...");
                            break;
                        default:
                            logger.debug("Voice connection status: " + status);
                            break;
                    }
                } catch (BassException ex) {
                    logger.error("Failed to pause/unpause speak handler for guild " + audioManager.getGuild().getName(), ex);
                } catch (Exception ex) {
                    logger.error("Unexpected error in connection status handler", ex);
                }
            }

            @Override
            public void onUserSpeaking(@Nonnull User user, boolean speaking) {

            }
        });
        
        logger.info("Opening audio connection to channel: " + channel.getName() + " (ID: " + channel.getId() + ")");
        audioManager.openAudioConnection(channel);
        logger.info("Audio connection request sent successfully");
    }

    public void leaveAudio(Guild guild) {
        AudioManager audioManager = guild.getAudioManager();
        logger.info("=== LEAVE AUDIO REQUEST === Guild: " + guild.getName());
        
        if (audioManager.isConnected()) {
            logger.info("Leaving audio channel: " + audioManager.getConnectedChannel().getName());
            
            try {
                updateSpeakState(audioManager, false, null);
                logger.debug("Speak handler cleaned up");
            } catch (Exception ex) {
                logger.warn("Failed to cleanup speak handler while leaving audio channel", ex);
            }
            
            try {
                updateListenState(audioManager, false, null);
                logger.debug("Listen handler cleaned up");
            } catch (Exception ex) {
                logger.warn("Failed to cleanup listen handler while leaving audio channel", ex);
            }
            
            try {
                audioManager.closeAudioConnection();
                logger.info("Audio connection closed successfully");
            } catch (Exception ex) {
                logger.error("Failed to close audio connection for guild " + guild.getName(), ex);
            }
        } else {
            logger.info("Not connected to any audio channel in this guild");
        }
    }

    public void leaveVoiceAll() {
        for (AudioManager audioManager : jda.getAudioManagers()) {
            leaveAudio(audioManager.getGuild());
        }
    }

    public void updateSpeakState(AudioManager audioManager, Boolean speakEnabled, String recordingDevice) {
        speakEnabled = speakEnabled != null ? speakEnabled : config.getSpeakEnabled();
        recordingDevice = recordingDevice != null ? recordingDevice : config.recordingDevice;

        // audio send handler
        AudioSendHandler sendingHandler = audioManager.getSendingHandler();
        if (speakEnabled) {
            if (sendingHandler == null) {
                sendingHandler = new SpeakHandler();
            }
            if (sendingHandler instanceof SpeakHandler) {
                try {
                    ((SpeakHandler) sendingHandler).openRecordingDevice(Utils.getRecordingDeviceHandle(recordingDevice), audioManager.isConnected());
                } catch (BassException ex) {
                    logger.error("Failed to open recording device '" + recordingDevice + "'", ex);
                    sendingHandler = null;
                    speakEnabled = false;
                }
            }
        } else {
            if (sendingHandler != null) {
                if (sendingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) sendingHandler);
                }
                sendingHandler = null;
            }
        }
        audioManager.setSendingHandler(sendingHandler);
        audioManager.setSelfMuted(!speakEnabled);
    }

    public void updateListenState(AudioManager audioManager, Boolean listenEnabled, String playbackDevice) {
        listenEnabled = listenEnabled != null ? listenEnabled : config.getListenEnabled();
        playbackDevice = playbackDevice != null ? playbackDevice : config.playbackDevice;

        // audio receive handler
        AudioReceiveHandler receivingHandler = audioManager.getReceivingHandler();
        if (listenEnabled) {
            if (receivingHandler == null) {
                receivingHandler = new ListenHandler();
            }
            if (receivingHandler instanceof ListenHandler) {
                try {
                    ((ListenHandler) receivingHandler).openPlaybackDevice(Utils.getPlaybackDeviceHandle(playbackDevice));
                } catch (BassException ex) {
                    logger.error("Failed to open playback device '" + playbackDevice + "'", ex);
                    receivingHandler = null;
                    listenEnabled = false;
                }
            }
        } else {
            if (receivingHandler != null) {
                if (receivingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) receivingHandler);
                }
                receivingHandler = null;
            }
        }
        audioManager.setReceivingHandler(receivingHandler);
        audioManager.setSelfDeafened(!listenEnabled);
    }

    public void setSpeakEnabled(boolean speakEnabled) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, speakEnabled, null);
        }
    }

    public void setListenEnabled(boolean listenEnabled) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, listenEnabled, null);
        }
    }

    public void setRecordingDevice(String recordingDevice) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, null, recordingDevice);
        }
    }

    public void setPlaybackDevice(String playbackDevice) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, null, playbackDevice);
        }
    }

    private List<AudioManager> getConnectedAudioManagers() {
        if (jda != null) {
            List<AudioManager> result = new ArrayList<>();
            for (AudioManager audioManager : jda.getAudioManagers()) {
                if (audioManager.isConnected()) {
                    result.add(audioManager);
                }
            }
            return result;
        } else {
            return Collections.emptyList();
        }
    }
}
