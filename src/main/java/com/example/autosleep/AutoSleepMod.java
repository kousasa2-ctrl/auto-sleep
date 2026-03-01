package com.example.autosleep;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(AutoSleepMod.MOD_ID)
public class AutoSleepMod {
    public static final String MOD_ID = "autosleep";

    public AutoSleepMod() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init);
    }
}
