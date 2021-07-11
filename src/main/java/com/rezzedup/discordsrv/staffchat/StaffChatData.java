package com.rezzedup.discordsrv.staffchat;

import com.rezzedup.discordsrv.staffchat.config.Configs;
import com.rezzedup.discordsrv.staffchat.config.StaffChatConfig;
import com.rezzedup.discordsrv.staffchat.events.AutoStaffChatToggleEvent;
import com.rezzedup.discordsrv.staffchat.events.ReceivingStaffChatToggleEvent;
import community.leaf.configvalues.bukkit.YamlValue;
import community.leaf.configvalues.bukkit.data.YamlDataFile;
import community.leaf.configvalues.bukkit.util.Sections;
import community.leaf.tasks.TaskContext;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.tlinkowski.annotation.basic.NullOr;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class StaffChatData
{
    private static final String PROFILES_PATH = "staff-chat.profiles";
    
    private final Map<UUID, Profile> profilesByUuid = new HashMap<>();
    
    private final StaffChatPlugin plugin;
    private final YamlDataFile yaml;
    
    private @NullOr TaskContext<BukkitTask> task = null;
    
    public StaffChatData(StaffChatPlugin plugin)
    {
        this.plugin = plugin;
        this.yaml = new YamlDataFile(plugin.directory().resolve("data"), "staff-chat.data.yml");
        
        reload();
    }
    
    protected void end()
    {
        if (task != null) { task.cancel(); }
        if (yaml.isUpdated()) { yaml.save(); }
    }
    
    public void reload()
    {
        end(); // End any existing tasks
        plugin.getServer().getOnlinePlayers().forEach(this::updateProfile);
        
        // Load persistent toggles
        if (plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES))
        {
            Sections.get(yaml.data(), PROFILES_PATH).ifPresent(section ->
            {
                for (String key : section.getKeys(false))
                {
                    try { getOrCreateProfile(UUID.fromString(key)); }
                    catch (IllegalArgumentException ignored) {}
                }
            });
        }
        
        // Start the save task
        task = plugin.async().every(2).minutes().run(() -> {
            if (yaml.isUpdated()) { yaml.save(); }
        });
        
        // Bring back staff members who left the staff chat if leaving is disabled
        if (!plugin.config().getOrDefault(StaffChatConfig.LEAVING_STAFFCHAT_ENABLED))
        {
            profilesByUuid.values().forEach(profile -> profile.receivesStaffChatMessages(true));
        }
    }
    
    public StaffChatProfile getOrCreateProfile(UUID uuid)
    {
        return profilesByUuid.computeIfAbsent(uuid, k -> new Profile(plugin, yaml, k));
    }
    
    public StaffChatProfile getOrCreateProfile(Player player)
    {
        return getOrCreateProfile(player.getUniqueId());
    }
    
    public Optional<StaffChatProfile> getProfile(UUID uuid)
    {
        return Optional.ofNullable(profilesByUuid.get(uuid));
    }
    
    public Optional<StaffChatProfile> getProfile(Player player)
    {
        return getProfile(player.getUniqueId());
    }
    
    public boolean isChatAutomatic(Player player)
    {
        return getProfile(player).filter(StaffChatProfile::automaticStaffChat).isPresent();
    }
    
    public void updateProfile(Player player)
    {
        @NullOr Profile profile = profilesByUuid.get(player.getUniqueId());
        
        if (Permissions.ACCESS.allows(player))
        {
            if (profile == null) { getOrCreateProfile(player); }
        }
        else
        {
            // Not a staff member but has a loaded profile...
            if (profile != null)
            {
                // Notify that they're no longer talking in staff chat.
                if (profile.automaticStaffChat()) { profile.automaticStaffChat(false); }
                
                // No longer staff, delete data.
                profile.clearStoredProfileData();
                
                // Remove from the map.
                profilesByUuid.remove(player.getUniqueId());
            }
        }
    }
    
    static class Profile implements StaffChatProfile
    {
        static final YamlValue<Instant> AUTO_TOGGLE_DATE = YamlValue.of("toggles.auto", Configs.INSTANT).maybe();
        
        static final YamlValue<Instant> LEFT_TOGGLE_DATE = YamlValue.of("toggles.left", Configs.INSTANT).maybe();
        
        private final StaffChatPlugin plugin;
        private final YamlDataFile yaml;
        private final UUID uuid;
        
        private @NullOr Instant auto;
        private @NullOr Instant left;
        
        Profile(StaffChatPlugin plugin, YamlDataFile yaml, UUID uuid)
        {
            this.plugin = plugin;
            this.yaml = yaml;
            this.uuid = uuid;
            
            if (plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES))
            {
                Sections.get(yaml.data(), path()).ifPresent(section -> {
                    auto = AUTO_TOGGLE_DATE.get(section).orElse(null);
                    left = LEFT_TOGGLE_DATE.get(section).orElse(null);
                });
            }
        }
        
        String path()
        {
            return PROFILES_PATH + "." + uuid;
        }
        
        @Override
        public UUID uuid() { return uuid; }
    
        @Override
        public Optional<Instant> sinceEnabledAutoChat()
        {
            return Optional.ofNullable(auto);
        }
    
        @Override
        public boolean automaticStaffChat()
        {
            return auto != null;
        }
    
        @Override
        public void automaticStaffChat(boolean enabled)
        {
            // Avoid redundantly setting: already enabled if auto is not null
            if (enabled == (auto != null)) { return; }
            
            if (plugin.events().call(new AutoStaffChatToggleEvent(this, enabled)).isCancelled()) { return; }
            
            auto = (enabled) ? Instant.now() : null;
            updateStoredProfileData();
        }
        
        @Override
        public Optional<Instant> sinceLeftStaffChat()
        {
            return Optional.ofNullable(left);
        }
    
        @Override
        public boolean receivesStaffChatMessages()
        {
            // hasn't left the staff chat or leaving is disabled outright
            return left == null || !plugin.config().getOrDefault(StaffChatConfig.LEAVING_STAFFCHAT_ENABLED);
        }
        
        @Override
        public void receivesStaffChatMessages(boolean enabled)
        {
            // Avoid redundantly setting: already enabled if `left` is null
            // (the staff member is receiving messages because they haven't... left the chat)
            if (enabled == (left == null)) { return; }
            
            if (plugin.events().call(new ReceivingStaffChatToggleEvent(this, enabled)).isCancelled()) { return; }
            
            left = (enabled) ? null : Instant.now();
            updateStoredProfileData();
        }
        
        boolean hasDefaultSettings()
        {
            return auto == null && left == null;
        }
    
        void clearStoredProfileData()
        {
            if (!plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) { return; }
        
            yaml.data().set(path(), null);
            yaml.updated(true);
        }
        
        void updateStoredProfileData()
        {
            if (!plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) { return; }
            
            if (hasDefaultSettings())
            {
                clearStoredProfileData();
                return;
            }
            
            ConfigurationSection section = Sections.getOrCreate(yaml.data(), path());
        
            AUTO_TOGGLE_DATE.set(section, auto);
            LEFT_TOGGLE_DATE.set(section, left);
            
            yaml.updated(true);
        }
    }
}
