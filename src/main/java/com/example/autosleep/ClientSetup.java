package com.example.autosleep;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

public final class ClientSetup {
    public static final String KEY_CATEGORY = "key.categories.autosleep";
    public static final String KEY_TOGGLE = "key.autosleep.toggle";

    private static KeyBinding toggleKey;

    private ClientSetup() {
    }

    public static void init() {
        toggleKey = new KeyBinding(
                KEY_TOGGLE,
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                InputMappings.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KEY_CATEGORY
        );
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(new AutoSleepEventHandler());
    }

    public static KeyBinding getToggleKey() {
        return toggleKey;
    }
}
