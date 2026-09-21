package dev.hostd.mcinmc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.ProfileResult;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringHelper;
import net.minecraft.util.Uuids;

public final class Profiles {

    private static final Map<String, GameProfile> CACHE = new ConcurrentHashMap<>();

    private Profiles() {
    }

    public static GameProfile resolve(MinecraftServer server, String name) {
        GameProfile cached = CACHE.get(name.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return cached;
        }
        GameProfile online = lookup(server, name);
        if (online == null) {
            return Uuids.getOfflinePlayerProfile(name);
        }
        CACHE.put(name.toLowerCase(Locale.ROOT), online);
        return online;
    }

    private static GameProfile lookup(MinecraftServer server, String name) {
        if (!StringHelper.isValidPlayerName(name)) {
            return null;
        }
        AtomicReference<GameProfile> found = new AtomicReference<>();
        try {
            server.getGameProfileRepo().findProfilesByNames(new String[]{name}, new ProfileLookupCallback() {
                @Override
                public void onProfileLookupSucceeded(GameProfile profile) {
                    found.set(profile);
                }

                @Override
                public void onProfileLookupFailed(String profileName, Exception exception) {
                }
            });
            GameProfile base = found.get();
            if (base == null) {
                return null;
            }
            ProfileResult result = server.getSessionService().fetchProfile(base.getId(), false);
            return result == null ? base : result.profile();
        } catch (Exception e) {
            System.out.println("[mcinmc] skin lookup for " + name + " failed: " + e);
            return null;
        }
    }
}
